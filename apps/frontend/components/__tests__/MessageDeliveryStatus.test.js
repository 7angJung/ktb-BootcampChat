import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import MessageDeliveryStatus from '../MessageDeliveryStatus';

describe('MessageDeliveryStatus', () => {
  it('renders pending and rejected states accessibly', () => {
    const { rerender } = render(
      React.createElement(MessageDeliveryStatus, { status: 'pending' }),
    );
    expect(screen.getByRole('status')).toHaveTextContent('전송 중...');

    rerender(React.createElement(MessageDeliveryStatus, {
      status: 'rejected',
      error: '금칙어가 포함되었습니다.',
    }));
    expect(screen.getByRole('alert')).toHaveTextContent('전송 실패');
    expect(screen.getByRole('alert')).toHaveAttribute('title', '금칙어가 포함되었습니다.');
  });

  it('does not render a status for sent messages', () => {
    const { container } = render(
      React.createElement(MessageDeliveryStatus, { status: 'sent' }),
    );
    expect(container).toBeEmptyDOMElement();
  });
});
