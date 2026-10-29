package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.api.StatusCount;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.FulfilmentNotifier;
import com.duoduo.fulfilment.client.OrderClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.client.dto.RefundRequest;
import com.duoduo.fulfilment.client.dto.RefundResult;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleArbitrateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleCreateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReplyRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReturnShipRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
import com.duoduo.fulfilment.mapper.AdminQueueReadMapper;
import com.duoduo.fulfilment.mapper.AfterSaleMapper;
import com.duoduo.fulfilment.support.AfterSaleStatus;
import com.duoduo.fulfilment.support.ImageUrls;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.OrderRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 售后仲裁服务（A3）。
 *
 * <p><b>要解决的问题</b>：在此之前退款只有「卖家一键退」一种形态。买家想退款只能先私下沟通，
 * 卖家不同意就无路可走；平台手里没有任何可裁定的凭据（诉求是什么、什么时候提的、卖家怎么答的）。
 * 本服务把「争议」变成一条可查、可留痕、可裁定的记录。
 *
 * <p><b>钱的边界（刻意）</b>：本服务**不碰钱**。裁定通过时调用交易域既有的运营侧退款入口
 * （{@code POST /api/orders/internal/{orderNo}/refund}），退款金额由那边回填。
 * 理由：退款的状态机、托管 CAS、退货入库补偿在交易域只有一份实现，
 * 在本域再写一条「售后专用退款」等于给自己造第二个事实来源。
 *
 * <p><b>顺序（关键）</b>：先让钱动，再改售后单状态。反过来会得到
 * 「售后单显示已退款、钱其实没退」这种最难查的账目分歧。
 * 若钱动失败（最常见的：已完成订单的钱已放款给卖家），售后单**保持原状态**并如实报错 ——
 * 那是另一条资金流程（向卖家追偿 / 平台垫付），属于项目已知边界 B-01，不在本服务里越权处理。
 *
 * <p><b>并发</b>：状态迁移一律走 {@code AfterSaleMapper} 的 CAS 条件更新。
 * 运营点「裁定退款」的同时卖家点「同意」，数据库只会让一个生效；
 * 钱那一侧的托管 CAS 会挡住第二次扣款。这里先做一次状态预检查是为了给出**准确的错误码**
 * （状态不允许 = 422），CAS 影响 0 行则说明是并发抢先 = 409，两者语义不同。
 */
