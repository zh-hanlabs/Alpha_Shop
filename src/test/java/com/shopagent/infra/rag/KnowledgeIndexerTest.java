package com.shopagent.infra.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.FTCreateParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexerTest {

    private final RedisVectorStore vectorStore = mock(RedisVectorStore.class);
    private final JedisPooled jedis = mock(JedisPooled.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> fingerprintBucket = mock(RBucket.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private KnowledgeIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new KnowledgeIndexer(vectorStore, redisson, embeddingModel);
        doReturn(jedis).when(vectorStore).getJedis();
        doReturn(fingerprintBucket).when(redisson).getBucket(KnowledgeIndexer.FINGERPRINT_KEY);
        when(jedis.ftCreate(anyString(), any(FTCreateParams.class), any(Iterable.class))).thenReturn("OK");
        // 建索引时 schemaFields() 会问维度；mock 模型不打通真 API（T1.4 测试口径）
        when(embeddingModel.dimensions()).thenReturn(1024);
    }

    private List<Document> docs(int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(Document.builder()
                    .id("doc" + i)
                    .text("条目" + i)
                    .metadata("source", "product")
                    .metadata("docType", "faq")
                    .metadata("productId", 1L)
                    .build());
        }
        return docs;
    }

    @Test
    void 指纹相同且索引在位_跳过_零写入() {
        List<Document> docs = docs(40);
        when(fingerprintBucket.get()).thenReturn(KnowledgeCorpusLoader.fingerprint(docs));
        when(jedis.ftList()).thenReturn(Set.of(KnowledgeIndexSpec.INDEX_NAME));

        KnowledgeIndexer.Outcome outcome = indexer.reindexIfChanged(docs);

        assertThat(outcome).isEqualTo(KnowledgeIndexer.Outcome.SKIPPED);
        verify(vectorStore, never()).add(any());
        verify(jedis, never()).ftDropIndexDD(anyString());
        verify(fingerprintBucket, never()).set(anyString());
    }

    @Test
    void 首次构建_无存储指纹_全量重建并存指纹() {
        when(fingerprintBucket.get()).thenReturn(null);
        when(jedis.ftList()).thenReturn(Set.of());

        assertThat(indexer.reindexIfChanged(docs(40))).isEqualTo(KnowledgeIndexer.Outcome.REBUILT);

        verify(jedis).ftDropIndexDD(KnowledgeIndexSpec.INDEX_NAME);
        verify(vectorStore, times(4)).add(any());
        verify(fingerprintBucket).set(KnowledgeCorpusLoader.fingerprint(docs(40)));
    }

    @Test
    void 语料变更_触发重建() {
        when(fingerprintBucket.get()).thenReturn("outdated-fingerprint");
        when(jedis.ftList()).thenReturn(Set.of(KnowledgeIndexSpec.INDEX_NAME));

        assertThat(indexer.reindexIfChanged(docs(40))).isEqualTo(KnowledgeIndexer.Outcome.REBUILT);
        verify(vectorStore, times(4)).add(any());
    }

    @Test
    void 指纹相同但索引缺失_仍重建() {
        List<Document> docs = docs(40);
        when(fingerprintBucket.get()).thenReturn(KnowledgeCorpusLoader.fingerprint(docs));
        when(jedis.ftList()).thenReturn(Set.of());

        assertThat(indexer.reindexIfChanged(docs)).isEqualTo(KnowledgeIndexer.Outcome.REBUILT);
    }

    @Test
    void 分批切片_25条按上限10切为10_10_5() {
        when(fingerprintBucket.get()).thenReturn(null);
        when(jedis.ftList()).thenReturn(Set.of());
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        indexer.reindexIfChanged(docs(25));

        verify(vectorStore, times(3)).add(captor.capture());
        List<Integer> sizes = captor.getAllValues().stream().map(List::size).toList();
        assertThat(sizes).containsExactly(10, 10, 5);
    }

    @Test
    void failOpen_灌入抛异常_不外抛且指纹不落库_下次启动自动重试() {
        when(fingerprintBucket.get()).thenReturn(null);
        when(jedis.ftList()).thenReturn(Set.of());
        doThrow(new RuntimeException("embedding api down")).when(vectorStore).add(any());

        assertThatCode(() -> indexer.onApplicationReady()).doesNotThrowAnyException();
        verify(fingerprintBucket, never()).set(anyString());
    }

    @Test
    void failOpen_Redis故障_不外抛() {
        when(redisson.getBucket(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> indexer.onApplicationReady()).doesNotThrowAnyException();
    }

    @Test
    void dropIndex_未知索引_视作空状态继续() {
        when(fingerprintBucket.get()).thenReturn(null);
        when(jedis.ftList()).thenReturn(Set.of());
        doThrow(new JedisDataException("Unknown index name"))
                .when(jedis).ftDropIndexDD(eq(KnowledgeIndexSpec.INDEX_NAME));

        assertThat(indexer.reindexIfChanged(docs(2))).isEqualTo(KnowledgeIndexer.Outcome.REBUILT);
        verify(vectorStore, times(1)).add(any());
    }
}
