package com.shopagent.tools.query;

import com.shopagent.service.ProductService;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductSearchTool {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchTool.class);

    // 关键词长度上限：防模型传超长串拖垮 LIKE 查询
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final ProductService productService;

    public ProductSearchTool(ProductService productService) {
        this.productService = productService;
    }

    @Tool(description = "搜索商品。当用户问「有没有 XX」「XX 多少钱」「XX 有货吗」「推荐一下 XX」时调用。" +
            "参数 keyword 为商品关键词（如：充电宝、背包、耳机），会匹配商品名、类目和描述。" +
            "返回的商品列表可能为空，为空时请礼貌告知没找到并询问是否换个说法。")
    public ToolResult searchProduct(@ToolParam(description = "商品关键词，例如：充电宝") String keyword,
                                    ToolContext toolContext) {
        if (keyword == null || keyword.isBlank()) {
            return ToolResult.badParam("搜索关键词不能为空");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            return ToolResult.badParam("关键词过长，请提供简短的商品名");
        }
        ToolEvents.publish(toolContext, "正在搜索商品「" + trimmed + "」");
        try {
            List<ProductService.ProductSummary> products = productService.searchProducts(trimmed);
            return ToolResult.ok(products);
        } catch (Exception e) {
            log.error("searchProduct failed, keyword={}", trimmed, e);
            return ToolResult.error("商品搜索失败，请稍后再试");
        }
    }
}
