// 프로필 이미지 key만 Amazon S3에 저장하고 조회한다.
package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3ProfileStorage implements StoragePort {

    private final S3Client s3Client;
    private final String bucket;

    @Autowired
    public S3ProfileStorage(
            S3Client s3Client,
            @Value("${s3.bucket:}") String bucket) {
        this(s3Client, bucket, true);
    }

    S3ProfileStorage(S3Client s3Client, String bucket, boolean validateBucket) {
        this.s3Client = s3Client;
        this.bucket = bucket == null ? "" : bucket.trim();
        if (validateBucket && !StringUtils.hasText(this.bucket)) {
            throw new IllegalStateException("S3_BUCKET은 FILE_STORAGE_TYPE=s3일 때 필수입니다.");
        }
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        requireProfileKey(key);

        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength(size);
        if (StringUtils.hasText(contentType)) {
            requestBuilder.contentType(contentType);
        }

        try {
            s3Client.putObject(requestBuilder.build(), RequestBody.fromInputStream(content, size));
            return new StoredObject(key, size);
        } catch (RuntimeException ex) {
            throw new RuntimeException("S3 프로필 이미지 저장에 실패했습니다.", ex);
        }
    }

    @Override
    public Optional<Resource> open(String key) {
        requireProfileKey(key);

        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ByteArrayResource(response.asByteArray()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw new RuntimeException("S3 프로필 이미지 조회에 실패했습니다.", ex);
        } catch (RuntimeException ex) {
            throw new RuntimeException("S3 프로필 이미지 조회에 실패했습니다.", ex);
        }
    }

    @Override
    public void delete(String key) {
        requireProfileKey(key);

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ex) {
            throw new RuntimeException("S3 프로필 이미지 삭제에 실패했습니다.", ex);
        }
    }

    private void requireProfileKey(String key) {
        if (!StorageKey.isProfile(key)) {
            throw new IllegalArgumentException("S3 프로필 스토리지는 profiles/ key만 처리합니다.");
        }
    }
}
