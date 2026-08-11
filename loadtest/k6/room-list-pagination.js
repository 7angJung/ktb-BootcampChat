import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const API_URL = __ENV.API_URL || 'http://localhost:5001';
const VUS = Number(__ENV.VUS || 20);
const ROOM_COUNT = Number(__ENV.ROOM_COUNT || 120);
const PAGE_SIZE = Number(__ENV.PAGE_SIZE || 20);
const RUN_ID = `${Date.now()}`;

const roomListSuccess = new Rate('room_list_success');
const roomListPayloadValid = new Rate('room_list_payload_valid');
const roomListDuration = new Trend('room_list_duration', true);
const roomListResponseBytes = new Trend('room_list_response_bytes');

export const options = {
  scenarios: {
    room_list: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.STAGE_1 || '5s', target: Math.min(5, VUS) },
        { duration: __ENV.STAGE_2 || '10s', target: Math.min(10, VUS) },
        { duration: __ENV.STAGE_3 || '10s', target: VUS },
        { duration: __ENV.STAGE_4 || '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    room_list_success: ['rate>0.99'],
    room_list_payload_valid: ['rate>0.99'],
    room_list_duration: ['p(95)<500'],
  },
};

function requestOptions(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '10s',
  };
}

export function setup() {
  const email = `k6-room-list-${RUN_ID}@test.com`;
  const password = 'Test1234!';
  const register = http.post(
    `${API_URL}/api/auth/register`,
    JSON.stringify({ email, password, name: `K6 Room List ${RUN_ID}` }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );
  if (register.status !== 201) fail(`registration failed: ${register.status}`);

  const login = http.post(
    `${API_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );
  if (login.status !== 200) fail(`login failed: ${login.status}`);

  const token = login.json('token');
  for (let index = 0; index < ROOM_COUNT; index += 1) {
    const room = http.post(
      `${API_URL}/api/rooms`,
      JSON.stringify({ name: `k6-room-list-${RUN_ID}-${String(index).padStart(3, '0')}` }),
      requestOptions(token),
    );
    if (room.status !== 201) fail(`room seed failed at ${index}: ${room.status}`);
  }

  return { token };
}

export default function (data) {
  const page = (__ITER + __VU) % Math.ceil(ROOM_COUNT / PAGE_SIZE);
  const startedAt = Date.now();
  const response = http.get(
    `${API_URL}/api/rooms?page=${page}&size=${PAGE_SIZE}`,
    requestOptions(data.token),
  );
  roomListDuration.add(Date.now() - startedAt);
  roomListResponseBytes.add(response.body ? response.body.length : 0);

  let payload;
  try {
    payload = response.json();
  } catch (error) {
    payload = null;
  }

  const dataIsValid = Array.isArray(payload?.data)
    && payload.data.length <= PAGE_SIZE
    && payload.data.every((room) =>
      typeof room?._id === 'string'
      && typeof room?.participantsCount === 'number'
      && !Object.prototype.hasOwnProperty.call(room, 'participants'))
    && payload?.metadata?.page === page
    && payload?.metadata?.pageSize === PAGE_SIZE;

  const succeeded = response.status === 200;
  roomListSuccess.add(succeeded);
  roomListPayloadValid.add(dataIsValid);
  check(response, {
    'room list returns 200': () => succeeded,
    'room list payload is lightweight and paginated': () => dataIsValid,
  });

  sleep(0.2);
}
