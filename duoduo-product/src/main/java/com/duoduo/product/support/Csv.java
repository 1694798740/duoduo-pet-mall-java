package com.duoduo.product.support;

import java.util.Arrays;
import java.util.List;

/**
 * 逗号分隔串与列表之间的转换。
 *
 * <p>多值属性在库里是逗号串（省表、省 JOIN），但对外一律以数组暴露。
 * 转换规则集中在这里，避免各处 split 行为不一致（是否 trim、是否丢空串）。
 */
public final class Csv {

    private Csv() {
    }

    public static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static boolean contains(String csv, String value) {
        return value != null && split(csv).contains(value);
    }
}
