package com.duoduo.product.support.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 回收站物理清理后的索引摘除（A-12）。
 *
 * <p><b>为什么必须是 AFTER_COMMIT</b>：清理在事务里做条件删除，可能因为并发恢复
 * 只删掉其中一部分。若在事务内就删 ES 文档，一旦事务回滚，库里的商品还在、
 * 索引却已经被摘掉；而它的 {@code updated_at} 没变，增量扫描也不会自动补回。
 *
 * <p>失败不抛：索引是旁路，清理的主链路不能因为 ES 抖动而失败；
 * 删除失败的 id 会进 {@link SearchSyncJob} 的重试队列。
 */
@Component
@ConditionalOnProperty(name = "duoduo.search.enabled", havingValue = "true")
public class SearchDocCleanupListener {

    private final SearchSyncJob syncJob;

    public SearchDocCleanupListener(SearchSyncJob syncJob) {
        this.syncJob = syncJob;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSpuPurged(SearchEvents.SpuPurged event) {
        // 逐条走同一个入口：成功出队、失败入队，调用方不需要知道索引是否可用
        event.spuIds().forEach(syncJob::deletePurgedProductDoc);
    }
}
