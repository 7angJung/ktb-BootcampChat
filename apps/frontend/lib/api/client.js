import axios from 'axios';
import { clearAuthStorage, loadStoredUser } from '../auth/authStorage';
import {
  createAuthExpiredError,
  createHttpError,
  createNetworkError,
  getRetryDelay,
  isCanceledRequest,
  isRetryableError,
  RETRY_CONFIG,
} from './errors';

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:5000';

/** 서버 생존 확인은 공통 타임아웃보다 짧게 끊는다. 호출하는 화면마다 값이 갈리지 않도록 여기서 정한다. */
export const HEALTH_TIMEOUT_MS = 3000;

const COOLDOWN_FAILURE_THRESHOLD = 2;
const COOLDOWN_MS = 3000;

const stableParams = (params = {}) => JSON.stringify(
  Object.keys(params).sort().reduce((result, key) => {
    result[key] = params[key];
    return result;
  }, {})
);

const getRequestKey = (config) => [
  config.method?.toUpperCase(),
  config.baseURL,
  config.url,
  stableParams(config.params),
  config.headers?.['x-auth-token'] || '',
  config.headers?.['x-session-id'] || '',
].join(':');

export const getAuthHeaders = (session = loadStoredUser()) => {
  if (!session?.token) {
    return {};
  }

  return {
    'x-auth-token': session.token,
    ...(session.sessionId ? { 'x-session-id': session.sessionId } : {}),
  };
};

export const createApiClient = ({
  baseURL = API_BASE_URL,
  getSession = loadStoredUser,
} = {}) => {
  const pendingRequests = new Map();
  const endpointFailures = new Map();

  const axiosInstance = axios.create({
    baseURL,
    timeout: 5000,
    withCredentials: true,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
  });

  axiosInstance.interceptors.request.use(
    (config) => {
      if (config.method !== 'get' && !config.data) {
        config.data = {};
      }

      if (!config.skipAuth) {
        config.headers = {
          ...config.headers,
          ...getAuthHeaders(getSession()),
        };
      }

      const method = config.method?.toUpperCase();
      if ((method === 'GET' || method === 'HEAD') && !config.dedupeConfigured) {
        const requestKey = getRequestKey(config);
        const cooldownUntil = endpointFailures.get(requestKey)?.cooldownUntil || 0;

        if (!config.bypassCooldown && cooldownUntil > Date.now()) {
          const cooldownError = new Error('잠시 후 다시 시도해주세요.');
          cooldownError.code = 'REQUEST_COOLDOWN';
          cooldownError.config = config;
          return Promise.reject(cooldownError);
        }

        const adapter = axios.getAdapter(config.adapter || axiosInstance.defaults.adapter);
        config.dedupeConfigured = true;
        config.requestKey = requestKey;
        config.adapter = (adapterConfig) => {
          const pending = pendingRequests.get(requestKey);
          if (pending) {
            return pending;
          }

          const request = adapter(adapterConfig).finally(() => {
            pendingRequests.delete(requestKey);
          });
          pendingRequests.set(requestKey, request);
          return request;
        };
      }

      return config;
    },
    (error) => Promise.reject(error)
  );

  axiosInstance.interceptors.response.use(
    (response) => {
      if (response.config.requestKey) {
        endpointFailures.delete(response.config.requestKey);
      }

      return response;
    },
    async (error) => {
      const config = error.config || {};
      config.retryCount = config.retryCount || 0;

      if (isCanceledRequest(error)) {
        return Promise.reject(error);
      }

      if (error.response?.status === 401 && config.handleAuthError !== false) {
        clearAuthStorage();

        if (
          typeof window !== 'undefined' &&
          window.location.pathname !== '/' &&
          window.location.pathname !== '/login'
        ) {
          window.location.href = '/';
        }

        throw createAuthExpiredError(error, config);
      }

      if (error.response?.status === 401 && config.handleAuthError === false) {
        return Promise.reject(error);
      }

      if (config.requestKey && [502, 503, 504].includes(error.response?.status)) {
        const failures = endpointFailures.get(config.requestKey)?.count || 0;
        const count = failures + 1;
        endpointFailures.set(config.requestKey, {
          count,
          cooldownUntil: count >= COOLDOWN_FAILURE_THRESHOLD ? Date.now() + COOLDOWN_MS : 0,
        });
      }

      if (!config.skipRetry && isRetryableError(error) && config.retryCount < RETRY_CONFIG.maxRetries) {
        config.retryCount++;
        const delay = getRetryDelay(config.retryCount);

        try {
          await new Promise((resolve) => setTimeout(resolve, delay));
          return await axiosInstance(config);
        } catch (retryError) {
          return Promise.reject(retryError);
        }
      }

      const retry = async () => axiosInstance(config);

      if (!error.response) {
        throw createNetworkError(error, config, retry);
      }

      throw createHttpError(error, config, retry);
    }
  );

  return axiosInstance;
};

const apiClient = createApiClient();

export default apiClient;
