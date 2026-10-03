package com.shopagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shopagent.entity.Product;
import com.shopagent.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public record ProductSummary(
            Long id,
            String name,
            String category,
            BigDecimal price,
            Integer stock,
            String description) {}

    // 三字段联合模糊：口语词常不在商品名里（「充电宝」→ 实名「移动电源」在描述里）
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
}
