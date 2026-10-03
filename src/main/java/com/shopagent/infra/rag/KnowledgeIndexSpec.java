package com.shopagent.infra.rag;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import redis.clients.jedis.JedisPooled;

/**
 * 知识向量索引的唯一事实来源：索引名/前缀/metadata 字段/算法集中在此。
 * 主 bean（启动装配，initializeSchema=false）与 Indexer 重建时的临时建索引实例共用同一构造，
 * 杜绝 schema 双写漂移（两边字段不一致 = 检索静默失效，比启动失败更难查）。
 *
 * Why 手工构造不走自动装配（W5D1 定稿备注，替代 §2.2「自动装配」原案）：
 * ①自动装配要求 JedisConnectionFactory bean——已被 Redisson 的 redissonConnectionFactory 抢位，
 *   @ConditionalOnBean 不满足即静默跳过（W5D0 条件报告实证）；
 * ②FLAT 算法与 metadata 字段只有 builder 能配，自动装配无对应属性。
 */
public final class KnowledgeIndexSpec {

    public static final String INDEX_NAME = "shopagent-knowledge";
    public static final String PREFIX = "knowledge:";

    private KnowledgeIndexSpec() {
    }

    public static RedisVectorStore newStore(JedisPooled jedis, EmbeddingModel embeddingModel,
                                            boolean initializeSchema) {
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(INDEX_NAME)
                .prefix(PREFIX)
                // FLAT 精确 KNN：几十条规模下近似索引（HNSW）无召回收益，反而多一层解释成本（§2.2）
                .vectorAlgorithm(RedisVectorStore.Algorithm.FLAT)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("source"),
                        RedisVectorStore.MetadataField.numeric("productId"),
                        RedisVectorStore.MetadataField.tag("docType"))
                // false：afterPropertiesSet 建索引会调 embeddingModel.dimensions()（真 API 调用），
                // dummy Key 场景启动必须能过（fail-open）——索引创建由 KnowledgeIndexer 按需触发
                .initializeSchema(initializeSchema)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
}
