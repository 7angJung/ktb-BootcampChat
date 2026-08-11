// S3 모드에서는 프로필 이미지와 채팅 파일을 S3로 보내고 기존 로컬 파일 조회를 지원한다.
package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

@Component
@Primary
public class RoutingStorage implements StoragePort {

    private final LocalStorage localStorage;
    private final S3ProfileStorage profileStorage;

    @Autowired
    public RoutingStorage(
            LocalStorage localStorage,
            ObjectProvider<S3ProfileStorage> profileStorageProvider,
            @Value("${file.storage.type:local}") String storageType) {
        this(localStorage, profileStorageProvider.getIfAvailable(), storageType);
    }

    RoutingStorage(LocalStorage localStorage, S3ProfileStorage profileStorage, String storageType) {
        this.localStorage = localStorage;

        String normalizedType = storageType == null
                ? "local"
                : storageType.trim().toLowerCase(Locale.ROOT);
        if (!normalizedType.equals("local") && !normalizedType.equals("s3")) {
            throw new IllegalStateException("FILE_STORAGE_TYPE은 local 또는 s3만 지원합니다.");
        }
        if (normalizedType.equals("s3") && profileStorage == null) {
            throw new IllegalStateException("FILE_STORAGE_TYPE=s3인데 S3 스토리지가 등록되지 않았습니다.");
        }
        this.profileStorage = normalizedType.equals("s3") ? profileStorage : null;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        return delegateFor(key).put(content, key, contentType, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        if (!isS3Key(key) || profileStorage == null) {
            return localStorage.open(key);
        }

        // 배포 전 로컬에 남아 있는 기존 파일은 이동 없이 계속 읽을 수 있게 한다.
        Optional<Resource> s3Resource = profileStorage.open(key);
        return s3Resource.isPresent() ? s3Resource : localStorage.open(key);
    }

    @Override
    public void delete(String key) {
        if (!isS3Key(key) || profileStorage == null) {
            localStorage.delete(key);
            return;
        }

        try {
            profileStorage.delete(key);
        } finally {
            // 기존 로컬 파일이 있으면 함께 정리한다. S3 신규 파일만 있어도 deleteIfExists 동작이다.
            localStorage.delete(key);
        }
    }

    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        return delegateFor(key).offloadUrl(key, ttl, disposition);
    }

    private StoragePort delegateFor(String key) {
        return isS3Key(key) && profileStorage != null ? profileStorage : localStorage;
    }

    private boolean isS3Key(String key) {
        return StorageKey.isProfile(key) || StorageKey.isChat(key);
    }
}
