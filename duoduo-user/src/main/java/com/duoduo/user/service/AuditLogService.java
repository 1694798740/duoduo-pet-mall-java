package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditCategory;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.AuditDtos.AuditLogView;
import com.duoduo.user.dto.AuditDtos.AuditQuery;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 后台操作审计日志的写入与检索（E18）。
 *
 * <h2>为什么「写入」是唯一入口</h2>
 * E18 之前有 5 处各自 {@code new AuditLogEntity()} 后 insert，每处自己决定写哪些字段。
 * 结果是同一张表里的记录**信息量不一致**：有的有 target、有的没有；
 * 新增「类目 / 结果 / IP / 链路」这四个维度时，5 处要改 5 遍，而漏掉一处不会报错 ——
 * 只会让那一类动作在审计页上永远缺列。收成一个入口，列的含义才只有一个解释。
 *
 * <h2>★ 写入失败不许抛异常</h2>
 * 审计是**旁路**。让它有能力把一次正常的业务操作变成 500，是为了审计牺牲业务 ——
 * 方向错了。所以 {@link #record(AuditLogRecord)} 返回是否写成功，
 * 失败只打日志（{@code ERROR} 级别：审计写不进去必须被看见），绝不外抛。
 * 跨域调用方（{@code RemoteAuditSink}）拿到 false 时会把记录降级写进自己的日志，
 * 避免出现「审计表里没有、日志里也没有」的完全不可重建状态。
 *
 * <h2>★ 检索的时间口径</h2>
 * 接受两种写法：{@code 2026-10-09}（按整日，止端自动补到当天 23:59:59.999999999）
 * 与 {@code 2026-10-09T13:00:00}。**止端按「含」处理** —— 运营选「截止到今天」
 * 时期望看到今天的记录，而写死 {@code < to} 会静默漏掉今天，且没有任何提示。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 单页上限：审计页是排查工具，不是导出工具；一次拉太多只会让浏览器卡住（与项目既有口径一致）。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 同步导出的行数上限（矩阵统一口径：超出应转异步任务） */
    private static final int MAX_EXPORT_ROWS = 50_000;

    private final AuditLogMapper auditLogMapper;
    private final SnowflakeIdGenerator idGenerator;

    public AuditLogService(AuditLogMapper auditLogMapper, SnowflakeIdGenerator idGenerator) {
        this.auditLogMapper = auditLogMapper;
        this.idGenerator = idGenerator;
    }

    // ---------------- 写入 ----------------

    /**
     * 落一条审计记录。
     *
     * @return 是否写入成功；失败**不抛异常**（见类注释）
     */
    public boolean record(AuditLogRecord raw) {
        if (raw == null) {
            return false;
        }
        try {
            AuditLogRecord record = raw.sanitized();
            AuditAction action = AuditAction.parse(record.action());
            AuditCategory category = AuditCategory.parse(record.category());
            AuditOutcome outcome = AuditOutcome.parse(record.outcome());

            AuditLogEntity entity = new AuditLogEntity();
            entity.setId(idGenerator.nextId());
            entity.setActorId(record.actorId());
            // 列是 NOT NULL：操作人缺失（理论上不该发生，内部通道也可能不带操作人）时写空串而不是 null，
            // 否则整条 insert 会失败 —— 那样丢的是一条完整记录，代价远大于一个空字符串。
            entity.setActorPhone(record.actorPhone() == null ? "" : record.actorPhone());
            entity.setAction(action.code());
            entity.setCategory(category.name());
            entity.setOutcome(outcome.name());
            entity.setTargetId(record.targetId());
            entity.setTargetType(record.targetType() == null ? "" : record.targetType());
            entity.setTenantId(record.tenantId());
            entity.setBeforeValue(record.beforeValue());
            entity.setAfterValue(record.afterValue());
            entity.setIp(record.ip());
            entity.setRequestId(record.requestId());
            entity.setDetail(record.detail() == null ? "" : record.detail());
            entity.setCreatedAt(parseCreatedAt(record.createdAt()));

            auditLogMapper.insert(entity);
            return true;
        } catch (Exception ex) {
            // ERROR 而不是 WARN：审计写不进去是**必须被看见**的问题（「以为记了、其实没记」
            // 是审计里最危险的状态），但它仍然不能影响业务，所以只是日志级别高。
            log.error("审计留痕写入失败（不影响业务）action={} actorId={} targetId={}",
                    raw.action(), raw.actorId(), raw.targetId(), ex);
            return false;
        }
    }

    /**
     * 时间戳解析：优先用发起方给的时间，解析不了就取当前时刻。
     *
     * <p>刻意**不因为时间戳不合法而丢掉整条记录** —— 记录本身（谁做了什么）比时间戳更重要，
     * 用接收时刻兜底至少保留了顺序。反过来「因为一个字符串格式不对，把整条审计丢了」
     * 是不可接受的。
     */
    private static LocalDateTime parseCreatedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            log.warn("审计记录的时间戳无法解析，按接收时刻记录：{}", raw);
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    // ---------------- 检索 ----------------

    /**
     * 按人 / 租户 / 时间 / 类目 / 动作 / 结果 / 目标检索。
     *
     * <p>权限在控制器上由 {@code @RequirePerm(Permission.AUDIT_LOG_READ)} 声明，
     * 本方法只管查询构造（与项目既有先例一致：判权集中声明、查询集中实现）。
     */
    public PageResult<AuditLogView> search(AuditQuery query, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        Page<AuditLogEntity> result = auditLogMapper.selectPage(new Page<>(safePage, safeSize),
                auditLogFilter(query));
        List<AuditLogView> items = result.getRecords().stream().map(AuditLogService::toView).toList();
        return PageResult.of(items, result.getTotal(), safePage, safeSize);
    }

    /** 审计日志导出（矩阵第二批）：与检索同一筛选，单次上限 5 万行 */
    public List<AuditLogView> exportSearch(AuditQuery query) {
        LambdaQueryWrapper<AuditLogEntity> wrapper = auditLogFilter(query);
        wrapper.last("LIMIT " + MAX_EXPORT_ROWS);
        return auditLogMapper.selectList(wrapper).stream().map(AuditLogService::toView).toList();
    }

    private LambdaQueryWrapper<AuditLogEntity> auditLogFilter(AuditQuery query) {
        AuditQuery q = query == null ? new AuditQuery(null, null, null, null, null, null, null, null) : query;

        LocalDateTime from = parseBound(q.from(), true);
        LocalDateTime to = parseBound(q.to(), false);
        if (from != null && to != null && from.isAfter(to)) {
            // 起止颠倒时**显式报错**而不是静默返回空：返回空会让人以为「这段时间真的没发生什么」，
            // 而实际是把两个参数填反了 —— 那是最容易让人带着错误结论离开的一种失败方式。
            throw BizException.validation("时间范围的起点晚于终点，请检查");
        }

        String actionFilter = normUpper(q.action());
        String categoryFilter = normUpper(q.category());
        String outcomeFilter = normUpper(q.outcome());

        return Wrappers.<AuditLogEntity>lambdaQuery()
                .eq(q.actorId() != null, AuditLogEntity::getActorId, q.actorId())
                .eq(q.tenantId() != null, AuditLogEntity::getTenantId, q.tenantId())
                .eq(q.targetId() != null, AuditLogEntity::getTargetId, q.targetId())
                // ★ 归一化后的值必须先算成局部变量，不能写成 .eq(hasText(x), col, x.trim())：
                //   Java 会**先求值所有实参再调用**，条件为 false 也照样执行 x.trim() ——
                //   于是「不带动作过滤」这句最常用的查询（打开审计页的默认请求）直接 NPE 500。
                //   这是纯粹的实现陷阱：编译通过、单测不看这条就会漏，冒烟一打就全红。
                .eq(actionFilter != null, AuditLogEntity::getAction, actionFilter)
                .eq(categoryFilter != null, AuditLogEntity::getCategory, categoryFilter)
                .eq(outcomeFilter != null, AuditLogEntity::getOutcome, outcomeFilter)
                .ge(from != null, AuditLogEntity::getCreatedAt, from)
                .le(to != null, AuditLogEntity::getCreatedAt, to)
                .orderByDesc(AuditLogEntity::getCreatedAt);
    }

    /**
     * 时间边界解析。
     *
     * @param start true = 起点；false = 终点。只给到「日」时，起点补 00:00:00、
     *              终点补到当日最后一纳秒 —— **含**语义，见类注释。
     */
    private static LocalDateTime parseBound(String raw, boolean start) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            if (text.length() == 10) {
                LocalDate date = LocalDate.parse(text);
                return start ? date.atStartOfDay() : date.atTime(23, 59, 59, 999_999_999);
            }
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ex) {
            throw BizException.validation("时间参数格式不正确（应为 2026-10-09 或 2026-10-09T13:00:00）");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 文本条件的归一化：去空白 + 转大写；空值返回 {@code null}（= 不加该条件）。
     *
     * <p>库内 {@code action / category / outcome} 落的都是枚举名（大写），
     * 所以这里统一转大写再比对 —— 运营在界面上输入 {@code security} 也应该能查到。
     */
    private static String normUpper(String raw) {
        return hasText(raw) ? raw.trim().toUpperCase() : null;
    }

    private static AuditLogView toView(AuditLogEntity entity) {
        AuditCategory category = AuditCategory.parse(entity.getCategory());
        return new AuditLogView(
                str(entity.getId()),
                str(entity.getActorId()),
                entity.getActorPhone(),
                entity.getAction(),
                category.name(),
                // 是否不可删改由**服务端的唯一判据**算出后下发，前端不自行判断 ——
                // 否则前端得再抄一份「SECURITY/FUNDS 不可删」的规则，两份规则迟早分叉。
                category.immutable(),
                AuditOutcome.parse(entity.getOutcome()).name(),
                str(entity.getTargetId()),
                entity.getTargetType(),
                str(entity.getTenantId()),
                entity.getBeforeValue(),
                entity.getAfterValue(),
                entity.getIp(),
                entity.getRequestId(),
                entity.getDetail(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString()
        );
    }

    /** 雪花 ID 按字符串流转（超出 JS 安全整数范围，见 T-39）。 */
    private static String str(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
