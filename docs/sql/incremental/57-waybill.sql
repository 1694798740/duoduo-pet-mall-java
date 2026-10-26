SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- B-05 · 电子面单（2026-10-26）
-- 解决的问题：发货只能手填「物流公司 + 单号」，没有面单生成入口；轨迹只能靠外部手工推送。
-- 本表保存一张订单的电子面单事实：
--   * 一个订单最多一张有效面单（uk_waybill_order）——重复申请必须返回同一张，不能生成两个号；
--   * mode = SIMULATED / EXTERNAL：本地没有快递鸟/菜鸟凭据时走模拟面单，
--     对外接口与轨迹形态保持一致，接真实渠道时只替换 Provider，不改订单与前端；
--   * status 用于模拟轨迹推进的「下一节点」判断（CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → SIGNED）。
CREATE TABLE IF NOT EXISTS t_waybill (
    id           BIGINT      NOT NULL COMMENT '雪花 id',
    order_id     BIGINT      NOT NULL COMMENT '订单事实快照键（OrderRefs 由订单号派生）',
    order_no     VARCHAR(32) NOT NULL COMMENT '交易域订单号',
    seller_id    BIGINT      NOT NULL DEFAULT 0 COMMENT '归属卖家 user id；0=平台自营',
    courier_code VARCHAR(20) NOT NULL COMMENT '快递公司编码（SF/YTO/ZTO/STO/YD/JD/EMS）',
    courier_name VARCHAR(50) NOT NULL COMMENT '快递公司名称（中文展示）',
    waybill_no   VARCHAR(64) NOT NULL COMMENT '运单号',
    mode         VARCHAR(16) NOT NULL DEFAULT 'SIMULATED' COMMENT 'SIMULATED=模拟面单 / EXTERNAL=真实渠道',
    status       VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PICKED_UP/IN_TRANSIT/OUT_FOR_DELIVERY/SIGNED',
    created_at   TIMESTAMP   NOT NULL COMMENT '创建时间（UTC）',
    updated_at   TIMESTAMP   NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waybill_order (order_no),
    UNIQUE KEY uk_waybill_no (waybill_no),
    KEY idx_waybill_status (mode, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '电子面单（B-05）';
