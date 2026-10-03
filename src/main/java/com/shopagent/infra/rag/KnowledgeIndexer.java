package com.shopagent.infra.rag;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库索引构建器：ApplicationReadyEvent 触发，语料指纹比对幂等启动（§2.2 定稿）。
 *  - 指纹不变且索引在位 → 跳过（重启零成本）
 *  - 指纹变 / 索引缺失 / 首次构建 → FT.DROPINDEX DD 连带删文档 → 重建索引 → 分批灌入（≤10 条/批，DashScope 上限）
 *  - 失败不存指纹 → 下次启动自动重试；全程 fail-open：嵌入失败（Key 缺失/网络）不阻断启动，
 *    知识检索降级而聊天主链路照常——分级降级矩阵（§2.3）：交易 fail-closed，知识 fail-open
 */
@Component
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    static final String FINGERPRINT_KEY = "knowledge:index:fingerprint";
    // DashScope text-embedding 单次批量上限 10 条（§2.2 设计定稿），Indexer 层分批与 API 层无关地满足它
    static final int EMBED_BATCH_SIZE = 10;

    private final RedisVectorStore vectorStore;
    private final RedissonClient redisson;
    private final EmbeddingModel embeddingModel;

    public KnowledgeIndexer(RedisVectorStore vectorStore, RedissonClient redisson,
                            EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.redisson = redisson;
        this.embeddingModel = embeddingModel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            reindexIfChanged(KnowledgeCorpusLoader.load());
        } catch (Exception e) {
            // ApplicationReady 阶段抛异常会导致应用退出，知识库必须 fail-open
            log.error("知识库索引构建失败，知识检索将降级（聊天主链路不受影响）", e);
        }
    }

    enum Outcome { SKIPPED, REBUILT }

    Outcome reindexIfChanged(List<Document> docs) {
        String fingerprint = KnowledgeCorpusLoader.fingerprint(docs);
        RBucket<String> bucket = redisson.getBucket(FINGERPRINT_KEY);
        String stored = bucket.get();
        JedisPooled jedis = vectorStore.getJedis();
        boolean indexExists = jedis.ftList().contains(KnowledgeIndexSpec.INDEX_NAME);
        if (fingerprint.equals(stored) && indexExists) {
            log.info("知识语料指纹未变化且索引在位，跳过重建（{} 条）", docs.size());
            return Outcome.SKIPPED;
        }
        long start = System.currentTimeMillis();
        log.info("知识库索引开始重建：语料 {} 条（{}）", docs.size(),
                stored == null ? "首次构建" : "语料变更或索引缺失");
        dropIndex(jedis);
        // 临时 init=true 实例负责建索引：afterPropertiesSet 内部先查 ftList 天然幂等；
        // 建索引触发 dimensions() 真 API 调用，异常被 onApplicationReady 的 fail-open 兜住
        KnowledgeIndexSpec.newStore(jedis, embeddingModel, true).afterPropertiesSet();
        for (List<Document> batch : partition(docs, EMBED_BATCH_SIZE)) {
            vectorStore.add(batch);
        }
        bucket.set(fingerprint);
        log.info("知识库索引重建完成：{} 条，耗时 {} ms", docs.size(), System.currentTimeMillis() - start);
        return Outcome.REBUILT;
    }

    private void dropIndex(JedisPooled jedis) {
        try {
            jedis.ftDropIndexDD(KnowledgeIndexSpec.INDEX_NAME);
            log.info("旧索引已连带文档删除（FT.DROPINDEX DD）");
        } catch (JedisException e) {
            // 首次构建无索引可删，RediSearch 报 Unknown index——正是期望的空状态
            log.info("无旧索引可删（首次构建）");
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return parts;
    }
}
