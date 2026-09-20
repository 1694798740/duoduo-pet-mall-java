package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.OrderClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.client.dto.RefundRequest;
import com.duoduo.fulfilment.client.dto.RefundResult;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleArbitrateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleCreateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReplyRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
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

    /** 可裁定的售后单状态（终态不可再改判，要改判只能新立案） */
    private static final Set<String> ARBITRABLE = Set.of(
            AfterSaleStatus.PENDING, AfterSaleStatus.SELLER_REJECTED, AfterSaleStatus.ARBITRATING);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final AfterSaleMapper afterSaleMapper;
    /** 只用于执行退款；取订单事实已收敛到 {@link OrderFacts}（见其类注释） */
    private final OrderClient orderClient;
    private final OrderFacts orderFacts;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final int maxImages;

    public AfterSaleService(AfterSaleMapper afterSaleMapper,
                            OrderClient orderClient,
                            OrderFacts orderFacts,
                            SnowflakeIdGenerator idGenerator,
                            ObjectMapper objectMapper,
                            @Value("${duoduo.aftersale.max-images:6}") int maxImages) {
        this.afterSaleMapper = afterSaleMapper;
        this.orderClient = orderClient;
        this.orderFacts = orderFacts;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
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
        if (!userId.equals(entity.getSellerId())) {
            // 越权与不存在刻意区分：售后单 id 是雪花值不可枚举，明说「不是你的店」更有助于排查
            throw BizException.forbidden("只能处理自己店铺的售后单");
        }
        requireStatus(entity, Set.of(AfterSaleStatus.PENDING),
                "只有「待卖家处理」的售后单可以处理");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (AfterSaleStatus.ACTION_AGREE.equals(action)) {
            // 先让钱动，再记结论
            RefundResult refund = callTradeRefund(entity.getOrderNo(),
                    "卖家同意售后退款（售后单 " + entity.getId() + "）");
            int rows = afterSaleMapper.markRefundedBySeller(entity.getId(), "AGREE",
                    refund.refundAmount(), now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 卖家 {} 同意退款，退款金额 {} 分", entity.getId(), userId, refund.refundAmount());
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
        }
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
        requireStatus(entity, Set.of(AfterSaleStatus.PENDING, AfterSaleStatus.SELLER_REJECTED),
                "该售后单当前状态不允许申请仲裁");
        int rows = afterSaleMapper.markArbitrating(entity.getId(), LocalDateTime.now(ZoneOffset.UTC));
        if (rows == 0) {
            throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
        }
        log.info("售后单 {} 由买家 {} 申请平台仲裁", entity.getId(), userId);
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
        } else {
            int rows = afterSaleMapper.markClosedByArbitration(entity.getId(), result, note,
                    operator.userId(), now);
            if (rows == 0) {
                throw BizException.conflict("该售后单刚刚已被处理，请刷新后查看");
            }
            log.info("售后单 {} 由运营 {} 裁定驳回：{}", entity.getId(), operator.userId(), note);
        }
        return toView(loadAfterSale(id));
    }

    // ---------------- 读 ----------------

    /** 我提起的售后（买家视角） */
    public PageResult<AfterSaleView> listMine(Long userId, String status, int page, int pageSize) {
        requireLogin(userId);
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getBuyerId, userId);
        applyStatusFilter(wrapper, status);
        return page(wrapper, page, pageSize);
    }

    /**
     * 我店铺的售后（卖家视角）。
     *
     * <p>可见范围完全由登录态推导，**不接受任何 sellerId 参数** ——
     * 接受即等于开放「换个 ID 看别人店铺的售后」。
     * 运营额外可见平台自营（{@code sellerId = 0}）：当前商品域没有商家字段，
     * 自营单没有真的卖家账号可登录，只能由运营代处理（与卖家中心同一口径）。
     */
    public PageResult<AfterSaleView> listForSeller(Long userId, String status, int page, int pageSize) {
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
        return page(wrapper, page, pageSize);
    }

    /** 全平台售后（运营） */
    public PageResult<AfterSaleView> listAll(String status, int page, int pageSize) {
        OperatorGuard.requireOperator();
        LambdaQueryWrapper<AfterSaleEntity> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        return page(wrapper, page, pageSize);
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

    private PageResult<AfterSaleView> page(LambdaQueryWrapper<AfterSaleEntity> wrapper,
                                           int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        wrapper.orderByDesc(AfterSaleEntity::getCreatedAt).orderByDesc(AfterSaleEntity::getId);
        Page<AfterSaleEntity> result = afterSaleMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AfterSaleView> views = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    private AfterSaleView toView(AfterSaleEntity entity) {
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
                iso(entity.getCreatedAt()),
                iso(entity.getUpdatedAt()));
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
