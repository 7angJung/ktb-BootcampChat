import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatRoomInfo from '../ChatRoomInfo';

vi.mock('../CustomAvatar', () => ({
  default: ({ user }) => <span>{user.name}</span>,
}));

describe('ChatRoomInfo', () => {
  it('renders participants that only have id without React key warnings', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ChatRoomInfo
        connectionStatus="connected"
        room={{
          name: '테스트방',
          participants: [{ id: 'user-1', name: 'Codex UI', email: 'codex@example.com' }],
        }}
      />
    );

    expect(screen.getByText('1명')).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalledWith(
      expect.stringContaining('Each child in a list should have a unique "key" prop.'),
      expect.anything()
    );

    errorSpy.mockRestore();
  });

  it.each([
    ['connected', '서버 연결됨'],
    ['joining', '채팅방 입장 중...'],
    ['ready', '채팅 가능'],
    ['connecting', '재접속 중...'],
    ['disconnected', '연결 끊김'],
    ['error', '연결 끊김'],
  ])('shows %s status as %s', (connectionStatus, label) => {
    render(<ChatRoomInfo connectionStatus={connectionStatus} room={{ participants: [] }} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
