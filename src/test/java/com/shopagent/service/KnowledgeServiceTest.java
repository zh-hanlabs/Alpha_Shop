package com.shopagent.service;

import com.shopagent.tools.support.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    private final RedisVectorStore vectorStore = mock(RedisVectorStore.class);
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(vectorStore);
    }

    private Document doc(String id, String title, double score, String source, Long productId) {
        Document.Builder builder = Document.builder()
                .id(id)
                .text(title + "\n这是「" + title + "」的答案正文。")
                .metadata("source", source)
                .metadata("docType", "faq")
                .score(score);
        if (productId != null) {
            builder.metadata("productId", productId);
        }
        return builder.build();
    }

    // —— rerank 规则重排（纯函数） ——

    @Test
    void rerank_标题整句命中_强加权居首() {
        // 向量分更低但标题整句命中的文档，应反超（字面相关补票）
        Document vectorHigh = doc("d1", "保温杯能装碳酸饮料吗", 0.80, "product", 5L);
        Document exactHit = doc("d2", "露营灯防水吗", 0.62, "product", 7L);

        List<Document> ranked = KnowledgeService.rerank("露营灯防水吗", List.of(vectorHigh, exactHit));

        assertThat(ranked.get(0).getId()).isEqualTo("d2");
    }

    @Test
    void rerank_bigram部分命中_按比例弱加权() {
        // 「露营灯能挂帐篷吗」的 bigram 与「露营灯防水吗」标题部分命中（露营/营灯），
        // 命中比例介于 0 与 1 之间，加权后应高于无任何命中的同分文档
        Document partial = doc("d1", "露营灯防水吗", 0.60, "product", 7L);
        Document none = doc("d2", "运费怎么算", 0.60, "policy", null);

        List<Document> ranked = KnowledgeService.rerank("露营灯能挂帐篷吗", List.of(none, partial));

        assertThat(ranked.get(0).getId()).isEqualTo("d1");
    }

    @Test
    void rerank_超过topN只取前三() {
        List<Document> candidates = List.of(
                doc("d1", "问题一", 0.9, "policy", null),
                doc("d2", "问题二", 0.8, "policy", null),
                doc("d3", "问题三", 0.7, "policy", null),
                doc("d4", "问题四", 0.6, "policy", null),
                doc("d5", "问题五", 0.5, "policy", null));

        List<Document> ranked = KnowledgeService.rerank("完全不相关的查询词", candidates);

        assertThat(ranked).hasSize(3);
        assertThat(ranked).extracting(Document::getId).containsExactly("d1", "d2", "d3");
    }

    @Test
    void rerank_单字查询无bigram无命中_退化为纯向量分() {
        Document d1 = doc("d1", "防水等级说明", 0.7, "product", 7L);
        Document d2 = doc("d2", "保修政策", 0.8, "policy", null);

        // 「星」不在任何标题里：既无整句命中也无 bigram，纯按向量分排
        List<Document> ranked = KnowledgeService.rerank("星", List.of(d1, d2));

        assertThat(ranked.get(0).getId()).isEqualTo("d2");
    }

    // —— search 链路（mock VectorStore） ——

    @Test
    void search_命中_结构化data含metadata与score() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("d1", "露营灯防水吗", 0.86, "product", 7L)));

        ToolResult result = service.search("露营灯防水吗");

        assertThat(result.code()).isEqualTo(ToolResult.CODE_SUCCESS);
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("title", "露营灯防水吗")
                .containsEntry("source", "product")
                .containsEntry("productId", 7L)
                .containsEntry("score", 0.86);
    }

    @Test
    void search_零召回_notFound引导如实告知() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ToolResult result = service.search("今天天气怎么样");

        assertThat(result.code()).isEqualTo(ToolResult.CODE_NOT_FOUND);
        assertThat(result.msg()).contains("知识库没有覆盖");
    }

    @Test
    void search_向量库故障_failOpen返回error不外抛() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("redis down"));

        ToolResult result = service.search("露营灯防水吗");

        assertThat(result.code()).isEqualTo(ToolResult.CODE_ERROR);
        assertThat(result.msg()).contains("知识库暂不可用");
    }
}
