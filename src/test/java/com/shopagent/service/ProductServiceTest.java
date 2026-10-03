package com.shopagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @Test
    void search_by_colloquial_word_matches_description() {
        // 「充电宝」不在商品名里，在描述里——口语词兜底
        List<ProductService.ProductSummary> hits = productService.searchProducts("充电宝");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).contains("移动电源");
        assertThat(hits.get(0).price()).isEqualByComparingTo("129.00");
        assertThat(hits.get(0).stock()).isEqualTo(45);
    }

    @Test
    void search_by_name_fragment() {
        List<ProductService.ProductSummary> hits = productService.searchProducts("背包");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).contains("徒步背包");
    }

    @Test
    void search_by_category_word() {
        List<ProductService.ProductSummary> hits = productService.searchProducts("户外");

        assertThat(hits.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void search_no_match_returns_empty() {
        assertThat(productService.searchProducts("火箭发动机")).isEmpty();
    }
}
