package com.duoduo.common.api;

import java.util.List;

/**
 * 分页结果。
 * 约定：page 从 1 开始；pageSize 上限由各服务的边界校验控制（默认 100）。
 */
public record PageResult<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        int pageCount
) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
        int pageCount = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PageResult<>(items, total, page, pageSize, pageCount);
    }

    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize, 0);
    }
}
