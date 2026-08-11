import React from 'react';

const MessageDeliveryStatus = ({ status, error }) => {
  if (status === 'pending') {
    return (
      <span className="text-xs text-gray-400" role="status">
        전송 중...
      </span>
    );
  }

  if (status === 'rejected') {
    return (
      <span
        className="text-xs text-red-400"
        role="alert"
        title={error || '메시지를 전송하지 못했습니다.'}
      >
        전송 실패
      </span>
    );
  }

  return null;
};

export default React.memo(MessageDeliveryStatus);
