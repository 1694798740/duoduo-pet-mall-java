-- 41：t_payment.code_url 加宽为 MEDIUMTEXT（2026-10-22）
--
-- 背景：支付宝「电脑网站支付」按官方规范返回的是**完整 form HTML**
-- （hidden inputs + 提交按钮，约 2~3KB），不再是短的二维码内容串。
-- 原 VARCHAR(500) 装不下，预下单入库时触发 DataIntegrityViolationException。
-- 语义同步放宽：code_url 现在表示「前端可直接使用的支付入口」，对支付宝渠道
-- 即整段表单 HTML（前端注入 DOM 后提交，见 OrderDetail.tsx submitAlipayForm）。
--
-- 可逆性：加宽 VARCHAR→TEXT 不会丢数据；回滚需先确认无超长值。

ALTER TABLE duoduo_trade.t_payment
    MODIFY COLUMN code_url MEDIUMTEXT NULL;
