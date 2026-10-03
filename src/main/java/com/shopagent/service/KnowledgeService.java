package com.shopagent.service;

import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识检索（W5D2，§2.3 设计定稿）：召回 top-5 → 相似度阈值截断 → 轻量规则重排 top-3 → 结构化注入。
 * 不引 reranker 模型：几十条规模多一次 API 调用不值，且「重排要不要上模型」本身就是按规模分层的面试叙事。
 * 降级语义（分级降级矩阵）：故障返回 error code「知识库暂不可用」——交易 fail-closed（W3 已定），
 * 知识 fail-open：答错一句是体验问题，聊天主链路必须照常。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    // §2.3 定稿参数：阈值起点 0.5，D2 冒烟实测定稿后回填任务清单
    static final int RECALL_TOP_K = 5;
    static final double SIMILARITY_THRESHOLD = 0.5;
    static final int RERANK_TOP_N = 3;
    // 规则重排权重：整句命中标题强加权；中文无分词，字符 bigram 命中比例做弱加权
    static final double TITLE_EXACT_BOOST = 0.30;
    static final double TITLE_BIGRAM_WEIGHT = 0.10;

    private final RedisVectorStore vectorStore;

    public KnowledgeService(RedisVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public ToolResult search(String query) {
        try {
            List<Document> candidates = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(RECALL_TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .build());
            if (candidates.isEmpty()) {
                // 零召回是正常态不是故障：模型诚实告知并引导，不注入垃圾上下文
                return ToolResult.notFound("知识库没有覆盖这个问题。请如实告知用户，并引导换个问法或联系人工客服。");
            }
            List<Document> top = rerank(query, candidates);
            log.info("知识检索：query={}，候选 {} 条 → 注入 top{}（分数 {}）",
                    query, candidates.size(), top.size(),
                    top.stream().map(doc -> doc.getScore()).toList());
            return ToolResult.ok(toData(top));
        } catch (Exception e) {
            log.error("knowledge search failed, query={}", query, e);
            return ToolResult.error("知识库暂不可用，请稍后再试");
        }
    }

    /**
     * 规则重排：向量分打底，标题（文档首行问句）命中加权。纯函数便于单测。
     * 向量召回排序模型语义相似，标题命中补上「字面相关」这一票——两者互补。
     */
    static List<Document> rerank(String query, List<Document> candidates) {
        String trimmed = query.trim();
        Set<String> bigrams = bigrams(trimmed);
        record Scored(Document doc, double score) {
        }
        return candidates.stream()
                .map(doc -> {
                    double score = doc.getScore() == null ? 0.0 : doc.getScore();
                    String title = titleOf(doc);
                    if (title.contains(trimmed)) {
                        score += TITLE_EXACT_BOOST;
                    }
                    if (!bigrams.isEmpty()) {
                        long hits = bigrams.stream().filter(title::contains).count();
                        score += TITLE_BIGRAM_WEIGHT * ((double) hits / bigrams.size());
                    }
                    return new Scored(doc, score);
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(RERANK_TOP_N)
                .map(Scored::doc)
                .toList();
    }

    /** 中文无分词，相邻字符 bigram 集合是最轻量的确定性包含匹配。 */
    private static Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        if (text.length() < 2) {
            return set;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            set.add(text.substring(i, i + 2));
        }
        return set;
    }

    private static String titleOf(Document doc) {
        String text = doc.getText();
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static List<Map<String, Object>> toData(List<Document> docs) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", titleOf(doc));
            item.put("content", doc.getText());
            item.put("source", doc.getMetadata().get("source"));
            item.put("docType", doc.getMetadata().get("docType"));
            Object productId = doc.getMetadata().get("productId");
            if (productId != null) {
                item.put("productId", productId);
            }
            item.put("score", doc.getScore());
            data.add(item);
        }
        return data;
    }
}
