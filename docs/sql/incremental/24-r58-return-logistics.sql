-- 第五十八轮 · P1-5：退货物流（寄回 / 签收）
--
-- 背景：退货退款（RETURN_REFUND）此前只「记录诉求」——卖家同意的瞬间钱就退了，
--       货到底寄没寄回、收到没有，系统里没有任何记录。卖家被要求「确认收到退货后再退款」，
--       但系统既没有寄回入口也没有签收入口，这条要求只能靠线下沟通完成。
--
-- 本次补的是**两个确定性节点**：买家填寄回运单（已寄回）→ 卖家确认收货（已签收，此时才退款）。
-- 刻意**不建退货轨迹表**：正向轨迹（t_logistics_track）的「签收」在语义上是订单交付的终态，
--   把退货节点混进去会让「已签收」这个词在一条订单里出现两种含义，是对账与前端展示的长期隐患；
--   退货流程真正需要的只有「寄回 / 收到」两个事实，落到售后单上即可。

ALTER TABLE t_after_sale
    ADD COLUMN return_courier      VARCHAR(64)  NULL COMMENT '买家寄回的快递公司（WAITING_RETURN 之后才有值）',
    ADD COLUMN return_tracking_no  VARCHAR(64)  NULL COMMENT '买家寄回的运单号',
    ADD COLUMN return_shipped_at   TIMESTAMP    NULL COMMENT '买家填写寄回信息的时间（= 已寄回节点）',
    ADD COLUMN return_received_at  TIMESTAMP    NULL COMMENT '卖家确认收货的时间（= 已签收节点，退款在此刻发生）';

-- 卖家端「待收货」列表按状态过滤，沿用既有 seller 索引即可（status 已是索引前导列之一），
-- 不新增索引：这两态的数据量远小于在途订单，且列表本身是分页的。

-- 状态取值扩充（VARCHAR 无需改类型，这里只做记录，真正的取值约束在 AfterSaleStatus）：
--   WAITING_RETURN = 卖家已同意退货，等待买家寄回
--   RETURNING      = 买家已寄回，等待卖家签收
--   终态仍为 REFUNDED / CLOSED，不新增终态。
