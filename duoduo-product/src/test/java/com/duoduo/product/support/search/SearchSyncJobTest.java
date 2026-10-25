package com.duoduo.product.support.search;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 搜索索引自动同步（A-12）单测。
 *
 * <p>守的是三类「接口全绿、检索悄悄不对」的问题：
 * <ul>
 *   <li><b>失败即丢失</b>：一条写失败后游标照常前进，下轮只扫新变更 ——
 *       那条商品从此永远不会进索引，直到进程重启；</li>
 *   <li><b>启动只试一次</b>：ES 比商品服务晚启动时，全量对齐失败后没有任何重试；</li>
 *   <li><b>物理清理留尸</b>：库行删了、文档还在，而删除动作已经不会再推 updated_at。</li>
 * </ul>
 */
class SearchSyncJobTest {

    private final SearchIndexer indexer = mock(SearchIndexer.class);
    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final SkuMapper skuMapper = mock(SkuMapper.class);
    private final ListingMapper listingMapper = mock(ListingMapper.class);
    private final SchedLeaderLock leaderLock = mock(SchedLeaderLock.class);
    private final SearchSyncJob job =
            new SearchSyncJob(indexer, spuMapper, skuMapper, listingMapper, leaderLock, 3000);

    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SpuEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkuEntity.class);
        TableInfoHelper.initTableInfo(assistant, ListingEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(leaderLock.tryAcquire(anyString(), anyLong())).thenReturn(true);
        when(indexer.isIndexReady()).thenReturn(true);
        when(indexer.indexProduct(anyLong())).thenReturn(true);
        when(indexer.indexListing(anyLong())).thenReturn(true);
        when(indexer.deleteDoc(anyString(), anyLong())).thenReturn(true);
        // null = 全量对齐的「所有行」；带 wrapper = 增量窗口。用有类型 any 让两者互不覆盖：
        // Mockito 的无参 any() 会把 null 也算进去，两个 stub 就会互相踩。
        when(spuMapper.selectList(isNull())).thenReturn(List.of());
        when(listingMapper.selectList(isNull())).thenReturn(List.of());
        when(spuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(skuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(listingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    /** 用空库完成一次全量对齐，让后续 sweep 走增量分支（否则每轮都会先 fullSync） */
    private void bootstrapEmpty() {
        job.fullSync();
        assertThat(job.status()).containsEntry("bootstrapped", true);
        // 基准点必须随全量对齐一起建立：否则首轮增量只回看 5 秒，
        // 选主等待 / 全量扫描期间发生的变更会永久漏扫（A-12 冒烟实测踩到）。
        assertThat(job.status().get("lastSweepAt")).isNotNull();
    }

    private static SpuEntity spu(Long id) {
        SpuEntity spu = new SpuEntity();
        spu.setId(id);
        spu.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return spu;
    }

    private static SkuEntity sku(Long spuId) {
        SkuEntity sku = new SkuEntity();
        sku.setSpuId(spuId);
        sku.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return sku;
    }

    @Test
    void 变更商品与SKU指向的SPU按id去重后各索引一次() {
        when(skuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(100L), sku(100L), sku(200L)));
        when(spuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(spu(200L), spu(300L)));
        bootstrapEmpty();

        job.sweep();

        verify(indexer).indexProduct(100L);
        verify(indexer).indexProduct(200L);
        verify(indexer).indexProduct(300L);
        verify(indexer, times(3)).indexProduct(anyLong());
    }

    @Test
    void 单条索引失败进入待重试_下一轮成功后移出队列() {
        // 第一轮能扫到该商品，第二轮窗口里已经没有它 —— 只能靠 pending 队列补
        when(spuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(spu(100L)), List.of());
        when(indexer.indexProduct(100L)).thenReturn(false, true);
        bootstrapEmpty();

        job.sweep();
        assertThat(job.status()).containsEntry("pendingProducts", 1);

        job.sweep();
        assertThat(job.status()).containsEntry("pendingProducts", 0);
        verify(indexer, times(2)).indexProduct(100L);
    }

    @Test
    void 启动时索引不可用_下一轮继续尝试全量对齐而不是永远放弃() {
        when(spuMapper.selectList(isNull())).thenReturn(List.of(spu(300L)));
        when(indexer.isIndexReady()).thenReturn(false, true);

        job.sweep();
        assertThat(job.status()).containsEntry("bootstrapped", false);
        verify(indexer, times(1)).ensureIndex();

        job.sweep();
        assertThat(job.status()).containsEntry("bootstrapped", true);
        verify(indexer, times(2)).ensureIndex();
        verify(indexer).indexProduct(300L);
    }

    @Test
    void 选主失败时本轮不触碰索引与数据库() {
        when(leaderLock.tryAcquire(anyString(), anyLong())).thenReturn(false);

        job.sweep();

        verifyNoInteractions(indexer, spuMapper, skuMapper, listingMapper);
    }

    @Test
    void 物理清理删文档失败进重试队列_下一轮扫描补删() {
        bootstrapEmpty();
        when(indexer.deleteDoc(SearchIndexer.TYPE_PRODUCT, 900L)).thenReturn(false);

        job.deletePurgedProductDoc(900L);
        assertThat(job.status()).containsEntry("pendingProducts", 1);
        verify(indexer).deleteDoc(SearchIndexer.TYPE_PRODUCT, 900L);

        // 下一轮 indexProduct 会重新读库：行已不存在 → 转为删除文档
        job.sweep();
        assertThat(job.status()).containsEntry("pendingProducts", 0);
        verify(indexer).indexProduct(900L);
    }

    @Test
    void 物理清理删文档成功时不留重试项() {
        bootstrapEmpty();

        job.deletePurgedProductDoc(901L);

        assertThat(job.status()).containsEntry("pendingProducts", 0);
        verify(indexer).deleteDoc(SearchIndexer.TYPE_PRODUCT, 901L);
        verify(indexer, never()).indexProduct(901L);
    }

    @Test
    void 检测到索引被重建后触发全量对齐() {
        bootstrapEmpty();
        clearInvocations(spuMapper, indexer);
        when(indexer.isIndexRecreated()).thenReturn(true);
        when(spuMapper.selectList(isNull())).thenReturn(List.of(spu(400L)));

        job.sweep();

        verify(spuMapper).selectList(isNull());
        verify(indexer).indexProduct(400L);
    }

    @Test
    void 重建后拿不到全量租约_本轮不做全量且重建标记不被消费() {
        bootstrapEmpty();
        clearInvocations(indexer, spuMapper);
        when(indexer.isIndexRecreated()).thenReturn(true);
        // 短租约拿到、延长租约失败：说明别的实例在接管
        when(leaderLock.tryAcquire(anyString(), anyLong())).thenReturn(true, false);

        job.sweep();

        verify(indexer).ensureIndex();
        verify(indexer, never()).consumeIndexRecreated();
        verify(spuMapper, never()).selectList(isNull());
    }

    @Test
    void 每轮先校验索引形态再写增量_不在自动建出的错误索引上继续写() {
        bootstrapEmpty();
        clearInvocations(indexer);
        when(spuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(spu(500L)));

        job.sweep();

        InOrder order = inOrder(indexer);
        order.verify(indexer).ensureIndex();
        order.verify(indexer).indexProduct(500L);
    }

    @Test
    void 状态上报包含待重试数量_供运维判断ES抖动是否积压() {
        assertThat(job.status())
                .containsKeys("bootstrapped", "indexReady", "lastSweepAt",
                        "pendingProducts", "pendingListings");
    }
}
