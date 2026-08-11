import { useCallback } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useChatFileUpload } from '../files/useChatFileUpload';
import {
  createPendingMessage,
  rejectPendingMessage,
  settleDeliveredMessage,
} from '../messages/messageDelivery';

const createClientMessageId = () => {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export const useMessageHandling = (
  currentUser,
  roomId,
  handleSessionError,
  messages = [],
  loadingMessages = false,
  setLoadingMessages,
  socketRef,
  setMessages = () => {},
) => {
 const {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   setUploading,
   setUploadProgress,
   setUploadError,
   resetFileUpload,
   uploadChatFile
 } = useChatFileUpload();

  const getRoomSocket = useCallback(() => socketRef?.current ?? null, [socketRef]);

  const canSendOnRoomSocket = useCallback(() => {
    if (socketRef) {
      return Boolean(getRoomSocket()?.connected);
    }

    return socketClient.canSend();
  }, [getRoomSocket, socketRef]);

  const handleLoadMore = useCallback(() => {
    if (!canSendOnRoomSocket()) {
      return;
    }

    if (loadingMessages) {
      return;
    }

    // 가장 오래된 메시지의 타임스탬프 찾기
    const sortedMessages = [...messages].sort(
      (a, b) => new Date(a.timestamp) - new Date(b.timestamp)
    );
    const oldestMessage = sortedMessages[0];
    const beforeTimestamp = oldestMessage?.timestamp;

    if (!beforeTimestamp) {
      return;
    }

    setLoadingMessages(true);

    // Socket.IO 이벤트만 발행 - 응답은 useChatRoom의 previousMessagesLoaded 이벤트 핸들러에서 처리
    socketClient.fetchPreviousMessages({
      roomId: roomId,
      before: beforeTimestamp,
      limit: 30
    }, getRoomSocket());
  }, [roomId, loadingMessages, messages, setLoadingMessages, canSendOnRoomSocket, getRoomSocket]);

 const handleMessageSubmit = useCallback(async (messageData) => {
   const roomSocket = getRoomSocket();
   if (!canSendOnRoomSocket() || !currentUser) {
     Toast.error('채팅 서버와 연결이 끊어졌습니다.');
     return;
   }

   if (!roomId) {
     Toast.error('채팅방 정보를 찾을 수 없습니다.');
     return;
   }

   let clientMessageId;
   let pendingCreated = false;
   try {
      if (messageData.type === 'file') {
        const uploadResponse = await uploadChatFile(
          messageData.fileData.file,
          currentUser
        );

       clientMessageId = createClientMessageId();
       const file = uploadResponse.data.file;
       setMessages(prev => [...prev, createPendingMessage({
         clientMessageId,
         roomId,
         currentUser,
         type: 'file',
         content: messageData.content || '',
         file,
       })]);
       pendingCreated = true;

       const deliveredMessage = await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'file',
         content: messageData.content || '',
         clientMessageId,
         fileData: {
           _id: file._id,
           filename: file.filename,
           originalname: file.originalname,
           mimetype: file.mimetype,
           size: file.size
         }
       }, roomSocket);
       setMessages(prev => settleDeliveredMessage(prev, deliveredMessage));

       resetFileUpload();

     } else if (messageData.content?.trim()) {
       clientMessageId = createClientMessageId();
       const content = messageData.content.trim();
       setMessages(prev => [...prev, createPendingMessage({
         clientMessageId,
         roomId,
         currentUser,
         type: 'text',
         content,
       })]);
       pendingCreated = true;

       const deliveredMessage = await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'text',
         content,
         clientMessageId,
       }, roomSocket);
       setMessages(prev => settleDeliveredMessage(prev, deliveredMessage));
     }

   } catch (error) {
     if (pendingCreated) {
       setMessages(prev => rejectPendingMessage(
         prev,
         clientMessageId,
         error?.message,
       ));
     }
     if (error.message?.includes('세션') ||
         error.message?.includes('인증') ||
         error.message?.includes('토큰')) {
       if (typeof handleSessionError === 'function') {
         await handleSessionError();
       }
       return;
     }

     // 서버가 거부한 메시지는 onError 핸들러가 이미 토스트로 알렸다.
     // 여기서 또 띄우면 같은 사유의 토스트가 두 개 뜬다.
     if (error?.code !== 'MESSAGE_REJECTED') {
       Toast.error(error.message || '메시지 전송 중 오류가 발생했습니다.');
     }
     if (messageData.type === 'file') {
       setUploadError(error.message);
       setUploading(false);
     }
   }
 }, [currentUser, roomId, handleSessionError, uploadChatFile, resetFileUpload, setUploadError, setUploading, setMessages, canSendOnRoomSocket, getRoomSocket]);

 const removeFilePreview = useCallback(() => {
   resetFileUpload();
 }, [resetFileUpload]);

 return {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   handleMessageSubmit,
   handleLoadMore,
   removeFilePreview,
 };
};

export default useMessageHandling;
