import socketService from '../../services/socket';

const sendDomainEvent = (service, socket, event, data) => {
  if (socket) {
    return service.sendOn(socket, event, data);
  }

  return service.send(event, data);
};

const ensureConnectedSocket = (socket) => {
  if (!socket?.connected) {
    throw new Error('Socket not connected');
  }
};

const createTimeoutError = (message) => new Error(message);

const waitForSocketEvent = ({
  socket,
  successEvent,
  errorEvents,
  timeoutMs,
  timeoutMessage,
  send,
  matchesSuccess = () => true,
  matchesError = () => true,
}) => {
  ensureConnectedSocket(socket);

  return new Promise((resolve, reject) => {
    let timeoutId;

    const cleanup = () => {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
      socket.off(successEvent, handleSuccess);
      for (const event of errorEvents) {
        socket.off(event, handleError);
      }
    };

    const settle = (callback, value) => {
      cleanup();
      callback(value);
    };

    const handleSuccess = (data) => {
      if (matchesSuccess(data)) settle(resolve, data);
    };
    const handleError = (error) => {
      if (matchesError(error)) settle(reject, error);
    };

    // Correlated waits may need to ignore events that belong to another request,
    // so listeners must stay registered until a matching event settles the wait.
    socket.on(successEvent, handleSuccess);
    for (const event of errorEvents) {
      socket.on(event, handleError);
    }

    timeoutId = setTimeout(() => {
      settle(reject, createTimeoutError(timeoutMessage));
    }, timeoutMs);

    try {
      send();
    } catch (error) {
      settle(reject, error);
    }
  });
};

const roomEventMap = {
  participantsUpdate: 'onParticipantsUpdate',
  messagesRead: 'onMessagesRead',
  message: 'onMessage',
  previousMessagesLoaded: 'onPreviousMessagesLoaded',
  messageReactionUpdate: 'onMessageReactionUpdate',
  session_ended: 'onSessionEnded',
  error: 'onError',
};

const connectionEventMap = {
  connect: 'onConnect',
  disconnect: 'onDisconnect',
  connect_error: 'onConnectError',
};

/**
 * socket.io v4 에서 재연결 이벤트는 socket 이 아니라 manager(socket.io)에서 발생한다.
 * socket 에 붙이면 영원히 호출되지 않아 재연결 성공도 최종 실패도 감지하지 못한다.
 */
const managerEventMap = {
  reconnect_attempt: 'onReconnecting',
  reconnect: 'onReconnect',
  reconnect_failed: 'onReconnectFailed',
};

const subscribeMappedEvents = (emitter, handlers, eventMap) => {
  if (!emitter) {
    return () => {};
  }

  const subscriptions = Object.entries(eventMap)
    .map(([event, handlerName]) => [event, handlers[handlerName]])
    .filter(([, handler]) => typeof handler === 'function');

  for (const [event, handler] of subscriptions) {
    emitter.on(event, handler);
  }

  return () => {
    for (const [event, handler] of subscriptions) {
      emitter.off(event, handler);
    }
  };
};

