package com.duoduo.product.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.support.search.SearchIndexer;
import com.duoduo.product.support.search.SearchSyncJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索对账（A10，运营）：库内事实与 ES 索引的差异报告 + 重建入口。
 *
 * <p>补偿扫描（{@code SearchSyncJob}）负责「自动追平」，这里是「人工核对 + 一键重建」：
 * 当怀疑索引与库不一致（搜索结果缺货 / 下架商品仍被搜到）时，先看报告定位差异，
 * 再 {@code POST ?fix=true} 触发全量重建 —— 两步分开，重建是重操作，不该和只读报告混在一个开关里。
 */
@RestController
@RequestMapping("/api/admin/search")
// 条件只认开关：不叠 @ConditionalOnBean —— 那个注解依赖 bean 注册顺序，
// 在普通组件扫描下不可靠，可能出现「开关开了但对账接口 404」的随机故障。
// 开关打开时下面的依赖（SearchIndexer / SearchSyncJob / ElasticsearchClient）必然存在。
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchAdminController {

    /** 差异报告：db 侧为事实源，index 侧为 ES 现状 */
    public record ReconcileReport(
            String docType,
            long dbCount,
            long indexCount,
            List<Long> missingInIndex,
            List<Long> extraInIndex,
            List<Long> staleInIndex,
            Map<String, Object> syncStatus
    ) {
    }

    private final SearchIndexer indexer;
    private final SearchSyncJob syncJob;
    private final com.duoduo.product.mapper.SpuMapper spuMapper;
    private final com.duoduo.product.mapper.ListingMapper listingMapper;
    private final ElasticsearchClient es;

    @Autowired
    public SearchAdminController(SearchIndexer indexer,
                                 SearchSyncJob syncJob,
                                 com.duoduo.product.mapper.SpuMapper spuMapper,
                                 com.duoduo.product.mapper.ListingMapper listingMapper,
                                 ElasticsearchClient es) {
        this.indexer = indexer;
        this.syncJob = syncJob;
        this.spuMapper = spuMapper;
        this.listingMapper = listingMapper;
        this.es = es;
    }

    @GetMapping("/reconcile")
    public ApiResponse<ReconcileReport> reconcile(@RequestParam(defaultValue = "product") String type) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(doReconcile(type));
    }

    /** 全量重建（重操作）：重建完成时间取决于目录规模；重建期间检索可能命中旧索引，属预期 */
    @PostMapping("/reindex")
    @Audited(action = AuditAction.SEARCH_REINDEX)
    public ApiResponse<Map<String, Object>> reindex() {
        OperatorGuard.requireOperator();
        syncJob.fullSync();
        return ApiResponse.ok(Map.of(
                "reindexed", true,
                "syncStatus", syncJob.status()
        ));
    }

    private ReconcileReport doReconcile(String type) {
        String docType = "listing".equalsIgnoreCase(type) ? SearchIndexer.TYPE_LISTING : SearchIndexer.TYPE_PRODUCT;
        Map<Long, Long> dbFacts = new LinkedHashMap<>();
        if (SearchIndexer.TYPE_LISTING.equals(docType)) {
            // A-12：回收站条目**不算缺失**。A-09 起软删商品会被索引显式摘除，
            // 若仍把已删行当成「库里有、索引没有」，每删一件商品都会在报告里
            // 多一条假缺失，运营再也看不出真正的同步故障。
            // 反过来，软删后仍留在索引里的文档会落进 extraInIndex —— 那才是要清的东西。
            listingMapper.selectList(new LambdaQueryWrapper<ListingEntity>()
                            .isNull(ListingEntity::getDeletedAt))
                    .forEach(l -> dbFacts.put(l.getId(), l.getUpdatedAt() == null ? 0L
                            : l.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
        } else {
            spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                            .isNull(SpuEntity::getDeletedAt))
                    .forEach(s -> dbFacts.put(s.getId(), s.getUpdatedAt() == null ? 0L
                            : s.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
        }
        Map<Long, Long> indexDocs = indexer.listDocs(docType);

        List<Long> missing = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        for (var e : dbFacts.entrySet()) {
            Long idxAt = indexDocs.get(e.getKey());
            if (idxAt == null) {
                missing.add(e.getKey());
            } else if (!idxAt.equals(e.getValue())) {
                stale.add(e.getKey());
            }
        }
        List<Long> extra = indexDocs.keySet().stream()
                .filter(id -> !dbFacts.containsKey(id)).toList();

        long esCount = -1;
        try {
            esCount = es.count(c -> c.index(SearchIndexer.INDEX)
                    .query(q -> q.term(t -> t.field("docType").value(docType)))).count();
        } catch (Exception ignored) {
            // ES 不在场时报告仍可给出「库 vs 索引快照」的差异，esCount 置 -1 表示不可用
        }
        return new ReconcileReport(docType, dbFacts.size(), esCount, missing, extra, stale, syncJob.status());
    }
}
