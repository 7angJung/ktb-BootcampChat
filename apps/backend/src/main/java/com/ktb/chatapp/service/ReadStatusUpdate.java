// 읽음 batch 처리 결과와 서버 생성 시각을 전달한다.
package com.ktb.chatapp.service;

import java.util.List;

public record ReadStatusUpdate(List<String> messageIds, String readAt) {
    public static ReadStatusUpdate empty() {
        return new ReadStatusUpdate(List.of(), InstantHolder.now());
    }

    private static final class InstantHolder {
        private static String now() {
            return java.time.Instant.now().toString();
        }
    }
}
