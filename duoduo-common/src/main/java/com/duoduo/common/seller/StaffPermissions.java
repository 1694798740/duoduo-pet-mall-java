package com.duoduo.common.seller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F-04 · 店员权限清单的解析 / 序列化 / 判定（全项目唯一实现）。
 *
 * <h2>存储口径</h2>
 * <ul>
 *   <li>{@code NULL} = <b>历史行</b>：F-04 之前邀请的店员没有权限列，
 *       按"全量权限"解释，保证升级当天店员的可用范围不缩小；</li>
 *   <li>{@code ""}（空串）= <b>显式无操作权限</b>：店主可以配置"只能看、不能动"；</li>
 *   <li>逗号分隔 = 精确清单，顺序与 {@link StaffPermission} 声明顺序一致。</li>
 * </ul>
 *
 * <p><b>未知键一律忽略（读）/ 拒绝（写）</b>：读路径要向前兼容（旧版本服务读到
 * 新键不能 500）；写路径必须拒绝拼错的键 —— 否则店主以为开了"发货"，
 * 实际存进去的是个错字，店员被拦时谁也解释不清。
 */
public final class StaffPermissions {

    private StaffPermissions() {
    }

    /** 全量权限（固定顺序）。 */
    public static List<String> all() {
        List<String> keys = new ArrayList<>(StaffPermission.values().length);
        for (StaffPermission permission : StaffPermission.values()) {
            keys.add(permission.name());
        }
        return List.copyOf(keys);
    }

    /**
     * 解析库内值。
     *
     * @param csv {@code null} = 历史行 → 全量；空串 = 无权限；否则按逗号拆分并过滤未知键
     */
    public static List<String> effective(String csv) {
        if (csv == null) {
            return all();
        }
        return parse(csv);
    }

    /** 解析显式清单（{@code null} / 空白 → 空列表，不当作全量）。 */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String key = raw.trim();
            if (!key.isEmpty() && isKnown(key)) {
                wanted.add(key);
            }
        }
        return ordered(wanted);
    }

    /**
     * 规范化店主提交的权限清单。
     *
     * @throws IllegalArgumentException 含未知权限键
     */
    public static List<String> normalize(Collection<String> permissions) {
        Set<String> wanted = new LinkedHashSet<>();
        if (permissions != null) {
            for (String raw : permissions) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String key = raw.trim().toUpperCase();
                if (!isKnown(key)) {
                    throw new IllegalArgumentException("未知权限：" + raw);
                }
                wanted.add(key);
            }
        }
        return ordered(wanted);
    }

    /** 序列化为库内值（{@link #normalize} 之后的结果；空集合落空串）。 */
    public static String serialize(Collection<String> permissions) {
        return String.join(",", normalize(permissions));
    }

    /** 判定清单里是否包含某权限。 */
    public static boolean has(Collection<String> permissions, StaffPermission permission) {
        return permissions != null && permissions.contains(permission.name());
    }

    /** 人读文案：{@code "商品内容编辑、订单发货"}；空清单为 {@code "仅查看"}。 */
    public static String labelOf(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "仅查看";
        }
        List<String> labels = new ArrayList<>(permissions.size());
        for (StaffPermission permission : StaffPermission.values()) {
            if (permissions.contains(permission.name())) {
                labels.add(permission.label());
            }
        }
        return String.join("、", labels);
    }

    private static boolean isKnown(String key) {
        for (StaffPermission permission : StaffPermission.values()) {
            if (permission.name().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 按枚举声明顺序输出，保证同一集合每次序列化结果一致（便于审计 diff）。 */
    private static List<String> ordered(Set<String> wanted) {
        List<String> result = new ArrayList<>(wanted.size());
        for (StaffPermission permission : StaffPermission.values()) {
            if (wanted.contains(permission.name())) {
                result.add(permission.name());
            }
        }
        return List.copyOf(result);
    }
}
