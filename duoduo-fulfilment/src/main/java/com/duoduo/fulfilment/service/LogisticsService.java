package com.duoduo.fulfilment.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.FulfilmentNotifier;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestResult;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackListView;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackNode;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackView;
import com.duoduo.fulfilment.entity.LogisticsTrackEntity;
import com.duoduo.fulfilment.mapper.LogisticsTrackMapper;
import com.duoduo.fulfilment.support.LogisticsNode;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.OrderRefs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流轨迹服务（A2）。
 *
 * <p><b>要解决的问题</b>：发货之后，系统里只剩订单上的「物流公司 + 单号」两个字段。
 * 买家想知道包裹到哪了必须自己复制单号去快递官网查；卖家被问起来也一样。
 * 这里把「一条单号下发生过哪些节点」变成平台内可查的记录，买卖双方的订单详情页都能看到时间线。
 *
 * <p><b>数据是谁写的</b>：外部（当前是模拟推送，报文形态与真实快递回调一致），
 * 经由内部凭据保护的摄入接口写入；「已揽收」这一条由交易域在发货事务提交后补推。
 * 因此本服务**不生成**节点，只做校验、去重、留档与查询 ——
 * 轨迹是**事实记录**，不是本域算出来的东西。
 *
 * <p><b>幂等</b>：靠唯一索引 {@code uk_track_order_node_time}（订单号 + 节点码 + 发生时间）。
 * 物流推送的常见形态就是「失败重推 / 补推 / 定时全量对齐」，同一条节点被推两次是正常的，
 * 撞键即算「跳过」，并把 inserted / skipped 分开回报 ——
 * 只回一个「成功」的话，重推与漏推在监控上看不出区别。
 *
 * <p><b>批量是全有或全无（先全部校验、再逐条写）</b>：若边校验边写，
 * 中途遇到一个非法节点就返回 400，调用方无从知道前面几条到底落了没有，
 * 只能整体重推 —— 而重推又会被唯一索引当成重复。先校验完再写，
 * 非法批次**一条都不落**，调用方改完重推即可，语义干净。
 * 批量内部**不加事务**：每条各自成事，回执里的 inserted / skipped 才是诚实的进度。
 */
@Service
public class LogisticsService {

    private static final Logger log = LoggerFactory.getLogger(LogisticsService.class);

    /**
     * 单次摄入的节点数上限。
     *
     * <p>不是「怕写不动」，而是防**一个坏批次把整条轨迹刷满**：
     * 轨迹是给用户看的时间线，一次推 500 条同一天的节点，页面就成了流水账。
     * 真需要大批对齐时，拆成多个批次推送即可。
     */
    private static final int MAX_BATCH = 50;

    /** 节点描述长度上限（与 DDL 的 VARCHAR(200) 对齐） */
    private static final int MAX_TEXT = 200;

    /** 原始报文长度上限（与 DDL 的 VARCHAR(1000) 对齐） */
    private static final int MAX_RAW = 1000;

    /**
     * 允许的「未来时间」容忍度。
     *
     * <p>节点发生时间超过当前时间这么多，一定是推送方的时钟或字段错了。
     * 留一点余量给服务间时钟漂移，而不是要求完全不能超前 ——
     * 卡死到「不能超前 1 秒」会让一次正常的时钟漂移变成整批被拒。
     */
    private static final long FUTURE_TOLERANCE_SECONDS = 3600;

    private final LogisticsTrackMapper trackMapper;
    private final OrderFacts orderFacts;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final FulfilmentNotifier fulfilmentNotifier;

    public LogisticsService(LogisticsTrackMapper trackMapper,
                            OrderFacts orderFacts,
                            SnowflakeIdGenerator idGenerator,
                            ObjectMapper objectMapper,
                            FulfilmentNotifier fulfilmentNotifier) {
        this.trackMapper = trackMapper;
        this.orderFacts = orderFacts;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.fulfilmentNotifier = fulfilmentNotifier;
    }

    // ---------------- 内部：轨迹摄入 ----------------

