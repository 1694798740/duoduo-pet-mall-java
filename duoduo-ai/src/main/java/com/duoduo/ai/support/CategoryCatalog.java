package com.duoduo.ai.support;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.CategoryBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 类目目录（第五十七轮）：从商品域取真实类目，做「用户话 → 品类」的识别。
 *
 * <p><b>为什么不能只靠类目名字符串匹配</b>：库里的一级类目叫「宠物主粮」，
 * 而用户说的是「猫粮」「狗粮」「口粮」—— 直接拿类目名去用户话里找子串，
 * 命中率接近零，品类约束就等于没做。所以要一份**显式别名表**。
 * 别名表按**类目名**索引（不是 id）：类目重建 id 会变，名字不会。
 *
 * <p><b>别名表是要维护的</b>：新增类目时必须补别名，否则那个类目只能被
 * 「用户恰好说出类目全名」命中。这是明确的维护成本，写在这里以免后来者以为它自动生效。
 * 兜底策略是：类目名本身也能匹配（「宠物零食」直接命中），所以漏配别名只是**召回变窄**，
 * 不会出错推荐。
 *
 * <p><b>缓存</b>：类目表是低频变更数据，取一次缓存 5 分钟 —— 每次对话都跨域拉一次
 * 会让 AI 的响应时间被商品域拖着走，而类目变更的时效性完全不需要秒级。
 * 取不到时返回空表并降级（不阻断对话）：品类约束只是「锦上添花」，
 * 没有它对话照样能答，只是范围宽一点。取不到时**不做品类过滤**是安全的 ——
 * 与预算约束不同，品类猜错会导致「什么都搜不到」，比不过滤更糟。
 */
@Component
public class CategoryCatalog {

    private static final Logger log = LoggerFactory.getLogger(CategoryCatalog.class);

    /** 缓存有效期：类目表低频变更，5 分钟足够 */
    private static final long TTL_MS = 5 * 60 * 1000L;

    /**
     * 品类口语别名 → 类目名。列表按「越长越具体优先」排序匹配，
     * 避免「粮」这种单字把「零食」也吃掉（「零食」里含「食」不含「粮」，但
     * 「粮」会把「猫粮」之外的东西也扯进来，所以顺序与粒度都要留意）。
     */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        // 宠物主粮
        for (String w : List.of("猫粮", "狗粮", "主粮", "口粮", "幼猫粮", "幼犬粮", "全价粮", "成猫粮", "成犬粮")) {
            ALIASES.put(w, "宠物主粮");
        }
        // 宠物零食
        for (String w : List.of("零食", "罐头", "冻干", "猫条", "肉干", "磨牙棒", "洁齿", "小饼干")) {
            ALIASES.put(w, "宠物零食");
        }
        // 宠物用品
        for (String w : List.of("猫砂", "狗窝", "猫窝", "猫抓板", "牵引绳", "食盆", "水碗", "玩具", "用品", "尿垫", "厕所")) {
            ALIASES.put(w, "宠物用品");
        }
        // 健康医疗
        for (String w : List.of("驱虫", "疫苗", "益生菌", "营养膏", "药", "保健品", "皮肤", "关节", "医疗", "滴耳")) {
            ALIASES.put(w, "健康医疗");
        }
        // 智能设备
        for (String w : List.of("智能", "喂食器", "饮水机", "摄像头", "自动", "设备")) {
            ALIASES.put(w, "智能设备");
        }
        // 活体与领养
        for (String w : List.of("领养", "活体", "小猫", "小狗", "幼猫", "幼犬")) {
            ALIASES.put(w, "活体与领养");
        }
    }

    private final ProductSearchClient productSearchClient;

    private volatile List<CategoryBrief> cached = List.of();
    private volatile long cachedAt = 0L;

    public CategoryCatalog(ProductSearchClient productSearchClient) {
        this.productSearchClient = productSearchClient;
    }

    /** 全量类目（ACTIVE），带 5 分钟缓存；取不到返回空表并**不**抛错 */
    public List<CategoryBrief> all() {
        long now = System.currentTimeMillis();
        if (!cached.isEmpty() && now - cachedAt < TTL_MS) {
            return cached;
        }
        try {
            var resp = productSearchClient.categories();
            List<CategoryBrief> list = resp == null ? null : resp.data();
            if (list != null && !list.isEmpty()) {
                cached = List.copyOf(list);
                cachedAt = now;
            }
            return cached;
        } catch (Exception e) {
            // 类目取不到不阻断对话：品类约束缺席只是范围宽一点，比把对话打挂好
            log.warn("取类目表失败，本轮不做品类约束: {}", e.getMessage());
            return cached;
        }
    }

    /**
     * 从用户话里认出品类。
     *
     * <p>两条路：① 别名表（覆盖「猫粮 → 宠物主粮」这类口语）；② 类目名直接出现。
     * 匹配到的类目**去重后返回**（一句「猫粮猫砂都要」会命中两个品类，
     * 这时按 OR 处理 —— 用户同时要两样东西，任一符合都算符合）。
     */
    public List<CategoryBrief> match(String message) {
        String text = message == null ? "" : message;
        if (text.isBlank()) {
            return List.of();
        }
        Map<Long, CategoryBrief> hit = new LinkedHashMap<>();
        for (CategoryBrief c : all()) {
            if (c.name() == null || c.name().isBlank()) {
                continue;
            }
            // ① 类目名直接出现（用户可能就照着类目名说）
            if (text.contains(c.name())) {
                hit.putIfAbsent(c.id(), c);
                continue;
            }
            // ② 别名命中
            for (Map.Entry<String, String> e : ALIASES.entrySet()) {
                if (text.contains(e.getKey()) && e.getValue().equals(c.name())) {
                    hit.putIfAbsent(c.id(), c);
                    break;
                }
            }
        }
        return new ArrayList<>(hit.values());
    }

    /**
     * 澄清问题的可点选项：取人类最容易对上号的类目。
     *
     * <p>只取 {@code depth == 1}（一级类目）—— 二级类目作为「你想买什么」的选项太细，
     * 用户看到「猫粮/狗粮/猫砂」会更愿意点，看到一堆三级类目只会关掉页面。
     */
    public List<CategoryBrief> clarifyOptions(int limit) {
        int size = limit <= 0 ? 4 : limit;
        List<CategoryBrief> result = new ArrayList<>();
        for (CategoryBrief c : all()) {
            if (result.size() >= size) {
                break;
            }
            if (c.name() == null || c.name().isBlank()) {
                continue;
            }
            if (c.depth() > 1) {
                continue;
            }
            result.add(c);
        }
        return result;
    }

    /** 供测试断言与排障：别名表规模（验证「别名确实配了」而不是空表） */
    public int aliasCount() {
        return (int) ALIASES.keySet().stream().filter(Objects::nonNull).count();
    }
}
