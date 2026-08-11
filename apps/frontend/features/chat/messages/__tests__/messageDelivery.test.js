import { describe, expect, it } from 'vitest';
import {
  createPendingMessage,
  normalizeDeliveredMessages,
  rejectPendingMessage,
  settleDeliveredMessage,
} from '../messageDelivery';

describe('messageDelivery', () => {
  it('creates a pending message and replaces it with the matching server message', () => {
    const pending = createPendingMessage({
      clientMessageId: 'client-1',
      roomId: 'room-1',
      currentUser: { id: 'user-1' },
      type: 'text',
      content: 'hello',
    });

    const delivered = settleDeliveredMessage([pending], {
      _id: 'message-1',
      clientMessageId: 'client-1',
      content: 'hello',
      type: 'text',
    });

    expect(pending.deliveryStatus).toBe('pending');
    expect(delivered).toHaveLength(1);
    expect(delivered[0]).toMatchObject({
      _id: 'message-1',
      clientMessageId: 'client-1',
      deliveryStatus: 'sent',
    });
  });

  it('does not duplicate repeated server events', () => {
    const incoming = { _id: 'message-1', clientMessageId: 'client-1' };
    const once = settleDeliveredMessage([], incoming);

    expect(settleDeliveredMessage(once, incoming)).toBe(once);
  });

  it('marks only the matching pending message as rejected', () => {
    const messages = [
      { clientMessageId: 'client-1', deliveryStatus: 'pending' },
      { clientMessageId: 'client-2', deliveryStatus: 'pending' },
    ];

    expect(rejectPendingMessage(messages, 'client-1', 'blocked')).toEqual([
      {
        clientMessageId: 'client-1',
        deliveryStatus: 'rejected',
        deliveryError: 'blocked',
      },
      messages[1],
    ]);
  });

  it('normalizes server-loaded messages as sent without mutating the input', () => {
    const loaded = [{ _id: 'message-1' }];
    const normalized = normalizeDeliveredMessages(loaded);

    expect(normalized).toEqual([{ _id: 'message-1', deliveryStatus: 'sent' }]);
    expect(loaded).toEqual([{ _id: 'message-1' }]);
  });
});