    /**
     * 批量摄入轨迹节点（内部接口，{@code X-Internal-Token} 保护）。
     *
     * <p>订单必须**真实存在**才写：轨迹按订单号聚合，一个打错的订单号会造出一条
     * 谁也查不到、又永远留在库里的孤儿轨迹。这里宁可多一次跨服务读，也不放孤儿数据进来
     * （失败关闭，与评价域 / 售后域同一口径）。
     *
     * <p><b>刻意不校验订单状态</b>：直觉上「未发货的订单不该有轨迹」，
     * 但订单可能因为退款而变成 REFUNDED，而包裹此时还在路上 ——
     * 按状态拦掉会把真实的后续节点全部丢掉，页面上的轨迹就断在半路。
     * 轨迹是对**真实运输过程**的记录，它不从属于订单状态机。
     */
    public TrackIngestResult ingest(String orderNo, TrackIngestRequest request) {
        OrderFact fact = orderFacts.require(orderNo);
        List<TrackNode> nodes = request == null ? null : request.nodes();
        if (nodes == null || nodes.isEmpty()) {
            throw BizException.validation("节点列表不能为空");
        }
        if (nodes.size() > MAX_BATCH) {
            throw BizException.validation("单次最多上报 " + MAX_BATCH + " 条节点，当前 " + nodes.size());
        }
        String source = normalizeSource(request.source());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long orderId = OrderRefs.orderIdOf(fact.orderNo());

        // 先全部校验并装配，再逐条写：非法批次一条都不落（见类注释）
        List<LogisticsTrackEntity> prepared = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            prepared.add(prepare(orderId, fact.orderNo(), nodes.get(i), source, now, i));
        }

        int inserted = 0;
        int skipped = 0;
        List<LogisticsTrackEntity> fresh = new ArrayList<>(prepared.size());
        for (LogisticsTrackEntity entity : prepared) {
            try {
                trackMapper.insert(entity);
                fresh.add(entity);
                inserted++;
            } catch (DuplicateKeyException e) {
                // 重推 / 补推：同订单 + 同节点 + 同发生时间已经在了，这正是唯一索引要挡住的情况
                skipped++;
            }
        }
        log.info("订单 {} 上报轨迹 {} 条（来源 {}）：新增 {}，重复跳过 {}",
                fact.orderNo(), nodes.size(), source, inserted, skipped);

        notifySignedQuietly(fact, fresh);

