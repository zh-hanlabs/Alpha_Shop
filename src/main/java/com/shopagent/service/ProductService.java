package com.shopagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Product;
import com.shopagent.infra.cache.TwoLevelCache;
import com.shopagent.mapper.ProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Service
public class ProductService {

    // §2.4 定稿参数：L1 Caffeine 500/60s（本地缓存无失效广播，60s 是不一致窗口上限）、L2 RBucket 30min
    static final long DETAIL_L1_MAX_SIZE = 500;
    static final Duration DETAIL_L1_TTL = Duration.ofSeconds(60);
    static final Duration DETAIL_L2_TTL = Duration.ofMinutes(30);
    static final String DETAIL_L2_KEY_PREFIX = "cache:product:detail:";

    private final ProductMapper productMapper;
    private final TwoLevelCache<Long, ProductDetailVO> detailCache;

    public ProductService(ProductMapper productMapper, RedissonClient redissonClient,
                          ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.detailCache = new TwoLevelCache<>(redissonClient, objectMapper, "product-detail",
                DETAIL_L2_KEY_PREFIX, DETAIL_L1_MAX_SIZE, DETAIL_L1_TTL, DETAIL_L2_TTL,
                ProductDetailVO.class);
    }

    public record ProductSummary(
            Long id,
            String name,
            String category,
            BigDecimal price,
            Integer stock,
            String description) {}

    // 三字段联合模糊：口语词常不在商品名里（「充电宝」→ 实名「移动电源」在描述里）
    // 列表查询不缓存（§2.4 缓存边界按数据变更特征划：关键词组合空间大命中率低，且结果含实时库存）
    public List<ProductSummary> searchProducts(String keyword) {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .like(Product::getName, keyword)
                        .or().like(Product::getCategory, keyword)
                        .or().like(Product::getDescription, keyword))
                .stream()
                .map(p -> new ProductSummary(
                        p.getId(), p.getName(), p.getCategory(), p.getPrice(), p.getStock(), p.getDescription()))
                .toList();
    }

    /**
     * 商品详情走两级缓存（展示字段，§2.4 方案A）。查无返回 null 且不缓存负结果——
     * 商品上架后 30min 内不可见的「负缓存」在 demo 语义下是纯坑。
     */
    public ProductDetailVO getDetail(long id) {
        return detailCache.get(id, key -> {
            Product p = productMapper.selectById(key);
            return p == null ? null
                    : new ProductDetailVO(p.getId(), p.getName(), p.getCategory(), p.getPrice(), p.getDescription());
        });
    }

    /** 库存永远实时查库（§2.4 缓存边界铁律），交易正确性字段不走任何缓存。查无返回 -1。 */
    public int getStock(long id) {
        Product p = productMapper.selectById(id);
        return p == null ? -1 : p.getStock();
    }

    /** 热点计数读数透传（dev 指标暴露用），Redis 不可用返回 -1 */
    public long detailHotspotCount(long id) {
        return detailCache.hotspotCount(id);
    }

    public record PriceUpdateOutcome(Long productId, BigDecimal oldPrice, BigDecimal newPrice, boolean found) {}

    /**
     * 改价：Cache Aside 先更库再删缓存（L1+L2 双删，§2.4 面试口径）——
     * 先删缓存再更库的窗口内，并发读会把旧值回填进缓存并驻留到 TTL（脏数据长期化）；
     * 先更库再删缓存最多容忍一个短暂旧值窗口 = 最终一致。
     */
    public PriceUpdateOutcome updatePrice(long id, BigDecimal newPrice) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            return new PriceUpdateOutcome(id, null, newPrice, false);
        }
        BigDecimal oldPrice = p.getPrice();
        p.setPrice(newPrice);
        productMapper.updateById(p);
        detailCache.evict(id);
        return new PriceUpdateOutcome(id, oldPrice, newPrice, true);
    }
}
