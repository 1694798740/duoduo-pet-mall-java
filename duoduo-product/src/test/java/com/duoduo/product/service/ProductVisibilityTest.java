package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.ProductQuery;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.CatalogLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>E14 · 公开可见性收口</b>的反向验证：未过审商品在任何前台路径上都拿不到。
 *
 * <h2>为什么这类缺陷必须专门写一份测试</h2>
 * 「待审核商品从前台漏出来」不会报错、不会 5xx、不影响其它页面 —— 它只会让一件
 * <b>平台还没放行的货</b>被买家看到并下单。而且它的成因往往是<b>新增了一条读路径</b>：
 * 半年后有人加一个「猜你喜欢」接口，忘了带上审核条件，就漏了。
 *
 * <p>所以本测试的写法是「往库里塞一条 PENDING 商品，然后逐条路径断言它查不到」：
 * 新增路径若忘了收口，只要补一条断言就会红；已经收口的方法则成为回归锚点。
 *
 * <h2>为什么部分断言看的是查询条件而不是查询结果</h2>
 * 单测里没有 MyBatis 容器，{@code LambdaQueryWrapper} 的<b>列名</b>解析依赖运行期
 * {@code TableInfo} 初始化，直接取 {@code getSqlSegment()} 不可靠。
 * 但「条件带上去了没有」有一个同样确定、且不依赖容器的事实：
 * <b>参数值</b> —— 过滤条件一定把常量 {@code "APPROVED"} / {@code "ON_SALE"} 压进参数表。
 * 所以这里断言参数值集合，配合 {@link #未过审商品详情一律404()}
 * 与 {@link #未过审商品在采购视图里不可售()} 这两条纯行为断言，形成交叉验证。
 */
class ProductVisibilityTest {

    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final SkuMapper skuMapper = mock(SkuMapper.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final CatalogLookup catalogLookup = mock(CatalogLookup.class);
    private final BannedWordGuard bannedWordGuard = mock(BannedWordGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.duoduo.product.support.search.EsSearchEngine> esProvider =
            mock(ObjectProvider.class);

    private final ProductService service = new ProductService(spuMapper, skuMapper, categoryService,
            catalogLookup, bannedWordGuard, objectMapper,
            org.mockito.Mockito.mock(com.duoduo.product.client.StoreNameClient.class), esProvider,
            mock(ProductStatService.class));

    /**
     * 初始化 MyBatis-Plus 的实体元数据缓存。
     *
     * <p><b>不加这一步，任何构造 {@code LambdaQueryWrapper} 的代码路径都会抛
     * 「MybatisPlus can not find lambda cache for this entity」</b> ——
     * 因为 {@code eq(SpuEntity::getAuditStatus, ...)} 要把方法引用解析成列名，
     * 而列名来自运行期注册的 {@code TableInfo}（平时由 Spring + MyBatis 启动时建好）。
     * 纯 Mockito 单测里没有容器，必须手工注册一次。
     *
     * <p>这个坑很值得记一笔：它让「测试写不出来」伪装成「代码有问题」——
     * 而实际上生产代码是对的，只是测试环境缺了一块运行期元数据。
     */
    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SpuEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkuEntity.class);
    }

    private ProductVisibilityTest() {
        // ES 不启用 → 检索一律走 MySQL 权威路径（本测试要验证的正是那条路径）
        when(esProvider.getIfAvailable()).thenReturn(null);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        // ⚠️ 必须用**可变** Map：商品可以不填品牌（brandId=null），
        // 而 Map.of() 这类不可变实现对 null key 会直接抛 NPE —— 用它打桩会让
        // 「没填品牌的商品详情」这一正常场景在测试里炸掉，看起来像生产代码的缺陷。
        // 真实的 CatalogLookup 返回的是 HashMap，允许 null key。
        when(catalogLookup.categoryNames(any())).thenReturn(new HashMap<>());
        when(catalogLookup.brandNames(any())).thenReturn(new HashMap<>());
        when(categoryService.expandCategoryIds(any())).thenReturn(List.of(500L));
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(spuMapper.selectList(any())).thenReturn(List.of());
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of());
    }

    private static SpuEntity spu(String auditStatus, String status) {
        SpuEntity spu = new SpuEntity();
        spu.setId(9001L);
        spu.setTitle("测试商品");
        // 类目必须给：不可变 Map（Map.of()）对 null key 会直接抛 NPE
        spu.setCategoryId(500L);
        spu.setSupplyType("merchant");
        spu.setStatus(status);
        spu.setAuditStatus(auditStatus);
        spu.setSellerId(101L);
        spu.setMinPrice(1000L);
        spu.setMaxPrice(2000L);
        spu.setSalesCount(0);
        return spu;
    }

    private static ProductQuery browse() {
        return new ProductQuery(null, null, null, null, null, null, null, null, false, "default", 1, 20);
    }

    /**
     * 断言本用例里**每一次**给 mapper 的查询都带着 `audit_status` 条件。
     *
     * <p>用 {@code allSatisfy} 而不是只检查最后一次：像「首页热门 + 最新」这种一次调用两条路径的用例，
     * 只看最后一次会漏掉「前一条路径忘了收口」—— 而那正是这类缺陷最常见的形态。
     *
     * <p>两个实现细节值得记：
     * <ol>
     *   <li>{@code getSqlSegment()} 会<b>触发</b>片段生成（MyBatis-Plus 是懒生成的），
     *       参数表也是在这一刻才被填充 —— 先读 {@code getParamNameValuePairs()} 会得到空表，
     *       让人误以为「条件没加上去」；</li>
     *   <li>列名解析依赖运行期注册的 {@code TableInfo}（见 {@link #initMybatisMetadata()}），
     *       不注册会抛「can not find lambda cache」。</li>
     * </ol>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertAllQueriesAudited() {
        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper, org.mockito.Mockito.atLeastOnce()).selectPage(any(), captor.capture());
        assertThat(captor.getAllValues())
                .as("公开读路径必须带上「审核通过」条件，否则未过审商品会从前台漏出")
                .allSatisfy(wrapper -> assertThat(wrapper.getSqlSegment()).contains("audit_status"));
    }

    // ---------------- 行为断言（最强的那两条） ----------------

    @Test
    void 未过审商品详情一律404() {
        // 待审核：公开列表看不到，但「把详情链接直接发出去」也必须打不开，
        // 否则审核链形同虚设
        when(spuMapper.selectById(9001L)).thenReturn(spu("PENDING", "ON_SALE"));
        assertThatThrownBy(() -> service.detail(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        when(spuMapper.selectById(9002L)).thenReturn(spu("REJECTED", "OFF_SHELF"));
        assertThatThrownBy(() -> service.detail(9002L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        // 未过审时连 SKU 都不该被查出来（提前返回，不做无意义的查询）
        verify(skuMapper, never()).selectList(any());
    }

    @Test
    void 已过审且在售的商品详情正常返回() {
        when(spuMapper.selectById(9001L)).thenReturn(spu("APPROVED", "ON_SALE"));

        var detail = service.detail(9001L);

        assertThat(detail.card().id()).isEqualTo(9001L);
        assertThat(detail.card().title()).isEqualTo("测试商品");
    }

    /**
     * <b>不填品牌的商品必须能正常出卡片</b>（E27 · 2026-10-09 由一次真实 500 补上）。
     *
     * <p>品牌是可选字段，第三方卖家上架可以不填。这里刻意把 {@code brandNames} 打桩成
     * <b>{@code Map.of()}（不可变 Map）</b> —— 也就是「这一页商品一条带品牌的都没有」时
     * 修复前 {@code CatalogLookup} 的真实返回值。
     *
     * <p>为什么不能让基座那个 HashMap 打桩替我们兜住：那会**掩盖**被测代码的判空缺失。
     * 本用例要证明的恰恰是「不管名称表返回的是哪种 Map 实现，出卡片都不该炸」——
     * 判空的正确位置在调用方，不在 Map 的实现里。
     */
    @Test
    void 不填品牌的商品详情正常返回且品牌名为null() {
        when(spuMapper.selectById(9001L)).thenReturn(spu("APPROVED", "ON_SALE"));
        // 不可变 Map：一旦调用方漏了判空，get(null) 会直接抛 NPE
        when(catalogLookup.brandNames(any())).thenReturn(Map.of());

        var detail = service.detail(9001L);

        assertThat(detail.card().brandId()).isNull();
        assertThat(detail.card().brandName()).isNull();
        assertThat(detail.card().title()).isEqualTo("测试商品");
    }

    @Test
    void 未过审商品在采购视图里不可售() {
        SkuEntity sku = new SkuEntity();
        sku.setId(7001L);
        sku.setSpuId(9001L);
        sku.setStatus("ACTIVE");
        sku.setStock(10);
        sku.setFrozenStock(0);
        sku.setPrice(1500L);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(spu("PENDING", "ON_SALE")));

        // 购物车里可能躺着「加购后商品被驳回」的条目；加购校验与下单快照都读这个字段。
        // 不把它算进来，那种条目会一路走到下单才炸一个语焉不详的错误
        var views = service.purchaseViews(List.of(7001L));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).sellable()).isFalse();
        assertThat(views.get(0).spuStatus()).isEqualTo("ON_SALE");
    }

    @Test
    void 已过审商品在采购视图里可售() {
        SkuEntity sku = new SkuEntity();
        sku.setId(7001L);
        sku.setSpuId(9001L);
        sku.setStatus("ACTIVE");
        sku.setStock(10);
        sku.setFrozenStock(0);
        sku.setPrice(1500L);
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(spu("APPROVED", "ON_SALE")));

        assertThat(service.purchaseViews(List.of(7001L)).get(0).sellable()).isTrue();
    }

    // ---------------- 条件断言（覆盖全部前台读路径） ----------------

    @Test
    void 商品检索带过审条件() {
        service.search(browse());
        assertAllQueriesAudited();
    }

    @Test
    void 首页热门与最新带过审条件() {
        service.topBySales(6);
        assertAllQueriesAudited();

        service.newest(6);
        assertAllQueriesAudited();
    }

    @Test
    void 物种精选与同类推荐带过审条件() {
        service.bySpecies("cat", 6);
        assertAllQueriesAudited();

        service.byCategory(500L, null, 4);
        assertAllQueriesAudited();
    }

    @Test
    void AI导购候选召回带过审条件() {
        // 语义后果很具体：未过审商品若是进了候选池，AI 会向用户推荐一件买不到的东西
        service.internalCandidates("猫粮", 8);

        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .as("AI 召回必须在 SQL 层排除未过审商品")
                .contains("audit_status");
    }

    @Test
    void 搜索纠错词表只取已过审商品标题() {
        // 未过审商品的标题不该进纠错词表：词表是「用户能搜到的东西」的抽样，
        // 混进去等于把没放行的商品名以「你是不是想搜 X」的形式泄给用户
        service.suggestKeyword("猫粮");

        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("audit_status");
    }
}
