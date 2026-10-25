package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.entity.SpuImageEntity;
import com.duoduo.product.mapper.SpuImageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 商品图片表同步（A-11）。
 *
 * <p><b>为什么双写</b>：现有列表 / 详情 / 搜索 / 订单快照都按
 * {@code t_spu.main_image} 与 {@code images}(CSV) 读取。一次性切读会牵动
 * 所有读路径（还包含秒级一轮的 ES 增量），风险远大于收益。
 * 因此本表先作为<b>排序与单图审核的权威写侧</b>，CSV 继续双写；
 * 待读路径逐条切过来后，CSV 可以退化为只读兼容列。
 *
 * <p><b>展示口径与 CSV 一致</b>：副图会去掉空项、与主图相同的 URL、重复 URL
 * —— 前台详情本来就这样合并（主图永远排第一），表里若保留重复项，
 * 排序与审核就会出现「两张一模一样的图」。
 *
 * <p><b>审核态跟随商品</b>：商品通过 / 自动放行 → APPROVED，
 * 待审 → PENDING，驳回 / 抽查判违规 → REJECTED。没有单图驳回原因，
 * 就不维护「部分图被拒」这种商品层看不到的中间态。
 */
@Service
public class SpuImageService {

    private final SpuImageMapper imageMapper;
    private final SnowflakeIdGenerator idGenerator;

    public SpuImageService(SpuImageMapper imageMapper, SnowflakeIdGenerator idGenerator) {
        this.imageMapper = imageMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 按 SPU 当前的 mainImage / images / auditStatus 对齐图片表。
     *
     * <p>幂等：形状（类型 + 排序 + URL）不变时只补审核态，不重建行 ——
     * 每次编辑都删了重插会让图片 id 失去稳定性，未来按 id 做「单图审核」
     * 或引用就无从谈起。
     */
    @Transactional(rollbackFor = Exception.class)
    public void sync(SpuEntity spu) {
        if (spu == null || spu.getId() == null) {
            return;
        }
        String audit = auditOf(spu.getAuditStatus());
        List<DesiredImage> desired = desiredOf(spu);
        List<SpuImageEntity> existing = imageMapper.selectList(
                new LambdaQueryWrapper<SpuImageEntity>()
                        .eq(SpuImageEntity::getSpuId, spu.getId()));

        if (sameShape(existing, desired)) {
            boolean auditDiffers = existing.stream()
                    .anyMatch(row -> !audit.equals(row.getAuditStatus()));
            if (auditDiffers) {
                imageMapper.update(null, new LambdaUpdateWrapper<SpuImageEntity>()
                        .eq(SpuImageEntity::getSpuId, spu.getId())
                        .set(SpuImageEntity::getAuditStatus, audit)
                        .set(SpuImageEntity::getUpdatedAt, now()));
            }
            return;
        }

        imageMapper.delete(new LambdaQueryWrapper<SpuImageEntity>()
                .eq(SpuImageEntity::getSpuId, spu.getId()));
        LocalDateTime now = now();
        for (DesiredImage image : desired) {
            SpuImageEntity entity = new SpuImageEntity();
            entity.setId(idGenerator.nextId());
            entity.setSpuId(spu.getId());
            entity.setUrl(image.url());
            entity.setImageType(image.type());
            entity.setSortNo(image.sortNo());
            entity.setAuditStatus(audit);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            imageMapper.insert(entity);
        }
    }

    /**
     * 只改审核态（审核通过 / 驳回 / 重提 / 抽查判违规）。
     *
     * <p>影响 0 行说明该商品还没有图片行（上线前创建、迁移后又没被回填到的极窄窗口），
     * 此时按当前内容补一组，而不是任由「商品已审、图片永远待审」这种错位留下。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyAuditStatus(SpuEntity spu, String auditStatus) {
        if (spu == null || spu.getId() == null) {
            return;
        }
        String target = auditOf(auditStatus);
        int rows = imageMapper.update(null, new LambdaUpdateWrapper<SpuImageEntity>()
                .eq(SpuImageEntity::getSpuId, spu.getId())
                .set(SpuImageEntity::getAuditStatus, target)
                .set(SpuImageEntity::getUpdatedAt, now()));
        if (rows == 0) {
            // sync 读的是 spu.auditStatus：内存实体必须已是最终结论，否则补出来的行会带旧状态
            spu.setAuditStatus(target);
            sync(spu);
        }
    }

    /** 商品被回收站清理任务物理删除时，图片一并清理（同一事务内调用） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBySpuIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        List<Long> ids = spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        imageMapper.delete(new LambdaQueryWrapper<SpuImageEntity>()
                .in(SpuImageEntity::getSpuId, ids));
    }

    // ---------------- 内部 ----------------

    private record DesiredImage(String url, String type, int sortNo) {
    }

    private List<DesiredImage> desiredOf(SpuEntity spu) {
        List<DesiredImage> out = new ArrayList<>();
        String main = trimToNull(spu.getMainImage());
        if (main != null) {
            out.add(new DesiredImage(main, SpuImageEntity.TYPE_MAIN, 0));
        }
        Set<String> seen = new HashSet<>();
        if (main != null) {
            seen.add(main);
        }
        if (spu.getImages() != null) {
            int sort = 0;
            for (String part : spu.getImages().split(",")) {
                String url = part == null ? "" : part.trim();
                if (url.isEmpty() || !seen.add(url)) {
                    continue;
                }
                out.add(new DesiredImage(url, SpuImageEntity.TYPE_GALLERY, ++sort));
            }
        }
        return out;
    }

    private static boolean sameShape(List<SpuImageEntity> existing, List<DesiredImage> desired) {
        if (existing.size() != desired.size()) {
            return false;
        }
        Set<String> rows = new HashSet<>();
        for (SpuImageEntity row : existing) {
            rows.add(shapeKey(row.getImageType(), row.getSortNo(), row.getUrl()));
        }
        Set<String> want = new HashSet<>();
        for (DesiredImage image : desired) {
            want.add(shapeKey(image.type(), image.sortNo(), image.url()));
        }
        return rows.equals(want);
    }

    private static String shapeKey(String type, Integer sort, String url) {
        return type + "#" + sort + "#" + url;
    }

    private static String auditOf(String auditStatus) {
        return auditStatus == null || auditStatus.isBlank()
                ? SpuImageEntity.AUDIT_APPROVED
                : auditStatus.trim().toUpperCase();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
