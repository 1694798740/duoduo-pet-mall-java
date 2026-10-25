package com.duoduo.product.support.search;

import java.util.List;

/**
 * 搜索索引相关的领域事件（A-12）。
 *
 * <p>只用于「库行已经消失、增量扫描追不到」的场景：回收站物理清理。
 * 普通的新增 / 编辑 / 上下架 / 软删 / 恢复都有 {@code updated_at} 可扫，
 * 不需要事件 —— 事件越多，漏发一处就是一条静默的不同步路径。
 */
public final class SearchEvents {

    private SearchEvents() {
    }

    /** 商品已被物理删除：索引文档必须同步摘除（失败由重试队列兜底） */
    public record SpuPurged(List<Long> spuIds) {
        public SpuPurged {
            spuIds = spuIds == null ? List.of() : List.copyOf(spuIds);
        }
    }
}
