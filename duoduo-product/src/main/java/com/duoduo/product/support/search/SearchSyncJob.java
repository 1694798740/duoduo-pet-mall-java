package com.duoduo.product.support.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引对账 / 补偿扫描（A10）。
 *
 * <p><b>最终一致性的两条路</b>（验收②③）：
 * <ol>
 *   <li>**变更驱动**：周期扫描 {@code updated_at} 晚于上一轮的 SPU / 闲置 / SKU ——
 *       上下架、编辑、库存冻结与释放都推 {@code updated_at}，因此一个扫描周期（默认 15s）内
 *       库存与上下架的变化必然反映到索引；</li>
 *   <li>**全量重建**：启动时与 ES 抖动恢复后全量对齐 —— 进程重启、ES 重启、
 *       写入失败堆积，都靠它一把抹平。</li>
 * </ol>
 *
 * <p>为什么不做「写路径同步双写」：商品/库存的写入口分散（商家端、运营端、
 * 交易域经内部凭据扣减库存），在每条写路径上埋索引调用一定会漏 ——
 * 而漏了是静默的。单点扫描扫的是**数据库事实**，天然不漏；15 秒的延迟
 * 换来的一致性保证是划算的（对检索场景，「最终一致 + 有对账」优于「实时 + 会漏」）。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncJob.class);

    private final SearchIndexer indexer;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final ListingMapper listingMapper;
    private final long sweepIntervalMs;
    private final AtomicReference<LocalDateTime> lastSweepAt = new AtomicReference<>(null);
    private final AtomicBoolean bootstrapped = new AtomicBoolean(false);

    public SearchSyncJob(SearchIndexer indexer,
                         SpuMapper spuMapper,
                         SkuMapper skuMapper,
                         ListingMapper listingMapper,
                         @Value("${duoduo.search.sweep-interval-ms}") long sweepIntervalMs) {
        this.indexer = indexer;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.listingMapper = listingMapper;
        this.sweepIntervalMs = sweepIntervalMs;
    }

    /** 启动即全量对齐一次（应用就绪后异步执行，不阻塞启动） */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread t = new Thread(this::fullSync, "search-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /** 变更扫描：updated_at 晚于上一轮的实体重新入索引；SKU 变动连带其 SPU */
    @Scheduled(fixedDelayString = "${duoduo.search.sweep-interval-ms:15000}",
               initialDelayString = "${duoduo.search.sweep-interval-ms:15000}")
    public void sweep() {
        try {
            LocalDateTime since = lastSweepAt.get();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 首次扫描没有基准点：回看一个周期 + 2 秒，避免刚启动时漏扫；
            // 之后从上一轮起点回退 2 秒（重叠窗口），抵消毫秒截断与时钟偏差造成的漏扫
            LocalDateTime effective = since == null
                    ? now.minusSeconds(sweepIntervalMs / 1000 + 2)
                    : since.minusSeconds(2);
            int count = 0;
            count += reindexChangedSpu(effective);
            count += reindexChangedListings(effective);
            lastSweepAt.set(now);
            if (count > 0) {
                log.info("索引补偿扫描完成 本轮重索引={} 条", count);
            }
        } catch (Exception ex) {
            log.warn("索引补偿扫描失败（下轮重试）: {}", ex.getMessage());
        }
    }

    /** 全量重建：先建/写全量文档，再清孤儿（索引里有、库里没有的 —— 硬删除的唯一清理路径） */
    public synchronized void fullSync() {
        try {
            // 索引不可用就别白扫一遍全表：写完也是失败，还会刷一屏 WARN
            indexer.ensureIndex();
            if (!indexer.isIndexReady()) {
                log.warn("ES 索引不可用，本轮全量对齐跳过（下轮重试）");
                return;
            }
            List<SpuEntity> spus = spuMapper.selectList(null);
            int ok = 0;
            for (SpuEntity spu : spus) {
                if (indexer.indexProduct(spu.getId())) {
                    ok++;
                }
            }
            List<ListingEntity> listings = listingMapper.selectList(null);
            for (ListingEntity l : listings) {
                if (indexer.indexListing(l.getId())) {
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
            bootstrapped.set(true);
            log.info("ES 全量对齐完成 实体总数={} 成功={}", spus.size() + listings.size(), ok);
        } catch (Exception ex) {
            log.warn("ES 全量对齐失败（扫描任务稍后重试）: {}", ex.getMessage());
        }
    }

    /** SKU 库存/状态变动 → 连带重索引其 SPU（物种/生命阶段/有货状态都冗余在 SPU 文档里） */
    private int reindexChangedSpu(LocalDateTime since) {
        List<SkuEntity> changedSkus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .gt(SkuEntity::getUpdatedAt, since)
                .select(SkuEntity::getSpuId));
        int count = 0;
        for (Long spuId : changedSkus.stream().map(SkuEntity::getSpuId).distinct().toList()) {
            if (indexer.indexProduct(spuId)) {
                count++;
            }
        }
        List<SpuEntity> changedSpu = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                .gt(SpuEntity::getUpdatedAt, since)
                .select(SpuEntity::getId));
        for (Long spuId : changedSpu.stream().map(SpuEntity::getId).toList()) {
            if (indexer.indexProduct(spuId)) {
                count++;
            }
        }
        return count;
    }

    private int reindexChangedListings(LocalDateTime since) {
        List<ListingEntity> changed = listingMapper.selectList(new LambdaQueryWrapper<ListingEntity>()
                .gt(ListingEntity::getUpdatedAt, since)
                .select(ListingEntity::getId));
        int count = 0;
        for (Long id : changed.stream().map(ListingEntity::getId).toList()) {
            if (indexer.indexListing(id)) {
                count++;
            }
        }
        return count;
    }

    /** 供对账接口读取的最近一次扫描时间（ISO 文本，可能为 null） */
    public Map<String, Object> status() {
        // 刻意用 LinkedHashMap 而不是 Map.of：首轮扫描前 lastSweepAt 就是 null，
        // 而 Map.of 不接受 null 值 —— 那会让对账接口在刚启动时直接 500。
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("bootstrapped", bootstrapped.get());
        status.put("indexReady", indexer.isIndexReady());
        status.put("lastSweepAt", lastSweepAt.get() == null ? null : lastSweepAt.get().toString());
        return status;
    }
}
