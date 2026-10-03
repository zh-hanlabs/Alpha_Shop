package com.shopagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 交易审计日志（W4D3）：每次到达闸序的交易尝试（首执/重放/在途拒绝/锁拒绝）各一条。
 * 重放场景两条记录同 idempotent_key——审计的时间线即幂等语义的物证。
 */
@TableName("trade_audit_log")
public class TradeAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String action;
    // placeOrder 执行时订单号尚不存在，允许为空（经 idempotent_key 关联）
    private String orderNo;
    private String idempotentKey;
    private Integer resultCode;
    private String resultMsg;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getIdempotentKey() { return idempotentKey; }
    public void setIdempotentKey(String idempotentKey) { this.idempotentKey = idempotentKey; }
    public Integer getResultCode() { return resultCode; }
    public void setResultCode(Integer resultCode) { this.resultCode = resultCode; }
    public String getResultMsg() { return resultMsg; }
    public void setResultMsg(String resultMsg) { this.resultMsg = resultMsg; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
