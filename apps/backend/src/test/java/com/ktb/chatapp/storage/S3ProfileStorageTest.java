// S3 프로필 스토리지의 key 제한과 기본 입출력을 검증한다.
package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.springframework.util.StreamUtils;

@ExtendWith(MockitoExtension.class)
class S3ProfileStorageTest {

    private static final String BUCKET = "profile-bucket";
    private static final String PROFILE_KEY = "profiles/avatar.png";

    @Mock
    private S3Client s3Client;

    private S3ProfileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3ProfileStorage(s3Client, BUCKET, true);
    }

    @Test
    void put_sendsProfileObjectToConfiguredBucket() {
        storage.put(
                new ByteArrayInputStream("image".getBytes(StandardCharsets.UTF_8)),
                PROFILE_KEY,
                "image/png",
                5L);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(PROFILE_KEY);
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("image/png");
        assertThat(requestCaptor.getValue().contentLength()).isEqualTo(5L);
    }

    @Test
    void open_returnsS3ObjectBytes() throws Exception {
        byte[] image = "image".getBytes(StandardCharsets.UTF_8);
        ResponseBytes<GetObjectResponse> response = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().contentLength((long) image.length).build(),
                image);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(response);

        Optional<org.springframework.core.io.Resource> result = storage.open(PROFILE_KEY);

        assertThat(result).isPresent();
        assertThat(StreamUtils.copyToString(result.orElseThrow().getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo("image");
    }

    @Test
    void open_returnsEmptyWhenS3ObjectDoesNotExist() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThat(storage.open(PROFILE_KEY)).isEmpty();
    }

    @Test
    void delete_sendsDeleteRequestToConfiguredBucket() {
        storage.delete(PROFILE_KEY);

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());

        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(PROFILE_KEY);
    }

    @Test
    void put_rejectsChatKey() {
        assertThatThrownBy(() -> storage.put(
                new ByteArrayInputStream(new byte[] {1}),
                "chat/file.png",
                "image/png",
                1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