export const createSocketClient = (service = socketService) => {
  const readQueues = new Map();

  const queueKey = (roomId, socket) => `${socket?.id || 'default'}:${roomId}`;

  const flushReadQueue = (key) => {
    const queue = readQueues.get(key);
    if (!queue) return;

    const messageIds = [...queue.messageIds].slice(0, 50);
    messageIds.forEach(messageId => queue.messageIds.delete(messageId));

    try {
      client.markMessagesAsRead(queue.roomId, messageIds, queue.socket);
    } catch (error) {
      messageIds.forEach(messageId => queue.messageIds.add(messageId));
    }

    if (queue.messageIds.size > 0) {
      queue.timer = setTimeout(() => flushReadQueue(key), 100);
    } else {
      queue.timer = null;
    }
  };

  const client = {
  connect: (options) => service.connect(options),
  disconnect: () => service.disconnect(),
  isConnected: () => service.isConnected(),
  canSend: () => service.isConnected(),
  send: (event, data) => service.send(event, data),
  sendChatMessage: (payload, socket) => sendDomainEvent(service, socket, 'chatMessage', payload),
  sendChatMessageAndWait: (payload, socket, { timeoutMs = 8000 } = {}) =>
    waitForSocketEvent({
      socket,
      successEvent: 'message',
      errorEvents: ['error', 'disconnect'],
      timeoutMs,
      timeoutMessage: '메시지 전송이 지연되고 있습니다. 다시 시도해주세요.',
      matchesSuccess: message => message?.clientMessageId === payload.clientMessageId,
      matchesError: error => (
        typeof error === 'string'
        ||
        error?.clientMessageId === payload.clientMessageId
        || error?.code === 'SESSION_EXPIRED'
      ),
      send: () => sendDomainEvent(service, socket, 'chatMessage', payload),
    }),
  fetchPreviousMessages: (payload, socket) => sendDomainEvent(service, socket, 'fetchPreviousMessages', payload),
  fetchPreviousMessagesAndWait: (payload, socket, { timeoutMs = 10000 } = {}) =>
    waitForSocketEvent({
      socket,
      successEvent: 'previousMessagesLoaded',
      errorEvents: ['error'],
      timeoutMs,
      timeoutMessage: '메시지 로딩 시간이 초과되었습니다.',
      send: () => sendDomainEvent(service, socket, 'fetchPreviousMessages', payload),
    }),
  joinRoom: (roomId, socket) => sendDomainEvent(service, socket, 'joinRoom', { roomId }),
  joinRoomAndWait: (roomId, socket, { timeoutMs = 10000 } = {}) =>
    waitForSocketEvent({
      socket,
      successEvent: 'joinRoomSuccess',
      errorEvents: ['joinRoomError', 'error', 'disconnect'],
      timeoutMs,
      timeoutMessage: '채팅방 입장 시간이 초과되었습니다.',
      send: () => sendDomainEvent(service, socket, 'joinRoom', { roomId }),
    }),
  leaveRoom: (roomId, socket) => sendDomainEvent(service, socket, 'leaveRoom', roomId),
  tryLeaveRoom: (roomId, socket) => service.trySendOn(socket, 'leaveRoom', roomId),
  markMessagesAsRead: (roomId, messageIds, socket) => {
    if (typeof roomId !== 'string' || !roomId.trim()) {
      throw new Error('roomId must be a non-empty string');
    }
    if (!Array.isArray(messageIds)) {
      throw new Error('messageIds must be an array');
    }

    const uniqueMessageIds = [...new Set(messageIds.filter(Boolean))];
    if (uniqueMessageIds.length === 0 || uniqueMessageIds.length > 50) {
      throw new Error('messageIds must contain 1 to 50 ids');
    }

    return sendDomainEvent(service, socket, 'markMessagesAsRead', {
      roomId,
      messageIds: uniqueMessageIds,
    });
  },
  enqueueMessagesAsRead: (roomId, messageIds, socket) => {
    if (typeof roomId !== 'string' || !roomId.trim() || !Array.isArray(messageIds)) {
      throw new Error('roomId and messageIds are required');
    }

    const key = queueKey(roomId, socket);
    const queue = readQueues.get(key) || {
      roomId,
      socket,
      messageIds: new Set(),
      timer: null,
    };

    messageIds.filter(Boolean).forEach(messageId => queue.messageIds.add(messageId));
    readQueues.set(key, queue);

    if (!queue.timer) {
      queue.timer = setTimeout(() => flushReadQueue(key), 100);
    }
  },
  clearReadQueues: (socket) => {
    for (const [key, queue] of readQueues.entries()) {
      if (!socket || queue.socket === socket || queue.socket?.id === socket.id) {
        if (queue.timer) clearTimeout(queue.timer);
        readQueues.delete(key);
      }
    }
  },
  sendMessageReaction: (messageId, reaction, type, socket) => sendDomainEvent(service, socket, 'messageReaction', {
    messageId,
    reaction,
    type,
  }),
  subscribeRoomEvents: (socket, handlers) => subscribeMappedEvents(socket, handlers, roomEventMap),
  subscribeConnectionEvents: (socket, handlers) => {
    const unsubscribeSocket = subscribeMappedEvents(socket, handlers, connectionEventMap);
    const unsubscribeManager = subscribeMappedEvents(socket?.io, handlers, managerEventMap);

    return () => {
      unsubscribeSocket();
      unsubscribeManager();
    };
  },
  };

  return client;
};

const socketClient = createSocketClient();

export default socketClient;
