package com.duoduo.fulfilment.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackNode;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackView;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillCreateRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillView;
import com.duoduo.fulfilment.entity.LogisticsTrackEntity;
import com.duoduo.fulfilment.entity.WaybillEntity;
import com.duoduo.fulfilment.mapper.LogisticsTrackMapper;
import com.duoduo.fulfilment.mapper.WaybillMapper;
import com.duoduo.fulfilment.support.Couriers;
import com.duoduo.fulfilment.support.Couriers.CourierInfo;
import com.duoduo.fulfilment.support.LogisticsNode;
import com.duoduo.fulfilment.support.LogisticsProvider;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.OrderRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 电子面单服务（B-05）。
 *
 * <h2>它解决什么</h2>
 * 此前卖家发货只能手填「物流公司 + 单号」，既容易填错，也没有任何平台侧的凭据；
 * 买家看到的单号无法验证，轨迹只能靠外部手工推送。本服务把「申请面单」做成平台动作：
 * 服务端生成运单号、落 {@code t_waybill}，再把单号交给既有的发货接口。
 *
 * <h2>为什么先落模拟渠道</h2>
 * 本地没有快递鸟/菜鸟的商户号与证书，直接调真实接口无法验证，失败原因也会被网络伪装。
 * 因此先实现 {@link LogisticsProvider} 适配层与 {@code SimulatedLogisticsProvider}：
 * 接口形态、轨迹节点、前端交互与真实渠道一致，将来接渠道是替换实现，不是重做。
 * 前端会看到 {@code simulated=true} 并明确提示「模拟面单」，不能让人误以为已接真实物流。
 *
 * <h2>幂等</h2>
 * 一个订单最多一张面单（{@code uk_waybill_order}）。卖家连点两次返回同一张；
 * 并发申请时第二个请求撞唯一键 → 回读已有面单，而不是报 500。
 */
@Service
public class WaybillService {

    private static final Logger log = LoggerFactory.getLogger(WaybillService.class);

    /** 只有待发货 / 已发货可以申请面单：已完成的订单再补面单没有业务意义 */
    private static final Set<String> ALLOWED_ORDER_STATUS = Set.of("PAID", "SHIPPED");

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_SIGNED = "SIGNED";
    private static final String MODE_SIMULATED = "SIMULATED";

    private final WaybillMapper waybillMapper;
    private final LogisticsTrackMapper trackMapper;
    private final OrderFacts orderFacts;
    private final LogisticsProvider provider;
    private final LogisticsService logisticsService;
    private final SnowflakeIdGenerator idGenerator;

    public WaybillService(WaybillMapper waybillMapper,
                          LogisticsTrackMapper trackMapper,
                          OrderFacts orderFacts,
                          LogisticsProvider provider,
                          LogisticsService logisticsService,
                          SnowflakeIdGenerator idGenerator) {
        this.waybillMapper = waybillMapper;
        this.trackMapper = trackMapper;
        this.orderFacts = orderFacts;
        this.provider = provider;
        this.logisticsService = logisticsService;
        this.idGenerator = idGenerator;
    }

    /**
     * 卖家 / 运营申请电子面单（幂等）。
     *
     * <p>准入：必须是该订单卖家或平台运营 —— 面单上印着收发货信息，
     * 不能让任意登录用户按订单号申请。订单状态必须是待发货 / 已发货。
     */
    public WaybillView create(WaybillCreateRequest request) {
        LoginUser user = UserContext.require();
        String orderNo = request == null ? null : trimToNull(request.orderNo());
        if (orderNo == null) {
            throw BizException.validation("订单号不能为空");
        }
        OrderFact fact = orderFacts.require(orderNo);
        boolean seller = fact.sellerId() != null && user.userId().equals(fact.sellerId());
        if (!seller && !user.isOperator()) {
            throw BizException.forbidden("只有该订单卖家或平台运营可以申请电子面单");
        }
        if (!ALLOWED_ORDER_STATUS.contains(fact.status())) {
            throw BizException.businessRule("订单当前状态（" + fact.statusLabel() + "）不能申请电子面单");
        }

        CourierInfo courier = Couriers.resolve(request.courierCode());
        if (courier == null) {
            throw BizException.validation("不支持的快递公司编码：" + request.courierCode()
                    + "（可选：" + Couriers.all().stream().map(CourierInfo::code).collect(Collectors.joining(" / ")) + "）");
        }

        WaybillEntity existing = waybillMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            return view(existing);
        }