        return new TrackIngestResult(fact.orderNo(), nodes.size(), inserted, skipped);
    }

    /**
     * 包裹已签收通知（第五十五轮）：best-effort（异常在 {@link FulfilmentNotifier} 内部吞掉）。
     *
     * <p><b>只推「已签收」</b>：一个包裹一生有十来个节点（揽收 / 中转 / 派送…），
     * 逐个推会把通知中心变成快递流水账 —— 订单详情的时间线本来就是为看这个准备的。
     * 「已签收」是唯一需要用户立刻行动（去确认收货、检查货）的节点，只推它。
     *
     * <p><b>判据是「本次真的写进去了」而不是「请求里带了它」</b>：
     * 快递公司重推同一节点是常态（唯一索引会挡掉重复），按请求推就会推出 N 条同样的通知。
     */
    private void notifySignedQuietly(OrderFact fact, List<LogisticsTrackEntity> freshNodes) {
        boolean signed = freshNodes.stream()
                .anyMatch(n -> LogisticsNode.SIGNED.equals(n.getNodeCode()));
        if (signed) {
            fulfilmentNotifier.notifySigned(fact.userId(), fact.orderNo());
        }
    }

    private LogisticsTrackEntity prepare(Long orderId, String orderNo, TrackNode node,
                                         String source, LocalDateTime now, int index) {
        String where = "第 " + (index + 1) + " 条节点";
        if (node == null) {
            throw BizException.validation(where + "为空");
        }
        String code = node.nodeCode() == null ? null : node.nodeCode().trim().toUpperCase();
        if (!LogisticsNode.isKnown(code)) {
            // 不回显全部合法值：白名单会演进，把清单写进错误信息等于制造第二份需要同步的文档
            throw BizException.validation(where + "的节点码不在允许范围内：" + node.nodeCode());
        }
        LocalDateTime occurredAt = parseOccurredAt(node.occurredAt(), where);
        if (occurredAt.isAfter(now.plusSeconds(FUTURE_TOLERANCE_SECONDS))) {
            throw BizException.validation(where + "的发生时间超前于当前时间过多，疑似推送方时钟或字段有误");
        }
        String text = trimToNull(node.nodeText());
        if (text == null) {
            text = LogisticsNode.defaultTextOf(code);
        }
        if (text.length() > MAX_TEXT) {
            throw BizException.validation(where + "的节点描述超过 " + MAX_TEXT + " 字");
        }

        LogisticsTrackEntity entity = new LogisticsTrackEntity();
        // id-type: input，主键必须显式赋雪花值，否则 Column 'id' cannot be null（INV-13）
        entity.setId(idGenerator.nextId());
        entity.setOrderId(orderId);
        entity.setOrderNo(orderNo);
        entity.setNodeCode(code);
        entity.setNodeText(text);
        entity.setOccurredAt(occurredAt);
        entity.setRaw(buildRaw(source, node.raw(), where));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    // ---------------- 对外：查询轨迹 ----------------

    /**
     * 查某个订单的轨迹（当事人或运营）。
     *
     * <p>准入用**订单事实**判断，不用请求参数：买家本人、该单卖家、运营。
     * 轨迹里虽然没有收货人信息，但它能暴露「这个人买了什么、寄到哪个城市」，
     * 属于当事人信息，不能只靠「猜到订单号」就开放。
     *
     * <p>订单还没有轨迹（未发货，或已发货但推送未到）时返回**空列表而不是 404** ——
     * 「暂时没有轨迹」是正常状态，用错误码表达会被前端渲染成故障（T-17 同类）。
     */
    public TrackListView listForParty(Long userId, String orderNo) {
        LoginUser user = requireLogin(userId);
        OrderFact fact = orderFacts.require(orderNo);

        boolean isBuyer = userId.equals(fact.userId());
        boolean isSeller = fact.sellerId() != null && userId.equals(fact.sellerId());
        if (!isBuyer && !isSeller && !user.isOperator()) {
            throw BizException.forbidden("只能查看与自己相关的订单物流");
        }

        List<LogisticsTrackEntity> rows = trackMapper.listByOrderNo(fact.orderNo());
        List<TrackView> tracks = new ArrayList<>(rows.size());
        for (LogisticsTrackEntity row : rows) {
            tracks.add(new TrackView(
                    row.getNodeCode(),
                    LogisticsNode.labelOf(row.getNodeCode()),
                    row.getNodeText(),
                    iso(row.getOccurredAt())));
        }
        return new TrackListView(fact.orderNo(), tracks.size(), tracks);
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

    /** 上报来源：不认识的一律当 SYSTEM（它只写进留档报文，不参与业务判断，不必因此拒掉整批） */
    private String normalizeSource(String source) {
        String value = source == null ? null : source.trim().toUpperCase();
        return LogisticsNode.isKnownSource(value) ? value : LogisticsNode.SOURCE_SYSTEM;
    }

    /**
     * 解析节点发生时间，统一转 UTC。
     *
     * <p>接受三种 ISO-8601 写法：{@code …Z}、带偏移 {@code +08:00}、以及不带时区（按 UTC 解释）。
     * 不带时区的写法在这里是**默认 UTC 而不是服务器本地时区**：
     * 按本地时区解释会让同一份报文在开发机（东八区）与容器（UTC）里落成相差 8 小时的两条节点，
     * 于是唯一索引兜不住重复，轨迹里出现两条一模一样的记录。
     *
     * <p>不接受只有日期的写法：物流时间要精确到秒，否则同一天的多个节点会互相撞成「重复」。
     */
    private LocalDateTime parseOccurredAt(String text, String where) {
        String value = trimToNull(text);
        if (value == null) {
            throw BizException.validation(where + "的发生时间不能为空");
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignore) {
            // 继续尝试无时区写法
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw BizException.validation(
                    where + "的发生时间不是合法的 ISO-8601（形如 2026-09-20T02:30:00Z）：" + value);
        }
    }

    /**
     * 装配留档报文：来源 + 推送方原始字段。
     *
     * <p>用 JSON 而不是拼字符串：将来对快递公司的报文时，不改表结构就能多存字段；
     * 而拼字符串的格式一旦变化，历史数据就成了没人能解析的一团。
     */
    private String buildRaw(String source, String payload, String where) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("source", source);
        String detail = truncate(trimToNull(payload), MAX_RAW - 64);
        if (detail != null) {
            raw.put("payload", detail);
        }
        try {
            String json = objectMapper.writeValueAsString(raw);
            if (json.length() > MAX_RAW) {
                // 兜底：payload 已截断，正常到不了这里；真到了宁可丢掉报文也不能让入库失败
                log.warn("{} 的留档报文超长（{} 字符），已丢弃", where, json.length());
                raw.remove("payload");
                json = objectMapper.writeValueAsString(raw);
            }
            return json;
        } catch (JsonProcessingException e) {
            // 留档失败不该让整批被拒：轨迹本身（节点码 + 时间）才是业务价值所在
            log.warn("{} 的留档报文序列化失败，按来源留档", where, e);
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max));
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
