import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('does not submit while Korean IME composition is active', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '가나다' } });
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      isComposing: true,
    });

    expect(onSubmit).not.toHaveBeenCalled();
    expect(input).toHaveValue('가나다');

    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      isComposing: false,
    });

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({
      type: 'text',
      content: '가나다',
    });
  });
});
