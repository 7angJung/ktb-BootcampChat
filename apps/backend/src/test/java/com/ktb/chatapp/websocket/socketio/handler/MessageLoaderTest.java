package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.ReadStatusUpdate;
import net.datafaker.Faker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageLoaderTest {
    
    @Mock
    private MessageRepository messageRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private FileRepository fileRepository;
    
    @Mock
    private MessageReadStatusService messageReadStatusService;
    
    @InjectMocks
    private MessageLoader messageLoader;
    
    private Faker faker;
    private List<Message> testMessages;
    private String roomId;
    private String userId;
    
    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();
        
        messageLoader = new MessageLoader(
                messageRepository,
                userRepository,
                fileRepository,
                new MessageResponseMapper(fileRepository),
                messageReadStatusService
        );
        
        var testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();
        
        // 테스트 메시지 50개 생성 (오름차순: 오래된 것 → 최신 것)
        // i=0: 50시간 전, i=1: 49시간 전, ... i=49: 1시간 전
        testMessages = IntStream.range(0, 50)
                .mapToObj(i -> createMessage(
                        faker.internet().uuid(),
                        LocalDateTime.now().minusHours(50 - i)
                ))
                .toList();
        
        lenient().when(userRepository.findAllById(anySet()))
                .thenReturn(List.of(testUser));
        lenient().when(messageReadStatusService.updateReadStatus(anyString(), anyList(), anyString()))
                .thenReturn(ReadStatusUpdate.empty());
    }
    
    private Message createMessage(String id, LocalDateTime timestamp) {
        Message message = new Message();
        message.setId(id);
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));
        message.setTimestamp(timestamp);
        return message;
    }
    
    @Test
    @DisplayName("loadMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[0~29] (50시간 전 ~ 21시간 전) - 오름차순 상태
        List<Message> first30Messages = testMessages.subList(0, 30);
        
        // DB는 DESC 정렬로 반환한다고 가정 (최신 것 먼저)
        // [21시간 전, 22시간 전, ..., 50시간 전]
        var messagePage = getMessagePage(first30Messages);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(messagePage);
        
        // When: 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isTrue();
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [50시간 전, 49시간 전, ..., 21시간 전]
        verifyAscending(result);
        verify(userRepository, times(1)).findAllById(Set.of(userId));
        verify(userRepository, never()).findById(anyString());
    }
    
    private static @NotNull Page<Message> getMessagePage(List<Message> first30Messages) {
        List<Message> messages = new ArrayList<>(first30Messages.reversed());
        
        Pageable pageable = PageRequest.of(0, 30, Sort.by("timestamp").descending());
        Page<Message> messagePage = new PageImpl<>(messages, pageable, 50);
        return messagePage;
    }
    
    @Test
    @DisplayName("loadInitialMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadInitialMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[20~49] (30시간 전 ~ 1시간 전) - 최신 30개 메시지
        List<Message> last30Messages = testMessages.subList(20, 50);
        
        // DB는 DESC 정렬로 반환 (최신 것부터)
        // [1시간 전, 2시간 전, ..., 30시간 전]
        Page<Message> messagePage = getMessagePage(last30Messages);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(messagePage);
        
        // When: 초기 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [30시간 전, 29시간 전, ..., 1시간 전]
        verifyAscending(result);
    }
    
    private static void verifyAscending(FetchMessagesResponse result) {
        for (int i = 0; i < result.getMessages().size() - 1; i++) {
            long current = result.getMessages().get(i).getTimestamp();
            long next = result.getMessages().get(i + 1).getTimestamp();
            assertThat(current).isLessThanOrEqualTo(next);
        }
    }
    
    @Test
    @DisplayName("loadInitialMessages: 에러 시 빈 응답")
    void loadInitialMessages_shouldReturnEmptyOnError() {
        when(messageRepository.findByRoomIdAndTimestampBefore(
                any(), any(LocalDateTime.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB error"));
        
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("loadMessages: 발신자와 파일을 각각 한 번에 조회")
    void loadMessages_shouldBatchUsersAndFiles() {
        String secondUserId = faker.internet().uuid();
        String firstFileId = faker.internet().uuid();
        String secondFileId = faker.internet().uuid();
        User firstUser = User.builder().id(userId).name("first").email("first@test.com").build();
        User secondUser = User.builder().id(secondUserId).name("second").email("second@test.com").build();
        File firstFile = File.builder().id(firstFileId).filename("first.png").originalname("first.png")
                .mimetype("image/png").size(10).build();
        File secondFile = File.builder().id(secondFileId).filename("second.png").originalname("second.png")
                .mimetype("image/png").size(20).build();

        Message first = createMessage(faker.internet().uuid(), LocalDateTime.now().minusMinutes(2));
        first.setFileId(firstFileId);
        Message second = createMessage(faker.internet().uuid(), LocalDateTime.now().minusMinutes(1));
        second.setSenderId(secondUserId);
        second.setFileId(secondFileId);
        Page<Message> page = new PageImpl<>(List.of(second, first), PageRequest.of(0, 30), 2);

        when(messageRepository.findByRoomIdAndTimestampBefore(eq(roomId), any(), any()))
                .thenReturn(page);
        when(userRepository.findAllById(Set.of(userId, secondUserId)))
                .thenReturn(List.of(firstUser, secondUser));
        when(fileRepository.findAllById(Set.of(firstFileId, secondFileId)))
                .thenReturn(List.of(firstFile, secondFile));

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).hasSize(2);
        assertThat(result.getMessages()).allSatisfy(message -> assertThat(message.getFile()).isNotNull());
        verify(userRepository, times(1)).findAllById(Set.of(userId, secondUserId));
        verify(fileRepository, times(1)).findAllById(Set.of(firstFileId, secondFileId));
        verify(fileRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("loadMessages: sender와 file이 없거나 삭제되어도 응답 생성")
    void loadMessages_shouldHandleMissingRelations() {
        Message systemMessage = createMessage(faker.internet().uuid(), LocalDateTime.now().minusMinutes(2));
        systemMessage.setSenderId(null);
        Message deletedRelations = createMessage(faker.internet().uuid(), LocalDateTime.now().minusMinutes(1));
        deletedRelations.setSenderId("deleted-user");
        deletedRelations.setFileId("deleted-file");
        Page<Message> page = new PageImpl<>(List.of(deletedRelations, systemMessage), PageRequest.of(0, 30), 2);

        when(messageRepository.findByRoomIdAndTimestampBefore(eq(roomId), any(), any()))
                .thenReturn(page);
        when(userRepository.findAllById(Set.of("deleted-user"))).thenReturn(List.of());
        when(fileRepository.findAllById(Set.of("deleted-file"))).thenReturn(List.of());

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).hasSize(2);
        assertThat(result.getMessages()).allSatisfy(message -> {
            assertThat(message.getSender()).isNull();
            assertThat(message.getFile()).isNull();
        });
    }
}
