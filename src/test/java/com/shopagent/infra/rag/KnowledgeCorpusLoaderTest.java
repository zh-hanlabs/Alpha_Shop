package com.shopagent.infra.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCorpusLoaderTest {

    private static final String PRODUCT_MD = """
            # 便携式露营灯
            productId: 7

            ## FAQ：露营灯防水吗？
            IPX5 级防水：小雨可淋、泼溅无忧，不可浸泡。

            ## FAQ：露营灯怎么充电？
            USB-C 接口充电，满电高亮档约续航 6 小时。

            ## 规格：露营灯的亮度档位
            三档调光：高亮、中亮、夜灯模式。
            """;

    private static final String POLICY_MD = """
            # 售后政策

            ## FAQ：退货政策是怎样的？
            签收后 7 天内可申请无理由退货。
            """;

    @Test
    void parse_商品文件_条目数_metadata与内容正确() {
        List<Document> docs = KnowledgeCorpusLoader.parse("product-7-camping-light.md", PRODUCT_MD);

        assertThat(docs).hasSize(3);
        Document faq = docs.get(0);
        assertThat(faq.getMetadata())
                .containsEntry("source", "product")
                .containsEntry("docType", "faq")
                .containsEntry("productId", 7L);
        assertThat(faq.getText()).startsWith("露营灯防水吗？").contains("IPX5");
        // 「规格：」前缀决定 docType，前缀本身不留在正文里
        assertThat(docs.get(2).getMetadata()).containsEntry("docType", "spec");
        assertThat(docs.get(2).getText()).startsWith("露营灯的亮度档位");
    }

    @Test
    void parse_政策文件_无productId_source为policy() {
        List<Document> docs = KnowledgeCorpusLoader.parse("policy-after-sales.md", POLICY_MD);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getMetadata())
                .containsEntry("source", "policy")
                .containsEntry("docType", "faq")
                .doesNotContainKey("productId");
    }

    @Test
    void parse_无标题前缀的条目_缺省faq且前缀不剥离() {
        List<Document> docs = KnowledgeCorpusLoader.parse("policy-x.md", "## 保修期多久？\n电子类 12 个月。");

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getMetadata()).containsEntry("docType", "faq");
        assertThat(docs.get(0).getText()).startsWith("保修期多久？");
    }

    @Test
    void docId_确定性_同输入同id_不同条目不同id() {
        List<Document> first = KnowledgeCorpusLoader.parse("product-7-camping-light.md", PRODUCT_MD);
        List<Document> second = KnowledgeCorpusLoader.parse("product-7-camping-light.md", PRODUCT_MD);

        assertThat(first).extracting(Document::getId).isEqualTo(second.stream().map(Document::getId).toList());
        assertThat(first).extracting(Document::getId).doesNotHaveDuplicates();
    }

    @Test
    void fingerprint_同语料相同_任一条目变更即不同() {
        List<Document> docs = KnowledgeCorpusLoader.parse("product-7-camping-light.md", PRODUCT_MD);
        String baseline = KnowledgeCorpusLoader.fingerprint(docs);

        assertThat(KnowledgeCorpusLoader.fingerprint(
                KnowledgeCorpusLoader.parse("product-7-camping-light.md", PRODUCT_MD))).isEqualTo(baseline);

        List<Document> edited = new ArrayList<>(KnowledgeCorpusLoader.parse(
                "product-7-camping-light.md", PRODUCT_MD.replace("不可浸泡", "可短时浸泡")));
        assertThat(KnowledgeCorpusLoader.fingerprint(edited)).isNotEqualTo(baseline);
    }

    @Test
    void parse_空内容_返回空列表() {
        assertThat(KnowledgeCorpusLoader.parse("empty.md", "# 只有标题\n\nproductId: 1\n")).isEmpty();
    }
}
