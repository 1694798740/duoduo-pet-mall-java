-- 62 · 重复收款自动退款台账（B-04 · 第一百二十六轮）
--
-- 背景：2026-10-22 支付入账事故之后，重复收款已经能被「检出 + 留痕」（t_payment.status=CLOSED
-- 且 fail_reason 写明重复支付），但退款仍靠人工。本轮把「检出」直接接到「退款」：
-- 检出即在**同一事务**里落一条退款台账（INIT），由补偿调度经渠道退款接口自动执行。
--
-- 状态机：INIT → SUCCESS（唯一成功终态）。
--   * 渠道暂不可用 / 渠道返回处理中 / 渠道不支持退款：一律保持 INIT，fail_reason 记最近一次原因，
--     由补偿调度下一轮重试 —— 与 RESTOCK_PENDING 的补偿语义一致，绝不把「暂时失败」固化成终态。
--   * 渠道级幂等靠 refund_no（微信 out_refund_no / 支付宝 out_request_no）：
--     同一退款单重复提交，渠道侧返回同一笔，不会退两次。
--
-- 幂等：
--   * uk_refund_pay_no —— 一张支付单最多一笔退款。检出路径可能被并发回调/查询兜底触发多次，
--     落库用 INSERT IGNORE 撞唯一索引跳过，不靠「先查再插」。
--   * uk_refund_no —— 退款单号唯一（与订单号同一生成器，21 位带校验位）。

CREATE TABLE t_payment_refund (
    id                BIGINT       NOT NULL COMMENT '雪花 ID',
    refund_no         VARCHAR(32)  NOT NULL COMMENT '退款单号（21 位，时间戳+随机+校验位）',
    pay_no            VARCHAR(32)  NOT NULL COMMENT '被退款的支付单号（重复收款那张）',
    order_id          BIGINT       NOT NULL COMMENT '订单 ID',
    user_id           BIGINT       NOT NULL COMMENT '买家用户 ID（退款对象）',
    channel           VARCHAR(32)  NOT NULL COMMENT '收款渠道（原路退回）',
    channel_trade_no  VARCHAR(64)  NULL COMMENT '渠道流水号（检出时留痕的）',
    amount            BIGINT       NOT NULL COMMENT '退款金额（整数分 = 被退支付单的全部金额）',
    status            VARCHAR(16)  NOT NULL COMMENT 'INIT 待执行 / SUCCESS 已退回',
    fail_reason       VARCHAR(255) NULL COMMENT '最近一次执行失败的原因（重试会覆盖）',
    channel_refund_no VARCHAR(64)  NULL COMMENT '渠道退款流水号（渠道受理后回填）',
    refunded_at       TIMESTAMP    NULL COMMENT '退款到账时间',
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_refund_no ON t_payment_refund (refund_no);
CREATE UNIQUE INDEX uk_refund_pay_no ON t_payment_refund (pay_no);
CREATE INDEX idx_refund_user ON t_payment_refund (user_id, created_at);
-- 补偿调度扫描（status=INIT）与运营侧筛选走这条索引
CREATE INDEX idx_refund_status ON t_payment_refund (status, created_at);