        LogisticsProvider.Waybill created = provider.create(
                new LogisticsProvider.WaybillRequest(orderNo, courier));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        WaybillEntity entity = new WaybillEntity();
        entity.setId(idGenerator.nextId());
        entity.setOrderId(OrderRefs.orderIdOf(orderNo));
        entity.setOrderNo(orderNo);
        entity.setSellerId(fact.sellerId() == null ? 0L : fact.sellerId());
        entity.setCourierCode(courier.code());
        entity.setCourierName(courier.name());
        entity.setWaybillNo(created.waybillNo());
        entity.setMode(created.mode());
        entity.setStatus(STATUS_CREATED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            waybillMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发申请：另一个请求刚插进去，回读同一张面单（幂等，不把并发判成失败）
            WaybillEntity concurrent = waybillMapper.selectByOrderNo(orderNo);
            if (concurrent != null) {
                return view(concurrent);
            }
            throw e;
        }
        log.info("订单 {} 申请电子面单：{} {}（模式 {}，申请人 {}）",
                orderNo, courier.code(), created.waybillNo(), created.mode(), user.userId());
        return view(entity);
    }

    /** 订单当前有效面单（当事人 / 运营）；没有面单返回 404。 */
    public WaybillView forParty(String orderNo) {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw BizException.unauthorized("请先登录");
        }
        OrderFact fact = orderFacts.require(orderNo);
        boolean buyer = user.userId().equals(fact.userId());
        boolean seller = fact.sellerId() != null && user.userId().equals(fact.sellerId());
        if (!buyer && !seller && !user.isOperator()) {
            throw BizException.forbidden("只能查看与自己相关的订单电子面单");
        }
        WaybillEntity entity = waybillMapper.selectByOrderNo(orderNo);
        if (entity == null) {
            throw BizException.notFound("电子面单");
        }
        return view(entity);
    }

    /**
     * 推进一次模拟轨迹（定时任务与内部测试端点共用）。
     *
     * <p>节点按「轨迹里已有节点」判定下一条，而不是按调用次数：
     * 交易域发货时已经推了 {@code PICKED_UP}，这里必须从 {@code IN_TRANSIT} 接着走，
     * 否则会出现两条「已揽收」。
     *
     * <p>只对 {@code SIMULATED} 面单生效：真实渠道的轨迹必须来自快递公司推送，
     * 本地绝不能替它编节点。
     */
    public WaybillView advance(String orderNo) {
        String normalized = trimToNull(orderNo);
        if (normalized == null) {
            throw BizException.validation("订单号不能为空");
        }
        WaybillEntity entity = waybillMapper.selectByOrderNo(normalized);
        if (entity == null) {
            throw BizException.notFound("电子面单");
        }
        if (!MODE_SIMULATED.equals(entity.getMode())) {
            throw BizException.businessRule("仅模拟面单支持推进模拟轨迹；真实渠道轨迹由快递公司推送");
        }
        List<LogisticsTrackEntity> tracks = trackMapper.listByOrderNo(normalized);
        String next = nextNode(tracks);
        if (next == null) {
            markSignedIfNeeded(entity, tracks);
            return view(entity);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TrackNode node = new TrackNode(next, simulatedText(entity, next), now.toString(),
                "simulated-waybill:" + entity.getWaybillNo());
        logisticsService.ingest(normalized, new TrackIngestRequest(LogisticsNode.SOURCE_COURIER, List.of(node)));
        waybillMapper.updateStatus(entity.getId(), next, now);
        entity.setStatus(next);
        entity.setUpdatedAt(now);
        log.info("订单 {} 模拟轨迹推进到 {}（面单 {}）", normalized, next, entity.getWaybillNo());
        return view(entity);
    }

    private String nextNode(List<LogisticsTrackEntity> tracks) {
        Set<String> codes = tracks.stream().map(LogisticsTrackEntity::getNodeCode).collect(Collectors.toSet());
        if (codes.contains(LogisticsNode.SIGNED) || codes.contains(LogisticsNode.REJECTED)) {
            return null;
        }
        if (!codes.contains(LogisticsNode.PICKED_UP)) {
            return LogisticsNode.PICKED_UP;
        }
        if (!codes.contains(LogisticsNode.IN_TRANSIT)) {
            return LogisticsNode.IN_TRANSIT;
        }
        if (!codes.contains(LogisticsNode.OUT_FOR_DELIVERY)) {
            return LogisticsNode.OUT_FOR_DELIVERY;
        }
        if (!codes.contains(LogisticsNode.SIGNED)) {
            return LogisticsNode.SIGNED;
        }
        return null;
    }

    private void markSignedIfNeeded(WaybillEntity entity, List<LogisticsTrackEntity> tracks) {
        boolean signed = tracks.stream().anyMatch(t -> LogisticsNode.SIGNED.equals(t.getNodeCode()));
        if (signed && !STATUS_SIGNED.equals(entity.getStatus())) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            waybillMapper.updateStatus(entity.getId(), STATUS_SIGNED, now);
            entity.setStatus(STATUS_SIGNED);
            entity.setUpdatedAt(now);
        }
    }

    private String simulatedText(WaybillEntity entity, String nodeCode) {
        return "【模拟】" + entity.getCourierName() + " " + LogisticsNode.labelOf(nodeCode)
                + "（运单 " + entity.getWaybillNo() + "）";
    }

    private WaybillView view(WaybillEntity entity) {
        List<TrackView> tracks = trackMapper.listByOrderNo(entity.getOrderNo()).stream()
                .map(row -> new TrackView(row.getNodeCode(), LogisticsNode.labelOf(row.getNodeCode()),
                        row.getNodeText(), iso(row.getOccurredAt())))
                .toList();
        return new WaybillView(
                entity.getOrderNo(),
                entity.getCourierCode(),
                entity.getCourierName(),
                entity.getWaybillNo(),
                entity.getMode(),
                MODE_SIMULATED.equals(entity.getMode()),
                entity.getStatus(),
                statusLabelOf(entity.getStatus()),
                iso(entity.getCreatedAt()),
                tracks);
    }

    private static String statusLabelOf(String status) {
        if (status == null) {
            return "";
        }
        return STATUS_CREATED.equals(status) ? "已生成" : LogisticsNode.labelOf(status);
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
