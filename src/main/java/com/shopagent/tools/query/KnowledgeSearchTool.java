package com.shopagent.tools.query;

import com.shopagent.service.KnowledgeService;
import com.shopagent.tools.support.ToolEvents;
import com.shopagent.tools.support.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具（W5D2）。与 searchProduct 的路由分工（§2.3）：
 * searchProduct=结构化找商品（有没有/多少钱/有货吗），searchKnowledge=使用/参数细节/平台政策（怎么用/行不行）。
 * 知识库是公共域：无 userId 校验需求，与订单工具的归属铁律形成对比（面试点）。
 * 异常在 KnowledgeService 内消化转 error 结果，这里不再包 try/catch（tools 只做参数校验与编排）。
 */
@Component
public class KnowledgeSearchTool {

    // 防模型传整段对话历史进知识检索，拖垮向量查询
    private static final int MAX_QUERY_LENGTH = 100;

    private final KnowledgeService knowledgeService;

    public KnowledgeSearchTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(description = "查询商品知识库与平台政策。当用户问商品使用或参数细节（怎么充电、防不防水、能不能游泳、续航多久），" +
            "或平台政策（退货、换货、保修、运费、支付方式、发票、充电宝登机限制）时调用。" +
            "参数 query 为用户的完整问题。" +
            "返回 notFound 表示知识库没有覆盖，请如实告知并引导换问法，绝不编造答案。" +
            "与 searchProduct 分工：找商品（有没有/多少钱）用 searchProduct，讲知识（怎么用/行不行）用本工具；" +
            "复杂问题可先查知识再搜商品配合回答（如「防水又便宜的灯」）。")
    public ToolResult searchKnowledge(@ToolParam(description = "用户的完整问题，例如：露营灯防水吗") String query,
                                      ToolContext toolContext) {
        if (query == null || query.isBlank()) {
            return ToolResult.badParam("问题不能为空");
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            return ToolResult.badParam("问题过长，请简短描述想了解的内容");
        }
        ToolEvents.publish(toolContext, "正在查询知识库「" + trimmed + "」");
        return knowledgeService.search(trimmed);
    }
}
