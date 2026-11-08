package com.duoduo.product.service;

import com.duoduo.common.api.PageResult;
import com.duoduo.product.client.TradeClient;
import com.duoduo.product.client.dto.ListingOrderCreated;
import com.duoduo.product.client.dto.ListingSoldOrderView;
import com.duoduo.product.dto.ProductDtos.ListingOrderRequest;
import com.duoduo.product.mapper.ListingMapper;

/**
 * 闲置担保交易服务（B-11 · 阶段 B1 空壳：失败测试先行，B2 填实现）。
 *
 * <h2>职责边界</h2>
 * 商品域只管两件事：
 * <ul>
 *   <li><b>单件锁</b>：闲置是「一物一价、卖完即止」，下单的本质是一次
 *       条件更新（{@code ON_SALE → SOLD}，CAS）—— 并发只有一个人成功，
 *       先查后改的窗口期会被这个原子动作抹掉；</li>
 *   <li><b>入口归属</b>：卖家发货、卖出列表都以登录态为准（USER 即可，
 *       不走 /api/seller/** 的 SELLER 门禁 —— 个人卖家没有那个角色）。</li>
 * </ul>
 * 订单创建、发货状态机、托管/放款/退款的事实全部在交易域：
 * 商品域通过 {@link TradeClient} 委托，不跨库查（硬规矩 4）。
 *
 * <h2>状态机（listing.status）</h2>
 * <pre>
 *          下单 CAS（唯一成交闸）           支付 → 托管 → 发货 → 确认收货 → 放款
 * ON_SALE ────────────────→ SOLD ───────────────────────────────────────→ 终态（SOLD）
 *    ↑                        │
 *    └──── 补偿回滚（建单失败）│
 *    └──── 释放（取消/超时/退款钩子，幂等）
 * </pre>
 */
public class ListingOrderService {

    private final ListingMapper listingMapper;
    private final TradeClient tradeClient;

    public ListingOrderService(ListingMapper listingMapper, TradeClient tradeClient) {
        this.listingMapper = listingMapper;
        this.tradeClient = tradeClient;
    }

    /**
     * 买家下单：校验可见 → CAS 锁定（ON_SALE→SOLD）→ 委托交易域建 C2C 订单。
     * 交易域建单失败必须补偿回滚，否则一次远程抖动就锁死一条真实闲置。
     */
    public ListingOrderCreated placeOrder(Long buyerId, ListingOrderRequest request) {
        throw new UnsupportedOperationException("B2 实现（B1 先红）");
    }

    /** 卖家视角的卖出订单列表（数据事实在交易域 t_order，此处透传） */
    public PageResult<ListingSoldOrderView> listSold(Long sellerId, String status, int page, int pageSize) {
        throw new UnsupportedOperationException("B2 实现（B1 先红）");
    }

    /**
     * 卖家发货：归属与状态校验在交易域（C2C 单 sellerId==操作者、PAID 才可发），
     * 商品域只带登录态转发 —— 校验收在交易域一处，避免两处口径漂移。
     */
    public void ship(Long operatorId, String orderNo, String logisticsCompany, String logisticsNo) {
        throw new UnsupportedOperationException("B2 实现（B1 先红）");
    }

    /**
     * 释放闲置（SOLD→ON_SALE）：取消 / 支付超时 / 未发货退款三类钩子的落点。
     * 幂等：已不是 SOLD 时返回 false、不抛 —— 三个钩子可能对同一单重复触发。
     */
    public boolean releaseToOnSale(Long listingId) {
        throw new UnsupportedOperationException("B2 实现（B1 先红）");
    }
}
