import { deriveUniqueSortedMessages } from '../messages/useMessageList';
import {
  normalizeDeliveredMessages,
  rejectPendingMessage,
  settleDeliveredMessage,
} from '../messages/messageDelivery';

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const normalizedMessages = normalizeDeliveredMessages(loadedMessages);
  const processedSnapshot = new Set(processedMessageIds.current);
  processedMessageIds.current = deriveUniqueSortedMessages(
    [],
    normalizedMessages,
    processedSnapshot
  ).processedMessageIds;

  let nextMessages;
  setMessages(prev => {
    nextMessages = deriveUniqueSortedMessages(prev, normalizedMessages, processedSnapshot).messages;
    return nextMessages;
  });
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

export const applyReadReceipts = (messages, { userId, messageIds, readAt }) => {
  const messageIdSet = new Set(messageIds || []);

  return messages.map(msg => {
    if (!messageIdSet.has(msg._id)) {
      return msg;
    }

    const alreadyRead = msg.readers?.some(reader =>
      reader.userId === userId || reader._id === userId
    );
    if (alreadyRead) {
      return msg;
    }

    return {
      ...msg,
      readers: [...(msg.readers || []), { userId, readAt }],
    };
  });
};

export const appendIncomingMessage = (messages, incoming) => {
  if (!incoming?._id) {
    return messages;
  }

  if (messages.some(msg => msg._id === incoming._id)) {
    return messages;
  }

  return [...messages, incoming];
};

export const createRoomEventHandlers = ({
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
  showRejectedMessage,
}) => {
  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
      processMessages(loadedMessages, hasMore, isInitialLoad);
      setLoadingMessages(false);
    } catch (error) {
      setLoadingMessages(false);
      setError('메시지 처리 중 오류가 발생했습니다.');
      setHasMoreMessages(false);
    } finally {
      messageProcessingRef.current = false;
    }
  };

  return {
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      setMessages(prev => applyReadReceipts(prev, payload));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id) return;
      if (processedMessageIds.current.has(incoming._id)) {
        setMessages(prev => settleDeliveredMessage(prev, incoming));
        return;
      }
      processedMessageIds.current.add(incoming._id);
      setMessages(prev => settleDeliveredMessage(prev, incoming));
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
      cleanup();
      logout();
      onReplace('/?error=session_expired');
    },
    onError: (error) => {
      if (!mountedRef.current) return;
      console.error('Socket error:', error);
      if (error?.code === 'MESSAGE_REJECTED') {
        if (error.clientMessageId) {
          setMessages(prev => rejectPendingMessage(
            prev,
            error.clientMessageId,
            error.message,
          ));
        }
        showRejectedMessage(error.message || '금칙어가 포함되어 메시지를 전송할 수 없습니다.');
        return;
      }
      if (error?.clientMessageId) {
        setMessages(prev => rejectPendingMessage(
          prev,
          error.clientMessageId,
          error.message,
        ));
        return;
      }
      setError(error.message || '채팅 연결에 문제가 발생했습니다.');
    },
  };
};
