package com.shopagent.infra.audit;

import com.shopagent.entity.TradeAuditLog;
import com.shopagent.infra.guard.TradeRequest;
import com.shopagent.mapper.TradeAuditLogMapper;
import com.shopagent.tools.support.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 交易审计写入（W4D3）：每次到达闸序的尝试（首执/重放/在途拒绝/锁拒绝）各留一条。
 * 与 ToolEventListener 旁路埋点同构——不在业务事务内：主交易已提交，审计写失败
 * 不回滚不阻断（warn 兜底人工补录），审计是物证不是闸门。
 */
@Component
public class TradeAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(TradeAuditLogger.class);

    private static final int MSG_MAX = 255;

    private final TradeAuditLogMapper auditLogMapper;

    public TradeAuditLogger(TradeAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void log(TradeRequest request, ToolResult result) {
        try {
            TradeAuditLog entry = new TradeAuditLog();
            entry.setUserId(request.userId());
            entry.setAction(request.action());
            entry.setOrderNo(request.orderNo());
            entry.setIdempotentKey(request.idempotentKey());
            entry.setResultCode(result.code());
            entry.setResultMsg(abbreviate(result.msg()));
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("trade audit write failed, action={}, key={}", request.action(), request.idempotentKey(), e);
        }
    }

    private String abbreviate(String msg) {
        if (msg == null || msg.length() <= MSG_MAX) {
            return msg;
        }
        return msg.substring(0, MSG_MAX);
    }
}
