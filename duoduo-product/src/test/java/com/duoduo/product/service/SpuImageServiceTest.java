package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.entity.SpuImageEntity;
import com.duoduo.product.mapper.SpuImageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品图片表同步（A-11）单测。
 *
 * <p>守的是「错了也不报错」的三类问题：
 * <ul>
 *   <li>副图把主图 / 重复 URL 又写一遍 —— 前台会展示两张一样的图；</li>
 *   <li>每次编辑都整组重建 —— 图片 id 不稳定，未来按 id 做单图审核就失去锚点；</li>
 *   <li>审核态没跟上商品 —— 商品已通过、图片却永远待审，两边口径对不上。</li>
 * </ul>
 */
class SpuImageServiceTest {

    private final SpuImageMapper imageMapper = mock(SpuImageMapper.class);
    private final SpuImageService service = new SpuImageService(
            imageMapper, new SnowflakeIdGenerator(1, 1));

    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SpuImageEntity.class);
    }

    private static SpuEntity spu(String mainImage, String images, String auditStatus) {
        SpuEntity spu = new SpuEntity();
        spu.setId(9001L);
        spu.setMainImage(mainImage);
        spu.setImages(images);
        spu.setAuditStatus(auditStatus);
        return spu;
    }

    private static SpuImageEntity row(String url, String type, int sort, String audit) {
        SpuImageEntity row = new SpuImageEntity();
        row.setSpuId(9001L);
        row.setUrl(url);
        row.setImageType(type);
        row.setSortNo(sort);
        row.setAuditStatus(audit);
        return row;
    }

    @Test
    void 首次同步_主图MAIN_副图去主图去重并从1连续排序() {
        // 历史 CSV 常见写法：主图重复放在第一项，且中间有空项 / 重复项
        service.sync(spu("/img/a.jpg", "/img/a.jpg, /img/b.jpg,/img/b.jpg,,/img/c.jpg", "PENDING"));

        verify(imageMapper).delete(any());
        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper, times(3)).insert(captor.capture());
        List<SpuImageEntity> rows = captor.getAllValues();

        assertThat(rows).extracting(SpuImageEntity::getUrl)
                .containsExactly("/img/a.jpg", "/img/b.jpg", "/img/c.jpg");
        assertThat(rows).extracting(SpuImageEntity::getImageType)
                .containsExactly("MAIN", "GALLERY", "GALLERY");
        assertThat(rows).extracting(SpuImageEntity::getSortNo)
                .containsExactly(0, 1, 2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getSpuId()).isEqualTo(9001L);
            assertThat(r.getAuditStatus()).isEqualTo("PENDING");
            assertThat(r.getCreatedAt()).isNotNull();
            assertThat(r.getUpdatedAt()).isNotNull();
            assertThat(r.getId()).isNotNull();
        });
    }

    @Test
    void 形状不变时只补审核态_不删不重建() {
        when(imageMapper.selectList(any())).thenReturn(List.of(
                row("/img/a.jpg", "MAIN", 0, "PENDING"),
                row("/img/b.jpg", "GALLERY", 1, "PENDING")));

        service.sync(spu("/img/a.jpg", "/img/b.jpg", "APPROVED"));

        verify(imageMapper).update(any(), any());
        verify(imageMapper, never()).delete(any());
        verify(imageMapper, never()).insert(any());
    }

    @Test
    void 审核态已一致时零写入() {
        when(imageMapper.selectList(any())).thenReturn(List.of(
                row("/img/a.jpg", "MAIN", 0, "APPROVED"),
                row("/img/b.jpg", "GALLERY", 1, "APPROVED")));

        service.sync(spu("/img/a.jpg", "/img/b.jpg", "APPROVED"));

        verify(imageMapper, never()).update(any(), any());
        verify(imageMapper, never()).delete(any());
        verify(imageMapper, never()).insert(any());
    }

    @Test
    void 副图顺序变化_整组重建() {
        when(imageMapper.selectList(any())).thenReturn(List.of(
                row("/img/a.jpg", "MAIN", 0, "APPROVED"),
                row("/img/c.jpg", "GALLERY", 1, "APPROVED"),
                row("/img/b.jpg", "GALLERY", 2, "APPROVED")));

        service.sync(spu("/img/a.jpg", "/img/b.jpg,/img/c.jpg", "APPROVED"));

        verify(imageMapper).delete(any());
        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SpuImageEntity::getUrl)
                .containsExactly("/img/a.jpg", "/img/b.jpg", "/img/c.jpg");
    }

    @Test
    void 无主图时只写副图_不伪造MAIN行() {
        service.sync(spu(null, "/img/b.jpg", "APPROVED"));

        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper).insert(captor.capture());
        assertThat(captor.getValue().getImageType()).isEqualTo("GALLERY");
        assertThat(captor.getValue().getSortNo()).isEqualTo(1);
    }

    @Test
    void 审核态为空按已通过处理() {
        service.sync(spu("/img/a.jpg", null, null));

        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper).insert(captor.capture());
        assertThat(captor.getValue().getAuditStatus()).isEqualTo("APPROVED");
    }

    @Test
    void 审核结论变化_已有行只更新状态() {
        when(imageMapper.update(any(), any())).thenReturn(2);

        service.applyAuditStatus(spu("/img/a.jpg", "/img/b.jpg", "REJECTED"), "REJECTED");

        verify(imageMapper).update(any(), any());
        verify(imageMapper, never()).delete(any());
        verify(imageMapper, never()).insert(any());
    }

    @Test
    void 审核时图片行缺失_按当前内容补一组并带最终结论() {
        // 上线前创建、迁移后未被回填的极窄窗口：更新 0 行 → 回退同步
        when(imageMapper.update(any(), any())).thenReturn(0);
        SpuEntity spu = spu("/img/a.jpg", "/img/b.jpg", "PENDING");

        service.applyAuditStatus(spu, "REJECTED");

        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r ->
                assertThat(r.getAuditStatus()).isEqualTo("REJECTED"));
    }

    @Test
    void 清理时按去重后的SPU批量删除图片() {
        service.deleteBySpuIds(List.of(9001L, 9001L, 9002L));

        verify(imageMapper).delete(any());
    }

    @Test
    void 清理空集合不访问数据库() {
        service.deleteBySpuIds(List.of());

        verifyNoInteractions(imageMapper);
    }

    @Test
    void 同步时间使用UTC() {
        service.sync(spu("/img/a.jpg", null, "APPROVED"));

        ArgumentCaptor<SpuImageEntity> captor = ArgumentCaptor.forClass(SpuImageEntity.class);
        verify(imageMapper).insert(captor.capture());
        LocalDateTime created = captor.getValue().getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(created).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }
}
