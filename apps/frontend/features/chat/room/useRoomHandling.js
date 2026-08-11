import { useRef, useEffect, useCallback } from 'react';
import socketClient from '@/lib/socket/socketClient';
import { useAuth } from '@/contexts/AuthContext';
import { Toast } from '@/components/Toast';
import {
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from './roomEventHandlers';

const getRoomConnectionErrorMessage = error => (
  error?.message?.includes('초과')
    ? '채팅방 연결 시간이 초과되었습니다.'
    : error?.message || '채팅방 연결에 실패했습니다.'
);

export const useRoomHandling = ({
  roomId,
  route,
  state,
  refs,
  actions,
  cleanup,
  handleReactionUpdate,
}) => {
  const { onReplace } = route;
  const { currentUser } = state;
  const {
    socketRef,
    attachSocket,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  } = refs;
  const {
    setRoom,
    setError,
    setMessages,
    setHasMoreMessages,
    setLoadingMessages,
    setupStarted,
    joinStarted,
    connectionEstablished,
    setupSucceeded,
    setupFailed,
  } = actions;
  const { user, logout } = useAuth();
  const setupPromiseRef = useRef(null);
  const roomEventsUnsubscribeRef = useRef(null);
  const MAX_SOCKET_RECONNECT_ATTEMPTS = 3;
  const MAX_MESSAGE_RETRY_ATTEMPTS = 3;
  const MESSAGE_TIMEOUT = 5000;
  const MESSAGE_RETRY_DELAY = 2000;

  const processMessages = useCallback(
    (loadedMessages, hasMore, isInitialLoad = false) => {
      processLoadedRoomMessages({
        loadedMessages,
        hasMore,
        isInitialLoad,
        processedMessageIds,
        setMessages,
        setHasMoreMessages,
        initialLoadCompletedRef,
      });
    },
    [
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    ]
  );

  const setupEventListeners = useCallback(() => {
    if (!socketRef.current || !mountedRef.current) return;

    if (roomEventsUnsubscribeRef.current) {
      roomEventsUnsubscribeRef.current();
      roomEventsUnsubscribeRef.current = null;
    }

    roomEventsUnsubscribeRef.current = socketClient.subscribeRoomEvents(
      socketRef.current,
      createRoomEventHandlers({
        mountedRef,
        messageProcessingRef,
        processedMessageIds,
        initialLoadCompletedRef,
        processMessages,
        setRoom,
        setMessages,
        setLoadingMessages,
        setError,
        setHasMoreMessages,
        cleanup,
        logout,
        onReplace,
        handleReactionUpdate,
        showRejectedMessage: Toast.error.bind(Toast),
      })
    );
  }, [
    processMessages,
    setHasMoreMessages,
    cleanup,
    handleReactionUpdate,
    setLoadingMessages,
    setError,
    logout,
    socketRef,
    mountedRef,
    messageProcessingRef,
    processedMessageIds,
    initialLoadCompletedRef,
    setRoom,
    setMessages,
    onReplace,
  ]);

  const setupSocket = useCallback(async () => {
    try {
      if (!user?.token || !user?.sessionId) {
        throw new Error('Invalid authentication state');
      }

      if (socketRef.current?.connected) {
        return socketRef.current;
      }

      if (socketRef.current) {
        const currentSocket = socketRef.current;

        if (userRooms.current?.get(currentSocket.id)) {
          await new Promise((resolve) => {
            socketClient.leaveRoom(
              userRooms.current.get(currentSocket.id),
              currentSocket
            );
            setTimeout(resolve, 1000);
          });
          userRooms.current.delete(currentSocket.id);
        }

        currentSocket.disconnect();
        currentSocket.removeAllListeners();
        attachSocket(null);

        await new Promise((resolve) => setTimeout(resolve, 2000));
      }

      const socket = await socketClient.connect({
        auth: {
          token: user.token,
          sessionId: user.sessionId,
        },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: MAX_SOCKET_RECONNECT_ATTEMPTS,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 3000,
        timeout: 10000,
        pingTimeout: 10000,
        pingInterval: 8000,
        forceNew: true,
        autoConnect: true,
      });

      return socket;
    } catch (error) {
      if (error.message === 'Invalid authentication state') {
        onReplace('/?error=auth_required');
      }
      throw error;
    }
  }, [userRooms, onReplace, socketRef, attachSocket, user]);

  const joinRoom = useCallback(
    async (targetRoomId) => {
      if (!targetRoomId || !mountedRef.current) {
        throw new Error('잘못된 채팅방 정보입니다.');
      }

      const socket = socketRef.current;
      if (!socket?.connected) {
        throw new Error('Socket not connected');
      }

      joinStarted();
      const joiningSocketId = socket.id;
      const data = await socketClient.joinRoomAndWait(targetRoomId, socket);

      // A previous socket may complete after Socket.IO has already replaced it.
      // Its response must not overwrite the state owned by the live socket.
      if (
        !mountedRef.current
        || socketRef.current !== socket
        || socketRef.current?.id !== joiningSocketId
        || !socketRef.current?.connected
      ) {
        return null;
      }

      const responseRoomId = data?.roomId;
      const payloadRoomId = data?.room?._id ?? data?.room?.id;
      if (
        responseRoomId !== targetRoomId
        || payloadRoomId !== targetRoomId
        || !Array.isArray(data?.messages)
        || typeof data?.hasMore !== 'boolean'
      ) {
        throw new Error('채팅방 초기 데이터가 올바르지 않습니다.');
      }

      userRooms.current?.set(socket.id, targetRoomId);
      return data;
    },
    [socketRef, mountedRef, userRooms, joinStarted]
  );

  // 재연결 뒤 필요한 것은 방 참가 상태 복구뿐이다. socket.io 가 같은 소켓을
  // 되살렸으므로 방 이벤트 구독도 그대로 살아 있다 — 여기서 소켓을 새로 만들면
  // 살아 있는 연결을 버리는 셈이 된다.
  const rejoinRoom = useCallback(async () => {
    const socket = socketRef.current;
    if (!roomId || !mountedRef.current || !socket?.connected) {
      return;
    }

    try {
      const joinResult = await joinRoom(roomId);
      if (!joinResult) return;

      processMessages(joinResult.messages, joinResult.hasMore, true);
      setupSucceeded(joinResult.room);

      if (mountedRef.current) {
        setupCompleteRef.current = true;
      }
    } catch (error) {
      if (mountedRef.current) {
        setupFailed(getRoomConnectionErrorMessage(error));
      }
      throw error;
    }
  }, [
    roomId,
    socketRef,
    mountedRef,
    setupCompleteRef,
    joinRoom,
    processMessages,
    setupSucceeded,
    setupFailed,
  ]);

  const loadInitialMessages = useCallback(
    async (roomId) => {
      const loadMessagesWithRetry = async (retryCount = 0) => {
        const socket = socketRef.current;
        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        try {
          const response = await socketClient.fetchPreviousMessagesAndWait(
            { roomId, limit: 30 },
            socket,
            { timeoutMs: MESSAGE_TIMEOUT }
          );

          if (!response || !Array.isArray(response.messages)) {
            throw new Error('잘못된 메시지 응답 형식입니다.');
          }

          processMessages(response.messages, response.hasMore, true);
          return response;
        } catch (error) {
          if (retryCount < MAX_MESSAGE_RETRY_ATTEMPTS) {
            await new Promise((resolve) =>
              setTimeout(resolve, MESSAGE_RETRY_DELAY)
            );
            return loadMessagesWithRetry(retryCount + 1);
          }

          throw error;
        }
      };

      try {
        return await loadMessagesWithRetry();
      } catch (error) {
        if (!socketRef.current?.connected) {
          // setupSocket 은 낡은 소켓을 버리고 새 소켓을 반환한다. 받아서 걸어주지
          // 않으면 ref 가 비어 있어 재시도가 곧바로 'Socket not connected' 로 죽는다.
          attachSocket(await setupSocket());
          return loadMessagesWithRetry();
        }
        throw error;
      }
    },
    [socketRef, attachSocket, processMessages, setupSocket]
  );

  const setupRoom = useCallback(async () => {
    if (setupPromiseRef.current) {
      return setupPromiseRef.current;
    }

    setupPromiseRef.current = (async () => {
      try {
        initializingRef.current = true;
        setupStarted();
        // 1. Socket Setup
        attachSocket(await setupSocket());
        if (!socketRef.current?.connected) {
          throw new Error('Socket not connected');
        }
        connectionEstablished();

        // 2. Subscribe before joining so no room event is missed.
        if (mountedRef.current) {
          setupEventListeners();
        }

        // 3. Socket join returns room metadata and initial messages together.
        if (mountedRef.current && socketRef.current?.connected) {
          const joinResult = await joinRoom(roomId);
          if (!joinResult) return;
          const roomData = joinResult.room;

          // Ensure current user is included in participants for display
          if (currentUser && roomData.participants) {
            const isUserInParticipants = roomData.participants.some(
              (p) => p._id === currentUser.id || p.id === currentUser.id
            );

            if (!isUserInParticipants) {
              roomData.participants = [
                ...roomData.participants,
                {
                  _id: currentUser.id,
                  id: currentUser.id,
                  name: currentUser.name,
                  email: currentUser.email,
                },
              ];
            }
          }

          processMessages(joinResult.messages, joinResult.hasMore, true);
          setupSucceeded(roomData);
        }

        if (mountedRef.current) {
          setupCompleteRef.current = true;
        }
      } catch (error) {
        if (mountedRef.current) {
          const errorMessage = getRoomConnectionErrorMessage(error);

          setupFailed(errorMessage);
          cleanup('ERROR');

          if (socketRef.current) {
            socketRef.current.disconnect();
            attachSocket(null);
          }
        }

        throw error;
      } finally {
        if (mountedRef.current) {
          initializingRef.current = false;
        }

        setupPromiseRef.current = null;
      }
    })();

    return setupPromiseRef.current;
  }, [
    roomId,
    socketRef,
    attachSocket,
    mountedRef,
    setupSocket,
    joinRoom,
    processMessages,
    cleanup,
    setupEventListeners,
    setupStarted,
    connectionEstablished,
    setupSucceeded,
    setupFailed,
    currentUser,
    initializingRef,
    setupCompleteRef,
  ]);

  useEffect(() => {
    return () => {
      setupPromiseRef.current = null;
      initializingRef.current = false;
      setupCompleteRef.current = false;

      if (roomEventsUnsubscribeRef.current) {
        roomEventsUnsubscribeRef.current();
        roomEventsUnsubscribeRef.current = null;
      }

      // 언마운트 경로는 attachSocket 을 쓰지 않는다. 사라지는 컴포넌트에
       // 소켓 교체를 통지할 구독자가 없다.
      if (socketRef.current) {
        socketRef.current.disconnect();
        socketRef.current = null;
      }
    };
  }, [initializingRef, setupCompleteRef, socketRef]);

  return {
    setupRoom,
    rejoinRoom,
    loadInitialMessages,
  };
};

export default useRoomHandling;
