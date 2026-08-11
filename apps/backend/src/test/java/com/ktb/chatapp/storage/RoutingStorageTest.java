// 프로필·채팅 key가 서로 다른 저장소로 라우팅되는지 검증한다.
package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

@ExtendWith(MockitoExtension.class)
class RoutingStorageTest {

    @TempDir
    private Path uploadDir;

    @Mock
    private S3ProfileStorage profileStorage;

    private LocalStorage localStorage;

    @BeforeEach
    void setUp() {
        localStorage = new LocalStorage(uploadDir.toString());
        localStorage.init();
    }

    @Test
    void s3Mode_routesProfileToS3AndChatToLocal() {
        RoutingStorage routingStorage = new RoutingStorage(localStorage, profileStorage, "s3");

        routingStorage.put(content("profile"), "profiles/avatar.png", "image/png", 7L);
        routingStorage.put(content("chat"), "chat/file.png", "image/png", 4L);

        verify(profileStorage).put(any(), eq("profiles/avatar.png"), eq("image/png"), eq(7L));
        verify(profileStorage, never()).put(any(), eq("chat/file.png"), any(), anyLong());
        assertThat(uploadDir.resolve("chat/file.png")).exists();
    }

    @Test
    void s3Mode_readsExistingLocalProfileWhenS3ObjectIsMissing() throws Exception {
        localStorage.put(content("old"), "profiles/old.png", "image/png", 3L);
        when(profileStorage.open("profiles/old.png")).thenReturn(Optional.empty());

        Optional<Resource> result = new RoutingStorage(localStorage, profileStorage, "s3")
                .open("profiles/old.png");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getInputStream().readAllBytes())
                .containsExactly("old".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void localMode_keepsProfileOnLocalStorage() {
        RoutingStorage routingStorage = new RoutingStorage(localStorage, profileStorage, "local");

        routingStorage.put(content("profile"), "profiles/avatar.png", "image/png", 7L);

        verify(profileStorage, never()).put(any(), any(), any(), anyLong());
        assertThat(uploadDir.resolve("profiles/avatar.png")).exists();
    }

    @Test
    void rejectsUnsupportedStorageType() {
        assertThatThrownBy(() -> new RoutingStorage(localStorage, profileStorage, "gridfs"))
                .isInstanceOf(IllegalStateException.class);
    }

    private ByteArrayInputStream content(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
