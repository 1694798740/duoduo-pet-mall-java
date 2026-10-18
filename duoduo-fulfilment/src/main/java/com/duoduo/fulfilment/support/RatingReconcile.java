package com.duoduo.fulfilment.support;

import com.duoduo.fulfilment.client.ProductClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分对账的纯判据（2026-10-18）。
 *
 * <h2>它解决的是什么问题</h2>
 * 首页卡片读 {@code t_spu.rating / rating_count}（冗余列），详情页读评价库的真实聚合。
 * 冗余列只有一个写入口（评价提交后回写），于是存在两种静默漂移：
 * <ul>
 *   <li><b>历史假数据</b>：建库时写入的 4.0 分 / 4 条评价，从来没有真实评价与之对应 ——
 *       首页显示「4.0 分 · 4 条评价」，详情页「暂无评分」；</li>
 *   <li><b>回写失败</b>：评价提交成功、回写是 best-effort，失败后冗余列停在旧值。</li>
 * </ul>
 *
 * <h2>判据：两个方向都要扫</h2>
 * <ul>
 *   <li>商品域「说有评分」的候选 → 评价库没有 ⇒ 复位 0（清掉假数据）；</li>
 *   <li>评价库「有评价」但商品域不知道 ⇒ 补推真实聚合（补回写失败的漏网）。</li>
 * </ul>
 * 只扫一个方向都会留残余：只扫候选清不掉「有评价但列是 0」，只扫评价清不掉假数据。
 *
 * <p>本类刻意不碰数据库/网络 —— 纯函数才能把四个方向（假数据、漏网、正常、空）
 * 一条不落地写成单测。
 */
public final class RatingReconcile {

    private RatingReconcile() {
    }

    /** 商品域自报的候选：本域认为这个 SPU 有 ratingCount 条评价 */
    public record Candidate(long spuId, int ratingCount) {
    }

    /** 评价库的真实聚合（只含审核通过的评价） */
    public record Aggregate(long spuId, double avgRating, int count) {
    }

    /**
     * 生成补丁。返回值按 spuId 去重，顺序稳定（先候选、后补推），便于日志与断言比对。
     */
    public static List<ProductClient.RatingPatch> plan(List<Candidate> candidates, List<Aggregate> aggregates) {
        Map<Long, Aggregate> bySpu = new HashMap<>();
        if (aggregates != null) {
            for (Aggregate a : aggregates) {
                if (a != null) {
                    bySpu.put(a.spuId(), a);
                }
            }
        }

        Map<Long, ProductClient.RatingPatch> patches = new LinkedHashMap<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c == null) {
                    continue;
                }
                Aggregate a = bySpu.get(c.spuId());
                // 候选里没有对应评价 ⇒ 这一列是假数据（或回写失败后残留），必须复位
                patches.put(c.spuId(), a == null
                        ? new ProductClient.RatingPatch(c.spuId(), 0.0, 0)
                        : new ProductClient.RatingPatch(c.spuId(), a.avgRating(), a.count()));
            }
        }
        if (aggregates != null) {
            for (Aggregate a : aggregates) {
                if (a == null) {
                    continue;
                }
                // 有真实评价、商品域却没这条候选（rating_count=0）⇒ 补推
                patches.putIfAbsent(a.spuId(), new ProductClient.RatingPatch(a.spuId(), a.avgRating(), a.count()));
            }
        }
        return new ArrayList<>(patches.values());
    }
}