@Service
public class AfterSaleService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleService.class);

    /**
     * 可以立案的订单状态。
     *
     * <p>「已发货」与「已完成」都收：货在路上的争议（迟迟不发货之后的拒收、货损）
     * 与确认收货后的争议（货不对板），都是真实的售后诉求，不适合同一个门槛挡住。
     * 而「待付款 / 已付款未发货」不收 —— 那种情况买家本来就能自助取消或自助退款，
     * 走售后反而绕远；「已退款 / 已关闭」更不该重复立案。
     */
    private static final Set<String> ALLOWED_ORDER_STATUS = Set.of("SHIPPED", "COMPLETED");

    /**
     * 可裁定的售后单状态（终态不可再改判，要改判只能新立案）。
     *
     * <p>两个退货中间态（{@code WAITING_RETURN / RETURNING}）必须包含进来：
     * 货寄出后卖家一直不确认收货时，买家同样需要求助平台的路 ——
     * 否则「退货中」就成了流程的黑洞。集合与 {@code AfterSaleMapper} 里
     * 仲裁相关 SQL 的 {@code status IN (...)} 是同一套口径，改一处就要改另一处。
     */
    private static final Set<String> ARBITRABLE = Set.of(
            AfterSaleStatus.PENDING, AfterSaleStatus.SELLER_REJECTED, AfterSaleStatus.ARBITRATING,
            AfterSaleStatus.WAITING_RETURN, AfterSaleStatus.RETURNING);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final AfterSaleMapper afterSaleMapper;
    /** 运营后台「看过即消」的已读集合（2026-10-27） */
    private final AdminQueueReadMapper adminQueueReadMapper;
    /** 只用于执行退款；取订单事实已收敛到 {@link OrderFacts}（见其类注释） */
    private final OrderClient orderClient;
    private final OrderFacts orderFacts;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    /** 售后进度通知（第五十五轮）：best-effort，见 {@link FulfilmentNotifier} */
    private final FulfilmentNotifier fulfilmentNotifier;
    private final int maxImages;

    public AfterSaleService(AfterSaleMapper afterSaleMapper,
                            AdminQueueReadMapper adminQueueReadMapper,
                            OrderClient orderClient,
                            OrderFacts orderFacts,
                            SnowflakeIdGenerator idGenerator,
                            ObjectMapper objectMapper,
                            FulfilmentNotifier fulfilmentNotifier,
                            @Value("${duoduo.aftersale.max-images:6}") int maxImages) {
        this.afterSaleMapper = afterSaleMapper;
        this.adminQueueReadMapper = adminQueueReadMapper;
        this.orderClient = orderClient;
        this.orderFacts = orderFacts;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.fulfilmentNotifier = fulfilmentNotifier;
        this.maxImages = maxImages;
    }

    // ---------------- 买家：立案 ----------------

    /**
     * 买家立案。
     *
     * <p>归属与状态全部以**订单事实**为准（内部接口取回），请求体里没有 buyerId / status 可供伪造；
     * 幂等靠唯一索引 {@code uk_aftersale_order_type}（同单同类型只能立一次），撞键转 409 而不是 500 ——
     * 重复立案是**预期内的业务结果**，不是服务故障。
     */
    public AfterSaleView create(Long userId, AfterSaleCreateRequest request) {
        requireLogin(userId);
        if (!AfterSaleStatus.isKnownType(request.type())) {
            throw BizException.validation("售后类型只能是 REFUND_ONLY 或 RETURN_REFUND");
        }
        OrderFact fact = orderFacts.require(request.orderNo());
        requireBuyer(fact, userId);
        requireOrderStatusAllowsAfterSale(fact);

        List<String> images = ImageUrls.validate(request.images(), maxImages);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        AfterSaleEntity entity = new AfterSaleEntity();
        // id-type: input，主键必须显式赋雪花值，否则 Column 'id' cannot be null（INV-13）
        entity.setId(idGenerator.nextId());
        entity.setOrderId(OrderRefs.orderIdOf(fact.orderNo()));
        entity.setOrderNo(fact.orderNo());
        entity.setBuyerId(userId);
        entity.setSellerId(fact.sellerId() == null ? 0L : fact.sellerId());
        entity.setType(request.type());
        entity.setReason(request.reason().trim());
        entity.setImages(ImageUrls.toJson(objectMapper, images));
        entity.setStatus(AfterSaleStatus.PENDING);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            afterSaleMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("该订单的此类售后已立案，请勿重复提交");
        }
        log.info("用户 {} 对订单 {} 提起售后（类型 {}），售后单 {}", userId, fact.orderNo(), request.type(), entity.getId());

        // 售后进度通知（第五十五轮）：立案成功后给买家一条回执。
        //  为什么「自己做的动作也通知自己」：用户需要一条可回看的确认，
        //  否则关掉页面后「到底提交成功了没有」只能靠记忆；这也是主流电商的通行做法。
        fulfilmentNotifier.notifyAfterSaleCreated(entity.getBuyerId(), entity.getOrderNo(), entity.getId());

        return toView(entity);
    }

    // ---------------- 卖家：处理 ----------------

    /**
     * 卖家处理：同意（执行退款）或拒绝（必填说明）。
     *
     * <p>只在「待卖家处理」时可操作。同意即执行退款 —— 不设「卖家已同意」的中间态：
     * 多一个状态就多一次前端要处理的组合，而它并没有带来任何额外信息。
     */
    public AfterSaleView reply(Long userId, Long id, AfterSaleReplyRequest request) {
        requireLogin(userId);
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        if (!AfterSaleStatus.ACTION_AGREE.equals(action) && !AfterSaleStatus.ACTION_REJECT.equals(action)) {
            throw BizException.validation("处理动作只能是 AGREE 或 REJECT");
        }
        AfterSaleEntity entity = loadAfterSale(id);
        requireCanProcess(entity, userId);
        requireStatus(entity, Set.of(AfterSaleStatus.PENDING),
                "只有「待卖家处理」的售后单可以处理");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (AfterSaleStatus.ACTION_AGREE.equals(action)) {
            // 退货退款：同意的是「退货」，钱要等货回到卖家手上才动（第五十八轮 P1-5）。
            //   这里刻意不复用下面 REFUND_ONLY 的分支 —— 两条 UPDATE 是两种不同的结论，
            //   合成一条就等于把「钱先退、货没回」写进流程里。
            if (AfterSaleStatus.TYPE_RETURN_REFUND.equals(entity.getType())) {
                int rows = afterSaleMapper.markWaitingReturn(entity.getId(), "AGREE", now);
                if (rows == 0) {
                    throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
                }
                log.info("售后单 {} 卖家 {} 同意退货，等待买家寄回（退款在签收后执行）",
                        entity.getId(), userId);
                // 通知买家：此刻买家必须知道「接下来该我寄回了」，否则会一直等退款
                fulfilmentNotifier.notifyReturnAgreed(entity.getBuyerId(), entity.getOrderNo(), entity.getId());
                return toView(loadAfterSale(id));
            }
            // 仅退款：钱现在就动
            RefundResult refund = callTradeRefund(entity.getOrderNo(),
                    "卖家同意售后退款（售后单 " + entity.getId() + "）");
            int rows = afterSaleMapper.markRefundedBySeller(entity.getId(), "AGREE",
                    refund.refundAmount(), now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 卖家 {} 同意退款，退款金额 {} 分", entity.getId(), userId, refund.refundAmount());
            // 通知买家（第五十五轮）：CAS 已成功（rows>0），这条结论是确定的
            fulfilmentNotifier.notifyAfterSaleRefunded(entity.getBuyerId(), entity.getOrderNo(), entity.getId(),
                    refund.refundAmount());
        } else {
            String reply = trimToNull(request.reply());
            if (reply == null) {
                // 「拒绝必须说明原因」是流程要求，不是字段格式错 —— 用 422（与 07 篇验收标准一致的判法）
                throw BizException.businessRule("拒绝售后必须填写说明原因");
            }
            int rows = afterSaleMapper.markSellerRejected(entity.getId(), reply, now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 被卖家 {} 拒绝：{}", entity.getId(), userId, reply);
            // 通知买家（第五十五轮）：带上拒绝理由，否则用户只看到「被拒绝」而不知原因
            fulfilmentNotifier.notifyAfterSaleRejected(entity.getBuyerId(), entity.getOrderNo(), entity.getId(), reply);
        }
        return toView(loadAfterSale(id));
    }

    /**
     * 卖家批量处理售后（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条走与单条完全相同状态机与归属校验：只有「待卖家处理」的成功，
     * 其余（已处理 / 非本店 / 订单已放款退不了）逐条失败并带回原因。
     * 成功一条就写一条卖家已读 —— 刚处理完的单自己当然看过，
     * 不该再挂着已退款 / 已拒绝的角标。
     */
    public BatchDtos.Result replyBatch(Long userId, List<Long> ids, AfterSaleReplyRequest request) {
        requireLogin(userId);
        List<Long> distinct = normalizeBatchIds(ids);
        List<BatchDtos.Item> items = new ArrayList<>(distinct.size());
        int success = 0;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (Long id : distinct) {
            try {
                reply(userId, id, request);
                success++;
                afterSaleMapper.markAfterSaleRead(userId, id, now);
                items.add(new BatchDtos.Item(String.valueOf(id), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("批量处理售后单条失败 id={}", id, ex);
                items.add(new BatchDtos.Item(String.valueOf(id), false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return new BatchDtos.Result(distinct.size(), success, distinct.size() - success, items,
                statusCounts(userId, "seller"));
    }

    /**
     * 运营批量裁定售后（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条走 {@link #arbitrate}：支持退款会真的调用交易域退款，
     * 某一条因「已完成订单已放款」失败时只影响该条，其余继续。
     * 成功条目写运营已读集合，返回最新未读分桶。
     */
    public BatchDtos.Result arbitrateBatch(List<Long> ids, AfterSaleArbitrateRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        List<Long> distinct = normalizeBatchIds(ids);
        List<BatchDtos.Item> items = new ArrayList<>(distinct.size());
        int success = 0;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (Long id : distinct) {
            try {
                arbitrate(id, request);
                success++;
                adminQueueReadMapper.markRead(operator.userId(), "AFTER_SALE", id, now);
                items.add(new BatchDtos.Item(String.valueOf(id), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(id), false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("批量裁定售后单条失败 id={}", id, ex);
                items.add(new BatchDtos.Item(String.valueOf(id), false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return new BatchDtos.Result(distinct.size(), success, distinct.size() - success, items,
                adminUnreadCount());
    }

    /** 批量 id 去重、拒绝空值、限制单次规模（与商品批量上下架同一上限） */
    private List<Long> normalizeBatchIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.validation("ids 不能为空");
        }
        java.util.LinkedHashSet<Long> distinct = new java.util.LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                throw BizException.validation("ids 不能包含空值");
            }
            distinct.add(id);
        }
        if (distinct.size() > 100) {
            throw BizException.validation("单次最多批量操作 100 笔售后单");
        }
        return new ArrayList<>(distinct);
    }

    // ---------------- 退货物流：买家寄回 / 卖家签收（第五十八轮 P1-5） ----------------

    /**
     * 买家填写寄回物流 → 已寄回，等待卖家签收。
     *
     * <p><b>为什么重复提交转 409 而不是幂等返回成功</b>：运单号已经写入，
     * 第二次提交意味着用户看到了「没成功」的假象而重试，此时应当明确告诉他
     * 「已经登记过了」，而不是静默返回成功 —— 静默成功会让人以为这次填的覆盖生效了，
     * 实际上库里还是第一次那个单号（这是「看起来成功、实际没生效」的典型形态）。
     */
    public AfterSaleView shipReturn(Long userId, Long id, AfterSaleReturnShipRequest request) {
        requireLogin(userId);
        String courier = trimToNull(request.courierCompany());
        String trackingNo = trimToNull(request.trackingNo());
        if (courier == null || trackingNo == null) {
            throw BizException.validation("请填写快递公司与运单号");
        }
        AfterSaleEntity entity = loadAfterSale(id);
        if (!userId.equals(entity.getBuyerId())) {
            throw BizException.forbidden("只能对自己的售后单填写寄回物流");
        }
        requireStatus(entity, Set.of(AfterSaleStatus.WAITING_RETURN),
                "只有「待买家寄回」的售后单可以填写寄回物流");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = afterSaleMapper.markReturning(entity.getId(), courier, trackingNo, now);
        if (rows == 0) {
            throw BizException.conflict("该售后单刚刚已登记过寄回信息，请刷新后查看");
        }
        log.info("售后单 {} 买家 {} 已寄回：{} {}", entity.getId(), userId, courier, trackingNo);
        // 通知卖家：货在路上，卖家才知道要去收货（否则这条单子会一直停在待收货）
        fulfilmentNotifier.notifyReturnShipped(entity.getSellerId(), entity.getOrderNo(), entity.getId(), trackingNo);
        return toView(loadAfterSale(id));
    }

    /**
     * 卖家确认收货 → 签收并退款（退货退款的钱在这一刻才动）。
     *
     * <p><b>先让钱动、再记结论</b>：与仅退款分支同一次序。
     * 反过来（先记 REFUNDED 再调退款）会在退款失败时留下「已退款」的结论而没有钱，
     * 那是比「钱退了、状态还没改」更难收拾的错账 —— 后者最多是状态落后，前者是账实不符。
     *
     * <p><b>签收后不再允许仲裁驳回</b>：签收即终态 REFUNDED，
     * 与既有设计一致（退款是终态），不留「退了又撤回」的边。
     */
    public AfterSaleView receiveReturn(Long userId, Long id) {
        requireLogin(userId);
        AfterSaleEntity entity = loadAfterSale(id);
        requireCanProcess(entity, userId);
        requireStatus(entity, Set.of(AfterSaleStatus.RETURNING),
                "只有「买家已寄回」的售后单可以确认收货");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RefundResult refund = callTradeRefund(entity.getOrderNo(),
                "卖家确认收货后退款（售后单 " + entity.getId() + "）");
        int rows = afterSaleMapper.markReceivedRefunded(entity.getId(), refund.refundAmount(), now);
        if (rows == 0) {
            // 钱已经退了但结论没写进去：必须响亮失败（这是账实不符，需要人工介入），
            // 绝不能当成「已处理过」静默返回 —— 与垫付那条的教训同源。
            log.error("售后单 {} 退款已执行（{} 分）但状态写入失败，请人工核对",
                    entity.getId(), refund.refundAmount());
            throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看（如已退款请联系平台核对）");
        }
        log.info("售后单 {} 卖家 {} 确认收货并退款，退款金额 {} 分",
                entity.getId(), userId, refund.refundAmount());
        fulfilmentNotifier.notifyAfterSaleRefunded(entity.getBuyerId(), entity.getOrderNo(), entity.getId(),
                refund.refundAmount());
        return toView(loadAfterSale(id));
    }

    // ---------------- 买家：申请平台仲裁 ----------------

    /**
     * 买家申请平台仲裁。
     *
     * <p>「待卖家处理」与「卖家已拒绝」两态都可进入：卖家一直不处理时买家需要一条求助平台的路，
     * 否则售后就变成了「卖家单方面决定要不要理你」。
     */
    public AfterSaleView escalate(Long userId, Long id) {
        requireLogin(userId);
        AfterSaleEntity entity = loadAfterSale(id);
        if (!userId.equals(entity.getBuyerId())) {
            throw BizException.forbidden("只能对自己的售后单申请仲裁");
        }
        // 退货的两个中间态同样可进仲裁：货寄出去之后卖家迟迟不确认收货时，
        // 「退货中」不能变成没有出路的黑洞（与 ARBITRABLE 同一套口径）
        requireStatus(entity, Set.of(AfterSaleStatus.PENDING, AfterSaleStatus.SELLER_REJECTED,
                        AfterSaleStatus.WAITING_RETURN, AfterSaleStatus.RETURNING),
                "该售后单当前状态不允许申请仲裁");
        int rows = afterSaleMapper.markArbitrating(entity.getId(), LocalDateTime.now(ZoneOffset.UTC));
        if (rows == 0) {
            throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
        }
        log.info("售后单 {} 由买家 {} 申请平台仲裁", entity.getId(), userId);
        // 回执（第五十五轮）：让买家知道「申请已经进入平台流程」，而不是停在「点了没反应」
        fulfilmentNotifier.notifyAfterSaleEscalated(entity.getBuyerId(), entity.getOrderNo(), entity.getId());
        return toView(loadAfterSale(id));
    }

    // ---------------- 运营：裁定 ----------------

    /**
     * 运营裁定：支持退款 / 驳回，**必须填裁定说明**。
     *
     * <p>说明必填不是形式主义：售后单是「为什么这么判」的唯一凭据，
     * 没有说明的裁定在事后复盘与客诉处理时等于零信息。缺说明按流程要求判 422。
     *
     * <p>支持退款时若交易域拒绝（最常见：已完成订单已放款、钱在卖家手里），
     * 售后单**保持原状态**并把对端的原话透传给运营 —— 让运营看到「需走追偿」，
     * 而不是看到一句笼统的「操作失败」。
     */
    public AfterSaleView arbitrate(Long id, AfterSaleArbitrateRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        String result = request.result() == null ? "" : request.result().trim().toUpperCase();
        if (!AfterSaleStatus.RESULT_APPROVE_REFUND.equals(result)
                && !AfterSaleStatus.RESULT_REJECT.equals(result)) {
            throw BizException.validation("裁定结论只能是 APPROVE_REFUND 或 REJECT");
        }
        String note = trimToNull(request.note());
        if (note == null) {
            throw BizException.businessRule("裁定必须填写说明");
        }
        AfterSaleEntity entity = loadAfterSale(id);
        requireStatus(entity, ARBITRABLE, "该售后单已终结，不能重复裁定");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (AfterSaleStatus.RESULT_APPROVE_REFUND.equals(result)) {
            RefundResult refund = callTradeRefund(entity.getOrderNo(),
                    "平台仲裁支持退款（售后单 " + entity.getId() + "）");
            int rows = afterSaleMapper.markRefundedByArbitration(entity.getId(), result, note,
                    operator.userId(), refund.refundAmount(), now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 由运营 {} 裁定支持退款，退款金额 {} 分：{}",
                    entity.getId(), operator.userId(), refund.refundAmount(), note);
            // 裁定结论通知买家（第五十五轮）：终态事件，用户最需要知道结果
            fulfilmentNotifier.notifyAfterSaleArbitratedRefund(entity.getBuyerId(), entity.getOrderNo(), entity.getId(),
                    refund.refundAmount(), note);
        } else {
            int rows = afterSaleMapper.markClosedByArbitration(entity.getId(), result, note,
                    operator.userId(), now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 由运营 {} 裁定驳回：{}", entity.getId(), operator.userId(), note);
            fulfilmentNotifier.notifyAfterSaleArbitratedRejected(entity.getBuyerId(), entity.getOrderNo(), entity.getId(), note);
        }
        return toView(loadAfterSale(id));
    }

    // ---------------- 读 ----------------

    /**
     * 我提起的售后（买家视角）。
     *
     * @param keyword 售后单号 / 订单号（包含匹配）。售后单本身没有「标题」，
     *                用户能记住的两个标识就是这两串数字。
     */
    public PageResult<AfterSaleView> listMine(Long userId, String status, String keyword, int page, int pageSize) {
        requireLogin(userId);
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getBuyerId, userId);
        applyStatusFilter(wrapper, status);
        applyKeyword(wrapper, keyword);
        // 未读优先：与订单/商品列表同一取向，未读不会散落到后面的页
        wrapper.last("ORDER BY (EXISTS (SELECT 1 FROM t_after_sale_read r WHERE r.user_id = "
                + userId + " AND r.after_sale_id = t_after_sale.id)) ASC, created_at DESC, id DESC");
        return page(wrapper, page, pageSize, userId, false);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<AfterSaleView> listMine(Long userId, String status, int page, int pageSize) {
        return listMine(userId, status, null, page, pageSize);
    }

    /**
     * 我店铺的售后（卖家视角）。
     *
     * <p>可见范围完全由登录态推导，**不接受任何 sellerId 参数** ——
     * 接受即等于开放「换个 ID 看别人店铺的售后」。
     * 运营额外可见平台自营（{@code sellerId = 0}）：当前商品域没有商家字段，
     * 自营单没有真的卖家账号可登录，只能由运营代处理（与卖家中心同一口径）。
     */
    public PageResult<AfterSaleView> listForSeller(Long userId, String status, String keyword, int page, int pageSize) {
        LoginUser user = requireLogin(userId);
        if (!user.canAccessSellerConsole()) {
            throw BizException.forbidden("仅卖家或运营可查看店铺售后");
        }
        List<Long> scope = new ArrayList<>();
        scope.add(userId);
        if (user.isOperator()) {
            scope.add(0L);
        }
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<AfterSaleEntity>()
                .in(AfterSaleEntity::getSellerId, scope);
        applyStatusFilter(wrapper, status);
        applyKeyword(wrapper, keyword);
        wrapper.last("ORDER BY (EXISTS (SELECT 1 FROM t_after_sale_read r WHERE r.user_id = "
                + userId + " AND r.after_sale_id = t_after_sale.id)) ASC, created_at DESC, id DESC");
        return page(wrapper, page, pageSize, userId, false);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<AfterSaleView> listForSeller(Long userId, String status, int page, int pageSize) {
        return listForSeller(userId, status, null, page, pageSize);
    }

    /** 全平台售后（运营） */
    public PageResult<AfterSaleView> listAll(String status, String keyword, int page, int pageSize) {
        LoginUser operator = OperatorGuard.requireOperator();
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        applyKeyword(wrapper, keyword);
        // 运营队列的未读集合与买家/卖家那份分开（source=AFTER_SALE）
        wrapper.last("ORDER BY (EXISTS (SELECT 1 FROM t_admin_queue_read r WHERE r.user_id = "
                + operator.userId() + " AND r.source = 'AFTER_SALE' AND r.biz_id = t_after_sale.id)) "
                + "ASC, created_at DESC, id DESC");
        return page(wrapper, page, pageSize, operator.userId(), true);
    }

    /**
     * 售后按状态分桶（买家端 / 卖家端售后页的二级 Tab 角标，2026-10-27）。
     *
     * <p><b>口径与列表接口严格同源</b>，只是把分页换成 GROUP BY：
     * <ul>
     *   <li>{@code side=buyer} → 与 {@link #listMine} 同一范围（{@code buyer_id = 我}）；</li>
     *   <li>{@code side=seller} → 与 {@link #listForSeller} 同一范围（本人；运营额外含自营 0）。</li>
     * </ul>
     *
     * <p>为什么不在前端按当前页 filter 计数：售后单是分页的，第 2 页的「待卖家处理」
     * 在角标上会整个消失 —— 用户看到 Tab 没数字、点进去却有单，只会认为角标坏了。
     *
     * @param side 缺省 buyer；取值只认 buyer / seller，其余按 buyer 处理
     *             （不抛 400：这是给角标用的只读端点，偶发拼错不应让整个页面白屏）
     */
    public java.util.List<StatusCount> statusCounts(Long userId, String side) {
        LoginUser user = requireLogin(userId);
        if ("seller".equalsIgnoreCase(side)) {
            if (!user.canAccessSellerConsole()) {
                throw BizException.forbidden("仅卖家或运营可查看店铺售后");
            }
            List<Long> scope = new ArrayList<>();
            scope.add(userId);
            if (user.isOperator()) {
                scope.add(0L);
            }
            return toStatusCounts(afterSaleMapper.countBySellerAndStatus(scope, userId));
        }
        return toStatusCounts(afterSaleMapper.countByBuyerAndStatus(userId, userId));
    }

    /**
     * 「看过某售后单」上报（2026-10-27 · 阅后即消角标）。
     *
     * <p>只有终态（{@code REFUNDED / CLOSED}，卖家侧另加 {@code SELLER_REJECTED}）
     * 的角标会因此消失；进行中的售后读过也继续提醒。
     *
     * <p>归属校验复用 {@link #detail}：他人 / 不存在一律 404（与全站同一纪律，
     * 不区分「不存在」与「不是你的」，避免被用来枚举他人售后单）。
     *
     * <p>幂等；返回最新分桶供前端覆盖本地乐观值。
     */
    public java.util.List<StatusCount> markRead(Long userId, Long id, String side) {
        detail(userId, id);
        afterSaleMapper.markAfterSaleRead(userId, id, LocalDateTime.now(ZoneOffset.UTC));
        return statusCounts(userId, side);
    }

    // ---------------- 运营后台角标：未读 · 点击详情即消除（2026-10-27） ----------------

    /** 运营后台「售后仲裁」的未读待处理数（排除本运营已点开看过的单） */
    public java.util.List<StatusCount> adminUnreadCount() {
        LoginUser operator = OperatorGuard.requireOperator();
        return unreadBucket(afterSaleMapper.countUnreadPendingForAdmin(operator.userId()));
    }

    /**
     * 运营点开售后详情 = 已读（幂等）；返回最新未读数。
     *
     * <p>与买家/卖家的 {@link #markRead} 分开：那份是「终态才可消」的业务角标，
     * 这份是运营待办队列的「看过就消」，两者的已读集合与可消状态名单都不同，
     * 共用一个方法迟早把两边规则搅在一起。
     */
    public java.util.List<StatusCount> markReadForAdmin(Long id) {
        LoginUser operator = OperatorGuard.requireOperator();
        if (id == null) {
            throw BizException.validation("售后单 id 不能为空");
        }
        if (afterSaleMapper.selectById(id) == null) {
            throw BizException.notFound("售后单不存在");
        }
        adminQueueReadMapper.markRead(operator.userId(), "AFTER_SALE", id,
                LocalDateTime.now(ZoneOffset.UTC));
        return unreadBucket(afterSaleMapper.countUnreadPendingForAdmin(operator.userId()));
    }

    private static java.util.List<StatusCount> unreadBucket(long count) {
        return count <= 0 ? java.util.List.of() : java.util.List.of(new StatusCount("PENDING", count));
    }

    /**
     * 聚合行 → 状态计数列表（返回 record 而非 Map，理由见 {@link StatusCount} 类注释；
     * 列名大小写容错的理由同 {@code OrderService#toStatusCounts}）。
     */
    private static java.util.List<StatusCount> toStatusCounts(List<java.util.Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<StatusCount> result = new ArrayList<>(rows.size());
        for (java.util.Map<String, Object> row : rows) {
            Object status = row.get("status") != null ? row.get("status") : row.get("STATUS");
            Object cnt = row.get("cnt") != null ? row.get("cnt") : row.get("CNT");
            if (status == null || !(cnt instanceof Number n) || n.longValue() <= 0) {
                continue;
            }
            result.add(new StatusCount(String.valueOf(status), n.longValue()));
        }
        return result;
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<AfterSaleView> listAll(String status, int page, int pageSize) {
        return listAll(status, null, page, pageSize);
    }

    /**
     * 卖家售后导出（2026-10-28 · 导入导出第一批）。
     *
     * <p>与 {@link #listForSeller} 同一可见范围与同一筛选（{@link #applyStatusFilter} /
     * {@link #applyKeyword}）；导出不按未读优先，按申请时间倒序，最多 5 万行。
     * {@code read} 恒为 false —— 导出文件是给对账/复盘看的，橙点没有意义。
     */
    public List<AfterSaleView> exportForSeller(Long userId, String status, String keyword) {
        LoginUser user = requireLogin(userId);
        if (!user.canAccessSellerConsole()) {
            throw BizException.forbidden("仅卖家或运营可查看店铺售后");
        }
        List<Long> scope = new ArrayList<>();
        scope.add(userId);
        if (user.isOperator()) {
            scope.add(PLATFORM_SELLER_ID);
        }
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<AfterSaleEntity>()
                .in(AfterSaleEntity::getSellerId, scope);
        applyStatusFilter(wrapper, status);
        applyKeyword(wrapper, keyword);
        wrapper.orderByDesc(AfterSaleEntity::getCreatedAt).orderByDesc(AfterSaleEntity::getId);
        wrapper.last("LIMIT 50000");
        return afterSaleMapper.selectList(wrapper).stream()
                .map(entity -> toView(entity, false))
                .toList();
    }

    /** 全平台售后导出（运营）：与 {@link #listAll} 同筛选，字段与卖家侧一致 */
    public List<AfterSaleView> exportAll(String status, String keyword) {
        OperatorGuard.requireOperator();
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        applyKeyword(wrapper, keyword);
        wrapper.orderByDesc(AfterSaleEntity::getCreatedAt).orderByDesc(AfterSaleEntity::getId);
        wrapper.last("LIMIT 50000");
        return afterSaleMapper.selectList(wrapper).stream()
                .map(entity -> toView(entity, false))
                .toList();
    }

    /**
     * 关键字：售后单号 / 订单号。
     *
     * <p>用 {@code like}（包含）而不是 {@code likeRight}：用户往往只记得单号中间一段，
     * 前缀匹配会让人以为「查无此单」。两张表都只有几万行级别，索引前缀失效的代价可接受；
     * 真要放大到百万级时，这里应改为「精确匹配优先 + 后四位兜底」，而不是把 like 去掉。</p>
     */
    private void applyKeyword(LambdaQueryWrapper<AfterSaleEntity> wrapper, String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        String kw = keyword.trim();
        wrapper.and(w -> w.like(AfterSaleEntity::getOrderNo, kw)
                .or()
                .apply("CAST(id AS CHAR) LIKE {0}", "%" + kw + "%"));
    }

    /** 售后单详情：当事人（买家 / 该单卖家）或运营 */
    public AfterSaleView detail(Long userId, Long id) {
        LoginUser user = requireLogin(userId);
        AfterSaleEntity entity = loadAfterSale(id);
        boolean party = userId.equals(entity.getBuyerId()) || userId.equals(entity.getSellerId());
        if (!party && !user.isOperator()) {
            throw BizException.forbidden("只能查看与自己相关的售后单");
        }
        return toView(entity);
    }

    // ---------------- 内部：校验与装配 ----------------

    /**
     * 平台自营（伪卖家）标识。
     *
     * <p>与 trade 域 {@code OrderService.PLATFORM_SELLER_ID} 同为 0：**平台自营没有真实的卖家账号**，
     * 商品/交易/售后三域各自引用这个约定值。管理后台拆分（任务 T7）落地后，自营会变成真正的
     * "平台自营店铺"租户实体，届时这里收敛为租户判定，不再需要"0 的特例"。
     */
    private static final long PLATFORM_SELLER_ID = 0L;

    /**
     * 处理权限：本店本人；**或运营/管理员代处理平台自营**（seller_id = 0）。
     *
     * <p>为什么运营必须能代处理自营：自营单没有卖家账号可登录，不给运营这个口子，
     * 自营售后就**没有任何人**能处理。列表接口（{@link #listForSeller}）早已按
     * "运营额外可见 seller_id = 0"实现（其注释写明"自营单只能由运营代处理"），
     * 处理接口此前漏了同一口径 —— 这正是"列表看得到、点开却 403"的成因（缺陷 S21）。
     *
     * <p>为什么不给运营更大范围：**运营不得触碰第三方商家的售后**。第三方店铺的代操作
     * 按设计只能走"关联工单 + 白名单动作 + 全量审计"的独立通道（任务 T21），
     * 在本接口放开等于直接拆掉越权防线。
     */
    private void requireCanProcess(AfterSaleEntity entity, Long userId) {
        if (userId.equals(entity.getSellerId())) {
            return;
        }
        LoginUser user = UserContext.get();
        if (user != null && user.isOperator()
                && entity.getSellerId() != null && entity.getSellerId() == PLATFORM_SELLER_ID) {
            return;
        }
        // 越权与不存在刻意区分：售后单 id 是雪花值不可枚举，明说「不是你的店」更有助于排查
        throw BizException.forbidden("只能处理自己店铺的售后单（平台运营仅可代处理自营售后）");
    }

    private LoginUser requireLogin(Long userId) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        LoginUser user = UserContext.get();
        if (user == null) {
            throw BizException.unauthorized("请先登录");
        }
        return user;
    }

    /** 立案人必须是订单归属人（订单事实里的 userId，不是请求体传的） */
    private void requireBuyer(OrderFact fact, Long userId) {
        if (!userId.equals(fact.userId())) {
            throw BizException.forbidden("只能对自己的订单申请售后");
        }
    }

    private void requireOrderStatusAllowsAfterSale(OrderFact fact) {
        if (!ALLOWED_ORDER_STATUS.contains(fact.status())) {
            throw BizException.businessRule(
                    "订单当前状态为「" + fact.statusLabel() + "」，发货后才能申请售后");
        }
    }

    private AfterSaleEntity loadAfterSale(Long id) {
        if (id == null) {
            throw BizException.validation("售后单 id 不能为空");
        }
        AfterSaleEntity entity = afterSaleMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("售后单");
        }
        return entity;
    }

    /** 状态预检查：给出准确的 422（区别于并发抢先的 409） */
    private void requireStatus(AfterSaleEntity entity, Set<String> allowed, String message) {
        if (!allowed.contains(entity.getStatus())) {
            throw BizException.businessRule(
                    message + "（当前：「" + AfterSaleStatus.labelOf(entity.getStatus()) + "」）");
        }
    }

    /**
     * 调交易域执行退款。
     *
     * <p>三类失败必须区分对待，**不允许「对端不可用就放行」**：
     * 订单不存在 404 原样传；业务规则不满足（已完成且已放款）把对端原话透传成 422；
     * 其余（5xx / 超时）判为依赖不可用 503，退款未执行。
     */
    private RefundResult callTradeRefund(String orderNo, String reason) {
        try {
            var response = orderClient.refund(orderNo, new RefundRequest(reason));
            RefundResult result = response == null ? null : response.data();
            if (result == null) {
                throw new BizException(ErrorCode.DEGRADED, "退款未生效，请稍后重试");
            }
            return result;
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw BizException.notFound("订单");
            }
            if (e.status() == 422) {
                String remote = tradeMessage(e);
                throw BizException.businessRule(remote != null ? remote
                        : "交易域拒绝了本次退款，请核对订单与资金状态");
            }
            log.error("调用交易域退款失败 orderNo={} status={}", orderNo, e.status(), e);
            throw new BizException(ErrorCode.DEGRADED, "交易服务暂时不可用，退款未执行，请稍后重试");
        }
    }

    /** 从交易域的错误响应里取出可读文案（拿不到就返回 null，由调用方给兜底话术） */
    private String tradeMessage(FeignException e) {
        try {
            JsonNode node = objectMapper.readTree(e.contentUTF8());
            JsonNode message = node == null ? null : node.get("message");
            if (message != null && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignore) {
            // 对端返回了非 JSON 或空体：不影响主流程，交给兜底文案
        }
        return null;
    }

    private void applyStatusFilter(LambdaQueryWrapper<AfterSaleEntity> wrapper, String status) {
        if (status != null && !status.isBlank()) {
            wrapper.eq(AfterSaleEntity::getStatus, status.trim().toUpperCase());
        }
    }

    /**
     * 分页装配：当前页一次取回已读集合，逐条下发 {@code read}。
     *
     * <p>排序由调用方通过 {@code wrapper.last(...)} 指定（未读优先），
     * 本方法不再追加 orderBy，否则会拼出两个 ORDER BY。
     */
    private PageResult<AfterSaleView> page(LambdaQueryWrapper<AfterSaleEntity> wrapper,
                                           int page, int pageSize,
                                           Long readerId, boolean adminQueue) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<AfterSaleEntity> result = afterSaleMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AfterSaleEntity> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), pageNo, size);
        }
        List<Long> ids = records.stream().map(AfterSaleEntity::getId).toList();
        Set<Long> readIds = Set.copyOf(adminQueue
                ? afterSaleMapper.listAdminReadIds(readerId, ids)
                : afterSaleMapper.listReadIds(readerId, ids));
        List<AfterSaleView> views = records.stream()
                .map(entity -> toView(entity, readIds.contains(entity.getId())))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    private AfterSaleView toView(AfterSaleEntity entity) {
        return toView(entity, false);
    }

    private AfterSaleView toView(AfterSaleEntity entity, boolean read) {
        return new AfterSaleView(
                // 18 位雪花 id 必须以字符串出网：前端 JSON.parse 会把它四舍五入成另一个整数，
                // 拿它拼 URL 就会打到不存在的单子上（见 AfterSaleView 的注释）
                String.valueOf(entity.getId()),
                entity.getOrderNo(),
                entity.getBuyerId(),
                entity.getSellerId(),
                entity.getType(),
                AfterSaleStatus.typeLabelOf(entity.getType()),
                entity.getReason(),
                ImageUrls.fromJson(objectMapper, entity.getImages()),
                entity.getStatus(),
                AfterSaleStatus.labelOf(entity.getStatus()),
                entity.getSellerReply(),
                entity.getArbitrateResult(),
                entity.getArbitrateNote(),
                entity.getRefundAmount(),
                entity.getReturnCourier(),
                entity.getReturnTrackingNo(),
                iso(entity.getReturnShippedAt()),
                iso(entity.getReturnReceivedAt()),
                iso(entity.getCreatedAt()),
                iso(entity.getUpdatedAt()),
                read);
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
