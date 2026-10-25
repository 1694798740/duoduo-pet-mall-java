package com.duoduo.product.support.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引自动同步（A10，A-12 收紧延迟与失败重试）。
 *
 * <p><b>三条路共同保证「变更 5 秒内可搜」</b>：
 * <ol>
 *   <li><b>变更扫描</b>：每 3 秒扫描 {@code updated_at} 晚于上一轮的 SPU / 闲置 / SKU ——
 *       上下架、编辑、审核、删除、恢复、改价改库存、库存冻结与释放都推 {@code updated_at}，
 *       因此一个扫描周期 + ES 默认 1 秒刷新，最迟约 4 秒可见（5 秒验收留了 1 秒余量）；</li>
 *   <li><b>失败重试</b>：单条写失败（ES 抖动 / 超时）进入 pending 集合，
 *       之后每轮扫描先重试它们，直到成功 —— 不能让「本轮失败」变成「这条永远不补」；</li>
 *   <li><b>全量对齐</b>：启动时、以及检测到索引被重建（ES 数据丢失 / 索引被删）后，
 *       整库重写一遍并清孤儿。增量扫描只能看到「最近变过」的实体，补不回整库。</li>
 * </ol>
 *
 * <p>为什么不做「写路径同步双写」：商品/库存的写入口分散（商家端、运营端、
 * 交易域经内部凭据扣减库存），在每条写路径上埋索引调用一定会漏 ——
 * 而漏了是静默的。单点扫描扫的是**数据库事实**，天然不漏；秒级延迟
 * 换来的一致性保证是划算的（对检索场景，「最终一致 + 有对账」优于「实时 + 会漏」）。
 * 唯一例外是**物理清理**（删除后库行不再存在，没有任何 {@code updated_at} 可扫），
 * 由 {@link SearchDocCleanupListener} 在事务提交后立即摘除文档。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncJob.class);

    /** 选主用的任务名（一个任务一个名字，跨实例必须一致） */
    private static final String TASK_NAME = "search-sync";
    /**
     * 全量对齐的租约：整库逐条写可能跑很久，必须留足余量。
     *
     * <p>日常增量扫描只用短租约（见 {@link #sweep()}）—— 这样实例被杀的接管等待
     * 只有十几秒而不是两分钟。A-12 冒烟第一次跑时正是被旧容器的 120s 残留锁挡住：
     * 新实例启动后两分钟内一轮都扫不到，变更全漏。
     */
    private static final long FULL_SYNC_LEASE_MS = 120_000L;

    private final SearchIndexer indexer;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final ListingMapper listingMapper;
    private final SchedLeaderLock leaderLock;
    private final long sweepIntervalMs;
    private final AtomicReference<LocalDateTime> lastSweepAt = new AtomicReference<>(null);
    private final AtomicBoolean bootstrapped = new AtomicBoolean(false);
    /**
     * 重试队列：只存「id」，重试时重新读库再决定写还是删。
     *
     * <p>不存文档内容：失败可能持续几分钟，期间商品可能又被改过；
     * 存快照会在恢复时把旧内容写回去，比不补更糟。
     */
    private final Set<Long> pendingProductIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingListingIds = ConcurrentHashMap.newKeySet();

    public SearchSyncJob(SearchIndexer indexer,
                         SpuMapper spuMapper,
                         SkuMapper skuMapper,
                         ListingMapper listingMapper,
                         SchedLeaderLock leaderLock,
                         @Value("${duoduo.search.sweep-interval-ms}") long sweepIntervalMs) {
        this.indexer = indexer;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.listingMapper = listingMapper;
        this.leaderLock = leaderLock;
        this.sweepIntervalMs = sweepIntervalMs;
    }

    /** 启动即全量对齐一次（应用就绪后异步执行，不阻塞启动） */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread t = new Thread(this::fullSync, "search-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /** 优雅停机主动释放选主锁：滚动发布时新实例不必等 TTL 过期才接管 */
    @PreDestroy
    public void releaseLeaderLock() {
        leaderLock.release(TASK_NAME);
    }

    /** 变更扫描：updated_at 晚于上一轮的实体重新入索引；SKU 变动连带其 SPU */
    @Scheduled(fixedDelayString = "${duoduo.search.sweep-interval-ms:3000}",
               initialDelayString = "${duoduo.search.sweep-interval-ms:3000}")
    public void sweep() {
        // 选主（第五十八轮 · P0-2）：本任务 3 秒一轮、最频繁，
        // 多实例并发扫描会互相覆盖 updated_at 窗口，最该先加选主的就是它。
        // 租约只覆盖「一轮增量扫描」的时长；真要全量对齐时再单独延长（见下），
        // 不能为了偶尔的长任务让每次故障接管都等 120 秒。
        long sweepLeaseMs = Math.max(15_000L, sweepIntervalMs * 5);
        if (!leaderLock.tryAcquire(TASK_NAME, sweepLeaseMs)) {
            return;
        }
        try {
            // 每轮先校验「索引存在且形态正确」，而不是只看 indexReady 标记。
            // 索引被删后，任意一次写入都会触发 ES 的 auto_create_index，用动态 mapping
            // 静默建回一个同名索引 —— 那种索引 exists=true、写入不报错，
            // 只有按字段认形态才能发现并重建（不校验就会「删了索引还能写，但过滤全空」）。
            indexer.ensureIndex();
            boolean recreated = indexer.isIndexRecreated();
            if (!bootstrapped.get() || recreated) {
                if (recreated) {
                    log.warn("检测到索引已重建（数据丢失 / 形态被自动创建），触发全量对齐补回整库");
                }
                // 全量对齐可能跑很久：先延长租约；拿不到说明别的实例接管了，
                // 本轮直接返回。重建标记不消费，下一轮还会重试 —— 不能被无声吞掉。
                if (!leaderLock.tryAcquire(TASK_NAME,
                        Math.max(FULL_SYNC_LEASE_MS, sweepIntervalMs * 8))) {
                    return;
                }
                fullSync(false);
                return;
            }
            LocalDateTime since = lastSweepAt.get();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 首次扫描没有基准点：回看一个周期 + 2 秒，避免刚启动时漏扫；
            // 之后从上一轮起点回退 2 秒（重叠窗口），抵消毫秒截断与时钟偏差造成的漏扫
            LocalDateTime effective = since == null
                    ? now.minusSeconds(sweepIntervalMs / 1000 + 2)
                    : since.minusSeconds(2);
            // pending 集合会被并进这两步：上一轮失败的 id 与「本轮变更」一起去重后重试
            int count = reindexChangedSpu(effective);
            count += reindexChangedListings(effective);
            lastSweepAt.set(now);
            if (count > 0) {
                log.info("索引自动同步完成 本轮重索引={} 条 待重试={} 商品/{} 闲置",
                        count, pendingProductIds.size(), pendingListingIds.size());
            }
        } catch (Exception ex) {
            log.warn("索引自动同步失败（下轮重试）: {}", ex.getMessage());
        }
    }

    /** 全量重建：先建/写全量文档，再清孤儿（索引里有、库里没有的 —— 硬删除的唯一清理路径） */
    public synchronized void fullSync() {
        fullSync(true);
    }

    /**
     * @param verifyIndex 是否先确保索引可用。sweep 刚刚校验过索引形态，
     *                    这里传 false，省掉每轮一次多余的映射查询。
     */
    private synchronized void fullSync(boolean verifyIndex) {
        // 增量窗口的基准点取「扫描开始时刻」而不是结束时刻：扫描期间被改过的行
        // 可能已经读过旧值，把基准点留在起点（再回退 2 秒）才能被下一轮补上。
        // 更重要的是：选主等待 / 启动期变更不会因为「第一次扫描只回看 5 秒」而永久漏掉。
        LocalDateTime scanStartedAt = LocalDateTime.now(ZoneOffset.UTC);
        try {
            // 索引不可用就别白扫一遍全表：写完也是失败，还会刷一屏 WARN
            if (verifyIndex) {
                indexer.ensureIndex();
            }
            if (!indexer.isIndexReady()) {
                log.warn("ES 索引不可用，本轮全量对齐跳过（下轮重试）");
                return;
            }
            List<SpuEntity> spus = spuMapper.selectList(null);
            int ok = 0;
            for (SpuEntity spu : spus) {
                if (trackProduct(spu.getId())) {
                    ok++;
                }
            }
            List<ListingEntity> listings = listingMapper.selectList(null);
            for (ListingEntity l : listings) {
                if (trackListing(l.getId())) {
                    ok++;
                }
            }
            // 清孤儿：商品/闲置被硬删除后库侧不会再有 updated_at 变化，
            // 增量扫描追不到，只有全量对齐能把索引里的残留文档摘掉
            Map<Long, Long> dbProducts = new java.util.LinkedHashMap<>();
            spus.forEach(s -> dbProducts.put(s.getId(), 0L));
            indexer.listDocs(SearchIndexer.TYPE_PRODUCT).keySet().stream()
                    .filter(id -> !dbProducts.containsKey(id))
                    .forEach(id -> indexer.deleteDoc(SearchIndexer.TYPE_PRODUCT, id));
            Map<Long, Long> dbListings = new java.util.LinkedHashMap<>();
            listings.forEach(l -> dbListings.put(l.getId(), 0L));
            indexer.listDocs(SearchIndexer.TYPE_LISTING).keySet().stream()
                    .filter(id -> !dbListings.containsKey(id))
                    .forEach(id -> indexer.deleteDoc(SearchIndexer.TYPE_LISTING, id));
            // 结尾统一刷一次：让「重建完成」等于「立即可见」（对账与检索都不必等 1 秒刷新周期）
            indexer.refreshIndex();
            // 扫描起点成为下一轮增量的基准；只前进不后退（与并发增量扫描取较大值）
            lastSweepAt.accumulateAndGet(scanStartedAt.minusSeconds(2),
                    (prev, candidate) -> prev == null || prev.isBefore(candidate) ? candidate : prev);
            bootstrapped.set(true);
            // 本次全量对齐已经把整库补回，索引重建标记不再需要触发第二轮
            indexer.consumeIndexRecreated();
            log.info("ES 全量对齐完成 实体总数={} 成功={} 待重试={}", spus.size() + listings.size(), ok,
                    pendingProductIds.size() + pendingListingIds.size());
        } catch (Exception ex) {
            log.warn("ES 全量对齐失败（扫描任务稍后重试）: {}", ex.getMessage());
        }
    }

    /** SKU 库存/状态变动 → 连带重索引其 SPU（物种/生命阶段/有货状态都冗余在 SPU 文档里） */
    private int reindexChangedSpu(LocalDateTime since) {
        // 先把上轮失败的 id 放进来：它们可能已经不再「最近变过」，
        // 靠 updated_at 窗口永远捡不回来，只能由重试队列自己记着
        Set<Long> ids = new LinkedHashSet<>(pendingProductIds);
        List<SkuEntity> changedSkus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .gt(SkuEntity::getUpdatedAt, since)
                .select(SkuEntity::getSpuId));
        changedSkus.stream().map(SkuEntity::getSpuId).filter(java.util.Objects::nonNull).forEach(ids::add);
        List<SpuEntity> changedSpu = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                .gt(SpuEntity::getUpdatedAt, since)
                .select(SpuEntity::getId));
        changedSpu.stream().map(SpuEntity::getId).filter(java.util.Objects::nonNull).forEach(ids::add);

        int count = 0;
        for (Long spuId : ids) {
            if (trackProduct(spuId)) {
                count++;
            }
        }
        return count;
    }

    private int reindexChangedListings(LocalDateTime since) {
        Set<Long> ids = new LinkedHashSet<>(pendingListingIds);
        List<ListingEntity> changed = listingMapper.selectList(new LambdaQueryWrapper<ListingEntity>()
                .gt(ListingEntity::getUpdatedAt, since)
                .select(ListingEntity::getId));
        changed.stream().map(ListingEntity::getId).filter(java.util.Objects::nonNull).forEach(ids::add);

        int count = 0;
        for (Long id : ids) {
            if (trackListing(id)) {
                count++;
            }
        }
        return count;
    }

    /** 索引一个商品：成功即出队，失败留在队列里等下一轮（下轮重新读库，不写旧快照） */
    private boolean trackProduct(Long spuId) {
        if (spuId == null) {
            return false;
        }
        if (indexer.indexProduct(spuId)) {
            pendingProductIds.remove(spuId);
            return true;
        }
        pendingProductIds.add(spuId);
        return false;
    }

    private boolean trackListing(Long listingId) {
        if (listingId == null) {
            return false;
        }
        if (indexer.indexListing(listingId)) {
            pendingListingIds.remove(listingId);
            return true;
        }
        pendingListingIds.add(listingId);
        return false;
    }

    /**
     * 物理清理后立即摘除商品文档（供 {@link SearchDocCleanupListener} 在事务提交后调用）。
     *
     * <p>删除失败进重试队列：此时库行已经没了，增量扫描不可能再发现它，
     * 只有队列会记得「这个 id 还需要删一次」。
     */
    public void deletePurgedProductDoc(Long spuId) {
        if (spuId == null) {
            return;
        }
        if (indexer.deleteDoc(SearchIndexer.TYPE_PRODUCT, spuId)) {
            pendingProductIds.remove(spuId);
        } else {
            pendingProductIds.add(spuId);
        }
    }

    /** 供对账接口读取的最近一次扫描时间（ISO 文本，可能为 null） */
    public Map<String, Object> status() {
        // 刻意用 LinkedHashMap 而不是 Map.of：首轮扫描前 lastSweepAt 就是 null，
        // 而 Map.of 不接受 null 值 —— 那会让对账接口在刚启动时直接 500。
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("bootstrapped", bootstrapped.get());
        status.put("indexReady", indexer.isIndexReady());
        status.put("lastSweepAt", lastSweepAt.get() == null ? null : lastSweepAt.get().toString());
        // 运维判断「ES 抖过之后有没有积压」看这两个数，而不是翻日志
        status.put("pendingProducts", pendingProductIds.size());
        status.put("pendingListings", pendingListingIds.size());
        return status;
    }
}
