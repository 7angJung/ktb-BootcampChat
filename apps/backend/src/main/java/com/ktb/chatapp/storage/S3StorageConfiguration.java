// 프로필 이미지 S3 저장에 사용할 AWS SDK 클라이언트를 구성한다.
package com.ktb.chatapp.storage;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3StorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${aws.region:ap-northeast-2}") String region) {
        String normalizedRegion = Objects.requireNonNull(region, "AWS_REGION은 필수입니다.").trim();
        if (normalizedRegion.isEmpty()) {
            throw new IllegalStateException("AWS_REGION은 비어 있을 수 없습니다.");
        }

        return S3Client.builder()
                .region(Region.of(normalizedRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
