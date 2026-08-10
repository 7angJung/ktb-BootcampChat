// 새 메시지 저장 알림을 방별로 짧게 합쳐 방 목록 count 조회를 줄인다.
package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RoomActivityNotifier {

    private static final long COALESCE_MILLIS = 250L;

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory());
    private final Map<String, ScheduledFuture<?>> pendingRooms = new ConcurrentHashMap<>();

    public RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher) {
        this.recentMessageCounter = recentMessageCounter;
        this.eventPublisher = eventPublisher;
    }

    public void notifyMessageStored(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }

        pendingRooms.computeIfAbsent(roomId, key -> scheduler.schedule(
                () -> publishRoomActivity(key),
                COALESCE_MILLIS,
                TimeUnit.MILLISECONDS));
    }

    private void publishRoomActivity(String roomId) {
        pendingRooms.remove(roomId);
        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        pendingRooms.values().forEach(future -> future.cancel(false));
        pendingRooms.clear();
        scheduler.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "room-activity-notifier");
            thread.setDaemon(true);
            return thread;
        }
    }
}
