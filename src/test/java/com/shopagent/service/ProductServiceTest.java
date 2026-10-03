package com.shopagent.service;

import com.shopagent.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    // Spy 而非 Mock：真实 H2 照常读写，同时可数 DB 调用次数验证缓存行为
    @MockitoSpyBean
    private ProductMapper productMapper;

    // @MockitoSpyBean 使本类上下文配置与其它 @SpringBootTest 分叉（缓存未命中 → 加载第二个上下文）；
    // DB_CLOSE_DELAY=-1 的同名 H2 库会被 data.sql 重复灌数据 → 主键冲突。
    // 独立命名内存库让本上下文自建自灌，互不干扰
    @DynamicPropertySource
    static void isolatedH2(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:shopdb-products-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

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

    // —— W5D3 缓存边界（§2.4 方案A）——

    @Test
    void getDetail_展示字段齐全_无库存字段() {
        // ProductDetailVO record 本身没有 stock 访问器（编译期保证库存不进缓存）
        ProductDetailVO detail = productService.getDetail(1L);

        assertThat(detail.name()).contains("移动电源");
        assertThat(detail.price()).isEqualByComparingTo("129.00");
        assertThat(detail.category()).isEqualTo("充电配件");
        assertThat(detail.description()).contains("快充");
    }

    @Test
    void getDetail_同id二次调用_L1命中仅一次DB查询() {
        // 测试环境的 mock Redisson 未打桩 = L2 不可用 → 走 loader 并回填 L1；
        // 二次调用命中 L1，mapper 只打一次
        productService.getDetail(7L);
        productService.getDetail(7L);

        verify(productMapper, times(1)).selectById(7L);
    }

    @Test
    void searchProducts_列表不缓存_两次都走库() {
        productService.searchProducts("露营灯");
        productService.searchProducts("露营灯");

        verify(productMapper, times(2)).selectList(any());
    }

    @Test
    void getStock_实时查库不走缓存() {
        assertThat(productService.getStock(7L)).isEqualTo(60);
        assertThat(productService.getStock(999L)).isEqualTo(-1);
    }

    @Test
    void updatePrice_先更库再双删_再读回源新值() {
        ProductService.PriceUpdateOutcome outcome = productService.updatePrice(7L, new BigDecimal("66.00"));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.oldPrice()).isEqualByComparingTo("59.00");

        // 双删后回源读到新价（若缓存未删，60s 窗口内仍是旧值）
        assertThat(productService.getDetail(7L).price()).isEqualByComparingTo("66.00");

        // 还原演示数据，不污染同上下文的其他用例
        productService.updatePrice(7L, new BigDecimal("59.00"));
    }

    @Test
    void updatePrice_查无商品_found为false() {
        assertThat(productService.updatePrice(999L, new BigDecimal("1.00")).found()).isFalse();
    }
}
