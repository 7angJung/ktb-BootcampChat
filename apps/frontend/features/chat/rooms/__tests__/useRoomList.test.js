import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms, metadata = { page: 0, hasMore: false }) => ({
  data: { data: rooms, metadata },
});

const renderRoomList = () =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router: { push: vi.fn() },
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('requests rooms in pages of 20 and appends the next page without duplicates', async () => {
    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse(
        [{ _id: 'room-1' }, { _id: 'room-2' }],
        { page: 0, hasMore: true }
      ))
      .mockResolvedValueOnce(roomsResponse(
        [{ _id: 'room-2', name: 'updated' }, { _id: 'room-3' }],
        { page: 1, hasMore: false }
      ));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });
    await act(async () => {
      await result.current.loadMoreRooms();
    });

    expect(axiosInstance.get).toHaveBeenNthCalledWith(1, '/api/rooms', {
      params: { page: 0, size: 20 },
    });
    expect(axiosInstance.get).toHaveBeenNthCalledWith(2, '/api/rooms', {
      params: { page: 1, size: 20 },
    });
    expect(result.current.rooms).toEqual([
      { _id: 'room-1' },
      { _id: 'room-2', name: 'updated' },
      { _id: 'room-3' },
    ]);
    expect(result.current.hasMore).toBe(false);
  });

  it('keeps loaded rooms when loading the next page fails', async () => {
    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }], { page: 0, hasMore: true }))
      .mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });
    await act(async () => {
      await result.current.loadMoreRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toMatchObject({ title: '채팅방 추가 로드 실패' });
    expect(result.current.loadingMore).toBe(false);
  });
});
