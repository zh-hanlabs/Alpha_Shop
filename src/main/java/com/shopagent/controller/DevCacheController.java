package com.shopagent.controller;

import com.shopagent.service.ProductDetailVO;
import com.shopagent.service.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 缓存一致性演示端点（W5D3，T3.4）：绕过 LLM 直打 service 层，保证冒烟证据确定性。
 * 链路：detail 读（L2 miss→库→回填 / L1 hit）→ update-product 改价（先更库再双删）→ detail 再读（回源新值）。
 * Why dev-only：改价端点可绕过交易闸序直改商品，@Profile("dev") 保证演示/生产不注册（同 DevChaosController 惯例）。
 */
@RestController
@RequestMapping("/api/dev/cache")
@Profile("dev")
public class DevCacheController {

    private final ProductService productService;

    public DevCacheController(ProductService productService) {
        this.productService = productService;
    }

    public record PriceUpdateRequest(long productId, java.math.BigDecimal newPrice) {}

    /** 直读详情（缓存读路径）：展示字段走两级缓存，库存实时 */
    @GetMapping("/product-detail")
    public Map<String, Object> detail(@RequestParam long productId) {
        ProductDetailVO detail = productService.getDetail(productId);
        Map<String, Object> result = new HashMap<>();
        result.put("product", detail);
        result.put("stock", productService.getStock(productId));
        result.put("hotspotCount", productService.detailHotspotCount(productId));
        return result;
    }

    /** 改价（Cache Aside 先更库再双删），返回新旧价格 */
    @PostMapping("/update-product")
    public ProductService.PriceUpdateOutcome updatePrice(@RequestBody PriceUpdateRequest req) {
        return productService.updatePrice(req.productId(), req.newPrice());
    }
}
