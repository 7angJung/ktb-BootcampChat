package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SocketIOConfigUnitTest {

    @Test
    void createRedissonConfig_usesRedisConnectionProperties() {
        SocketIOConfig socketIOConfig = configuredSocketIOConfig();

        var redissonConfig = socketIOConfig.createRedissonConfig();
        var singleServer = redissonConfig.useSingleServer();

        assertThat(singleServer.getAddress()).isEqualTo("redis://redis.internal:6380");
        assertThat(redissonConfig.getPassword()).isEqualTo("secret");
        assertThat(singleServer.getDatabase()).isEqualTo(2);
    }

    @Test
    void createRedissonConfig_doesNotSetBlankPassword() {
        SocketIOConfig socketIOConfig = configuredSocketIOConfig();
        ReflectionTestUtils.setField(socketIOConfig, "redisPassword", " ");

        assertThat(socketIOConfig.createRedissonConfig()
                .getPassword()).isNull();
    }

    @Test
    void socketIOServer_usesProvidedDistributedStoreFactory() {
        SocketIOConfig socketIOConfig = configuredSocketIOConfig();
        RedissonStoreFactory storeFactory = org.mockito.Mockito.mock(RedissonStoreFactory.class);

        var configuration = socketIOConfig.createSocketIOConfiguration(storeFactory);

        assertThat(configuration.getStoreFactory()).isSameAs(storeFactory);
    }

    @Test
    void memoryStoreFactory_isAvailableOnlyForExplicitSingleNodeMode() {
        assertThat(new SocketIOConfig().memoryStoreFactory())
                .isInstanceOf(MemoryStoreFactory.class);
    }

    private SocketIOConfig configuredSocketIOConfig() {
        SocketIOConfig config = new SocketIOConfig();
        ReflectionTestUtils.setField(config, "host", "0.0.0.0");
        ReflectionTestUtils.setField(config, "port", 5002);
        ReflectionTestUtils.setField(config, "origin", "*");
        ReflectionTestUtils.setField(config, "redisHost", "redis.internal");
        ReflectionTestUtils.setField(config, "redisPort", 6380);
        ReflectionTestUtils.setField(config, "redisPassword", "secret");
        ReflectionTestUtils.setField(config, "redisDatabase", 2);
        return config;
    }
}
