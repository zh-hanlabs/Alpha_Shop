package com.shopagent.config;

import com.shopagent.infra.rag.KnowledgeIndexSpec;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/**
 * 知识向量库装配（W5D1）。手工构造 RedisVectorStore 而非自动装配，Why：
 * ①自动装配要求 JedisConnectionFactory bean，该位置已被 Redisson 的 redissonConnectionFactory
 *   抢占（Boot 的 Lettuce/Jedis 工厂 @ConditionalOnMissingBean 让位），自动装配静默跳过（D0 实证）；
 * ②FLAT 算法与 metadata 字段（source/productId/docType）只有 builder 能配（§2.2 索引参数）。
 * initializeSchema=false：启动期不碰 embedding API（dummy Key 也能起，fail-open），
 * 索引创建由 KnowledgeIndexer 指纹比对后按需触发；schema 定义统一走 KnowledgeIndexSpec 防双写漂移。
 */
@Configuration
public class RedisVectorStoreConfig {

    @Bean
    public JedisPooled knowledgeJedis(RedisProperties redisProperties) {
        // 与 Redisson 同源读取 spring.data.redis.*；Jedis 专司 RediSearch FT.*，与 Redisson 各自连池互不影响
        return new JedisPooled(redisProperties.getHost(), redisProperties.getPort());
    }

    @Bean
    public RedisVectorStore redisVectorStore(JedisPooled knowledgeJedis, EmbeddingModel embeddingModel) {
        return KnowledgeIndexSpec.newStore(knowledgeJedis, embeddingModel, false);
    }
}
