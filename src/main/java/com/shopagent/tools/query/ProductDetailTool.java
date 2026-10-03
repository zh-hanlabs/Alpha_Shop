package com.shopagent.tools.query;

import com.shopagent.service.ProductDetailVO;
import com.shopagent.service.ProductService;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 商品详情工具（W5D3）：展示字段走两级缓存（ProductDetailVO），库存实时查库同响应返回——
 * 一个 payload 同时演示缓存边界两面：price 来自缓存（60s 窗口），stock 永远新鲜。
 * productId 是 long 参数天然白名单（对比订单号 String 需正则校验）；id 只能来自 searchProduct 结果。
 */
@Component
public class ProductDetailTool {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailTool.class);

    private final ProductService productService;

    public ProductDetailTool(ProductService productService) {
        this.productService = productService;
    }

    @Tool(description = "查询指定商品的详情（名称/类目/价格/描述）与实时库存。" +
            "当用户问某个具体商品的细节（如「这个充电宝多少钱」「保温杯什么材质」）且已从 searchProduct 结果得知商品 id 时调用；" +
            "没给过商品 id 时先调 searchProduct 搜索。商品 id 只能来自搜索结果，不得编造。")
    public ToolResult productDetail(@ToolParam(description = "商品 id，来自 searchProduct 的结果") long productId,
                                    ToolContext toolContext) {
        ToolEvents.publish(toolContext, "正在查询商品详情");
        try {
            ProductDetailVO detail = productService.getDetail(productId);
            if (detail == null) {
                return ToolResult.notFound("没有查到这个商品，请用 searchProduct 搜索确认后再试");
            }
            return ToolResult.ok(Map.of(
                    "product", detail,
                    "stock", productService.getStock(productId)));
        } catch (Exception e) {
            log.error("productDetail failed, productId={}", productId, e);
            return ToolResult.error("商品详情查询失败，请稍后再试");
        }
    }
}
