export const DELIVERY_STATUS = Object.freeze({
  PENDING: 'pending',
  SENT: 'sent',
  REJECTED: 'rejected',
});

export const createPendingMessage = ({
  clientMessageId,
  roomId,
  currentUser,
  type,
  content,
  file,
}) => ({
  _id: `pending:${clientMessageId}`,
  room: roomId,
  clientMessageId,
  content,
  type,
  file,
  sender: currentUser,
  timestamp: Date.now(),
  reactions: {},
  readers: [],
  deliveryStatus: DELIVERY_STATUS.PENDING,
});

export const settleDeliveredMessage = (messages, incoming) => {
  if (!incoming?._id) return messages;

  const clientMessageId = incoming.clientMessageId;
  const pendingIndex = clientMessageId
    ? messages.findIndex(message => message.clientMessageId === clientMessageId)
    : -1;
  const savedIndex = messages.findIndex(message => message._id === incoming._id);

  if (savedIndex >= 0 && savedIndex === pendingIndex) return messages;

  if (pendingIndex >= 0) {
    const next = [...messages];
    next[pendingIndex] = {
      ...incoming,
      deliveryStatus: DELIVERY_STATUS.SENT,
    };

    if (savedIndex >= 0 && savedIndex !== pendingIndex) {
      next.splice(savedIndex, 1);
    }
    return next;
  }

  if (savedIndex >= 0) return messages;
  return [...messages, { ...incoming, deliveryStatus: DELIVERY_STATUS.SENT }];
};

export const rejectPendingMessage = (messages, clientMessageId, reason) => {
  if (!clientMessageId) return messages;

  let changed = false;
  const next = messages.map(message => {
    if (
      message.clientMessageId !== clientMessageId
      || message.deliveryStatus === DELIVERY_STATUS.SENT
    ) {
      return message;
    }

    changed = true;
    return {
      ...message,
      deliveryStatus: DELIVERY_STATUS.REJECTED,
      deliveryError: reason || '메시지를 전송하지 못했습니다.',
    };
  });

  return changed ? next : messages;
};

export const normalizeDeliveredMessages = messages => (
  messages.map(message => ({
    ...message,
    deliveryStatus: message.deliveryStatus || DELIVERY_STATUS.SENT,
  }))
);
