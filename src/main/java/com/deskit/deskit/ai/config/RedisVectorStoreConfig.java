package com.deskit.deskit.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

@Configuration
@EnableConfigurationProperties(RagVectorProperties.class)
public class RedisVectorStoreConfig {

    @Bean
    public JedisPooled jedisPooled(RagVectorProperties properties) {

        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            return new JedisPooled(
                    new HostAndPort(properties.getHost(), properties.getPort()),
                    DefaultJedisClientConfig.builder()
                            .password(properties.getPassword())
                            .build()
            );
        }

        return new JedisPooled(
                properties.getHost(),
                properties.getPort()
        );
    }

    @Bean
    public RedisVectorStore redisVectorStore(
            JedisPooled jedisPooled,
            EmbeddingModel embeddingModel,
            RagVectorProperties properties
    ) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .initializeSchema(true)
                .build();
    }

}
