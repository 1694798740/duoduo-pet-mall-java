package com.duoduo.product.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.product.client.MembershipClient;
import com.duoduo.product.client.MerchantLicenseClient;
import com.duoduo.product.client.MerchantRiskClient;
import com.duoduo.product.client.SkuUsageClient;
import com.duoduo.product.client.dto.MembershipInfo;
import com.duoduo.product.client.dto.MerchantLicenseInfo;
import com.duoduo.product.client.dto.MerchantRiskInfo;
import com.duoduo.product.client.dto.SkuUsageInfo;
import com.duoduo.product.client.dto.SkuUsageQuery;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditView;
import com.duoduo.product.dto.ProductDtos.SellerProductBatchItemView;
import com.duoduo.product.dto.ProductDtos.SellerProductBatchResultView;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductUpdateRequest;
import com.duoduo.product.dto.ProductDtos.SellerSkuPriceStockRequest;
import com.duoduo.product.dto.ProductDtos.SkuPriceStockInput;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProductDtos.SellerSkuInput;
import com.duoduo.product.dto.ProductDtos.SellerSkuStructureInput;
import com.duoduo.product.dto.ProductDtos.SellerSkuStructureRequest;
import com.duoduo.product.dto.ProductDtos.SellerSkuStructureResultView;
import com.duoduo.product.dto.ProductDtos.SellerSkuUsageView;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.CatalogLookup;
import com.duoduo.product.support.search.SearchEvents;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端商品管理 + 商品上架审核（E14）单测。
 *
 * <h2>为什么这些断言值得写</h2>
 * <ul>
 *   <li><b>归属判定有两个入口</b>（编辑 / 上下架）却只有一条实现：
 *       只在一处写判定的后果是另一处「看起来有权限校验、实际形同虚设」——
 *       这类缺陷只在越权请求时出现，普通冒烟全绿。</li>
 *   <li><b>字段三态</b>（{@code null} = 不改、{@code ""} = 清空、有值 = 替换）
 *       混淆前两者都不会报错，只能靠断言守住。</li>
 *   <li><b>审核状态机的每条边</b>（提交 / 内容变更再审 / 上架门禁 / 重提 / 复核）
 *       都是「错了也不会崩」的那种错 —— 它只会让违规商品悄悄挂在前台，
 *       或者让卖家永远改不回来。必须逐条钉住。</li>
 *   <li><b>「自营免审」与「第三方必审」的分界</b>：边界写反了，要么运营自己被卡住，
 *       要么第三方商品直接上线。两种都不是运行时异常，是纯业务事故。</li>
 * </ul>
 */
class SellerProductServiceTest {

    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final SkuMapper skuMapper = mock(SkuMapper.class);
    private final MembershipClient membershipClient = mock(MembershipClient.class);
    private final MerchantLicenseClient merchantLicenseClient = mock(MerchantLicenseClient.class);
    private final MerchantRiskClient merchantRiskClient = mock(MerchantRiskClient.class);
    private final SkuUsageClient skuUsageClient = mock(SkuUsageClient.class);
    private final BannedWordGuard bannedWordGuard = mock(BannedWordGuard.class);
    private final CatalogLookup catalogLookup = mock(CatalogLookup.class);
    private final SpuAuditHistoryService auditHistoryService = mock(SpuAuditHistoryService.class);
    private final CategoryAttrService categoryAttrService = mock(CategoryAttrService.class);
    private final SpuImageService spuImageService = mock(SpuImageService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SellerProductService service = new SellerProductService(
            spuMapper, skuMapper, membershipClient, merchantLicenseClient, merchantRiskClient,
            skuUsageClient, new SnowflakeIdGenerator(1, 1), bannedWordGuard, catalogLookup, objectMapper,
            auditHistoryService, categoryAttrService, spuImageService, eventPublisher);

    /**
     * 默认让「店铺资质」查得到且可售（E15），并让「商家风险画像」查得到且**无标记**（E28）。
     *
     * <p>不这么做的话，E14 那批上下架用例会因为多了一道门禁而全红 ——
     * 而它们想验的是审核维度，不是资质维度。默认放行 + 单独的资质用例，
     * 比让每条用例都自己 stub 一次更不容易漏。
     *
     * <p>风险画像同理：不 stub 就会走 NPE 兜底分支（按无标记处理，行为一样但会刷 WARN 日志），
     * 而且「无标记」是绝大多数商家的常态 —— 把它设成默认值，需要污点的用例显式覆盖。
     */
    @BeforeEach
    void stubLicenseAsSellable() {
        when(merchantLicenseClient.of(any())).thenReturn(ApiResponse.ok(
                new MerchantLicenseInfo("710000000000000101", "APPROVED", "NONE", null, null)));
        when(merchantRiskClient.of(any())).thenReturn(ApiResponse.ok(
                new MerchantRiskInfo("710000000000000101", 0, false, false, false)));
        // A-14：默认「没有任何 SKU 被订单引用」；要验删除门禁的用例显式覆盖
        when(skuUsageClient.usage(any())).thenReturn(ApiResponse.ok(List.of()));
        // A-07：乐观锁写入成功（影响 1 行）。不 stub 的话 Mockito 默认返回 0，
        // 会被实现解读为「期间有他人写入」，把所有正常路径都变成 409。
        when(spuMapper.update(isNull(), any())).thenReturn(1);
    }

    /** 让该商家带上「此前有驳回 / 停业记录」的污点（E28 的风险规则之一） */
    private void stubFlaggedSeller() {
        when(merchantRiskClient.of(any())).thenReturn(ApiResponse.ok(
                new MerchantRiskInfo("710000000000000101", 1, false, false, false)));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    /**
     * 注册 MyBatis-Plus 的实体元数据缓存。
     *
     * <p>构造 {@code LambdaQueryWrapper} 时，{@code eq(SpuEntity::getX, v)} 要把方法引用
     * 解析成列名，而列名来自运行期注册的 {@code TableInfo}（平时由 Spring + MyBatis 启动时建好）。
     * 纯 Mockito 单测没有容器，不手工注册就会抛
     * 「MybatisPlus can not find lambda cache for this entity」——
     * 那个报错看起来像生产代码有问题，其实只是测试环境缺了一块运行期元数据。
     */
    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SpuEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkuEntity.class);
    }

    // ---------------- 夹具 ----------------

    private static LoginUser seller(long id) {
        return new LoginUser(id, "13800000000", "SELLER", false);
    }

    private static LoginUser operator() {
        return new LoginUser(900L, "13900000000", "OPERATOR", true);
    }

    private static LoginUser plainUser(long id) {
        return new LoginUser(id, "13700000000", "USER", false);
    }

    /** 第三方（需审核）商品；默认已过审、在售 */
    private static SpuEntity spu(long id, long sellerId, String title, String image) {
        return spu(id, sellerId, title, image, "merchant", "APPROVED", "ON_SALE");
    }

    private static SpuEntity spu(long id, long sellerId, String title, String image,
                                 String supplyType, String auditStatus, String status) {
        SpuEntity spu = new SpuEntity();
        spu.setId(id);
        spu.setSellerId(sellerId);
        spu.setTitle(title);
        spu.setMainImage(image);
        // 类目必须给：审核视图会按类目 id 批量取名称（List.of(null) 会直接 NPE）
        spu.setCategoryId(500L);
        spu.setSupplyType(supplyType);
        spu.setAuditStatus(auditStatus);
        spu.setStatus(status);
        spu.setSalesCount(0);
        spu.setMinPrice(1000L);
        spu.setMaxPrice(2000L);
        // 数据库里 version 非空、默认 0；夹具必须带上，否则乐观锁条件拿到 null
        spu.setVersion(0);
        return spu;
    }

    /** 提交商品所需的外围依赖：类目存在、品牌可不传、SKU 统计为空 */
    private void stubCreateDeps() {
        when(catalogLookup.categories(any())).thenReturn(Map.of(500L, new CategoryEntity()));
        when(catalogLookup.brands(any())).thenReturn(Map.of());
        when(skuMapper.selectList(any())).thenReturn(List.of());
    }

    private static SellerProductCreateRequest createRequest(String title) {
        return new SellerProductCreateRequest(title, "副标题", 500L, null,
                "https://img/main.png", List.of("https://img/1.png"), "商品详情",
                List.of(new SellerSkuInput("2kg", 1990L, 10, null),
                        new SellerSkuInput("5kg", 3990L, 5, null)));
    }

    private static SellerProductUpdateRequest titleUpdate(String title) {
        return titleUpdate(title, 0);
    }

    private static SellerProductUpdateRequest titleUpdate(String title, int version) {
        return new SellerProductUpdateRequest(title, null, null, null, null, null, null,
                null, null, null, null, version);
    }

    /** 捕获内容编辑 / 审核发出的那条条件 UPDATE（A-07 起不再有整行 updateById） */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<SpuEntity> captureSpuUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(spuMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    /** 条件 UPDATE 必须带版本前置条件，并且把版本原子 +1 */
    private static void assertVersionGuarded(LambdaUpdateWrapper<SpuEntity> wrapper, int expectedVersion) {
        assertThat(wrapper.getSqlSegment()).contains("version");
        assertThat(wrapper.getSqlSet()).contains("version = version + 1");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(expectedVersion);
    }

    // ---------------- 归属与准入（沿用旧版断言，防止改动走形） ----------------

    @Test
    void 卖家可以改自己商品的标题与主图() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "旧标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        SellerProductView view = service.update(9001L,
                new SellerProductUpdateRequest("新标题", null, "https://img/1.png", null, null, null, null,
                        null, null, null, null, 0));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.mainImage()).isEqualTo("https://img/1.png");
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("title", "main_image");
        assertVersionGuarded(update, 0);
    }

    @Test
    void 编辑别人的商品_403而不是404() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));

        // 刻意区分「不存在」与「存在但不是你的」：统一 404 等于帮越权探测消除了信号
        assertThatThrownBy(() -> service.update(9001L, titleUpdate("新标题")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 上下架别人的商品同样403_与编辑共用归属判定() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));

        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
    }

    // ---------------- E23 · 代运营：平台运营对第三方商品的口子已收窄 ----------------

    /**
     * 收窄的理由：商品域给运营的可见范围是 {@code platformScope = true}，归属校验一律放行。
     * 在商品还没有第三方归属的年代这没问题（平台管的就是自己的货），
     * 但从 E27 起 {@code t_spu.seller_id} 指向真实第三方卖家，
     * 同一段代码的含义就变成了「平台能改别人的商品」。
     * <p>这条断言守的是「已经收窄」，而不是「本来就不行」—— 它是在钉住一次行为变更。
     */
    @Test
    void E23_运营不能直接下架第三方商品_必须走代运营工单() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "第三方商品", null));

        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第三方店铺")
                // 提示必须指向**可执行的下一步**：只说「不允许」会让运营反复重试
                .hasMessageContaining("proxy-off-shelf");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void E23_正对照_运营仍可直接下架自营商品() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 自营是平台自己的货，收窄不该把它一起拦住 —— 否则「平台连自己的商品都动不了」
        assertThat(service.changeStatus(9001L, "OFF_SHELF").status()).isEqualTo("OFF_SHELF");
    }

    @Test
    void E23_店主改自己的商品不受收窄影响() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "我的商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.changeStatus(9001L, "OFF_SHELF").status()).isEqualTo("OFF_SHELF");
    }

    // ---------------- A-13 · 批量上下架：逐条结果，部分失败不回滚 ----------------

    @Test
    void A13_批量下架全部成功_逐条返回目标状态() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "商品A", null));
        when(spuMapper.selectById(9002L)).thenReturn(spu(9002L, 101L, "商品B", null));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L, 9002L), "OFF_SHELF");

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.items()).allMatch(SellerProductBatchItemView::success);
        assertThat(result.items()).extracting(SellerProductBatchItemView::status)
                .containsExactly("OFF_SHELF", "OFF_SHELF");
        verify(spuMapper, times(2)).update(isNull(), any());
    }

    /**
     * 批量语义的核心：一条失败不能拖垮整单，也不能把已经成功的条目回滚。
     * 这里用「别人的商品」制造失败，断言另一条仍然落库（写入恰好一次）。
     */
    @Test
    void A13_一条失败不影响其它条_已成功的条目不回滚() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "我的商品", null));
        when(spuMapper.selectById(9002L)).thenReturn(spu(9002L, 202L, "别人的商品", null));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L, 9002L), "OFF_SHELF");

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        SellerProductBatchItemView failed = result.items().get(1);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errorCode()).isEqualTo(ErrorCode.FORBIDDEN.name());
        assertThat(failed.message()).contains("不属于当前账号");
        verify(spuMapper, times(1)).update(isNull(), any());
    }

    @Test
    void A13_批量上架时未过审条目单条失败_失败理由指向下一步() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L))
                .thenReturn(spu(9001L, 101L, "已过审", null, "merchant", "APPROVED", "OFF_SHELF"));
        when(spuMapper.selectById(9002L))
                .thenReturn(spu(9002L, 101L, "待审核", null, "merchant", "PENDING", "OFF_SHELF"));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L, 9002L), "ON_SALE");

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("ON_SALE");
        assertThat(result.items().get(1).message()).contains("审核");
        // 只有过审那一件真的写了库
        verify(spuMapper, times(1)).update(isNull(), any());
    }

    @Test
    void A13_已是目标状态仍记为成功且不写库() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "已在售", null));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L), "ON_SALE");

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("ON_SALE");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A13_重复id只处理一次() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "商品A", null));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L, 9001L, 9001L), "OFF_SHELF");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        verify(spuMapper, times(1)).update(isNull(), any());
    }

    @Test
    void A13_空列表_null_超上限_非法action都整体拒绝() {
        UserContext.set(seller(101L));

        assertThatThrownBy(() -> service.changeStatusBatch(null, "OFF_SHELF"))
                .isInstanceOf(BizException.class).hasMessageContaining("ids");
        assertThatThrownBy(() -> service.changeStatusBatch(List.of(), "OFF_SHELF"))
                .isInstanceOf(BizException.class).hasMessageContaining("ids");
        assertThatThrownBy(() -> service.changeStatusBatch(java.util.Arrays.asList(9001L, null), "OFF_SHELF"))
                .isInstanceOf(BizException.class).hasMessageContaining("空值");
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> service.changeStatusBatch(tooMany, "OFF_SHELF"))
                .isInstanceOf(BizException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> service.changeStatusBatch(List.of(9001L), "DELETE"))
                .isInstanceOf(BizException.class).hasMessageContaining("ON_SALE");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A13_运营批量操作第三方商品_逐条403而不是整单失败() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "第三方商品", null));
        when(spuMapper.selectById(9002L))
                .thenReturn(spu(9002L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE"));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductBatchResultView result =
                service.changeStatusBatch(List.of(9001L, 9002L), "OFF_SHELF");

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().get(0).message()).contains("第三方店铺");
        assertThat(result.items().get(1).status()).isEqualTo("OFF_SHELF");
    }

    /**
     * 代运营申请入口必须由被代理方发起。若平台既能授权又能执行，
     * 这套机制只是给越权操作加了一道填写框 —— 审计记录很完整，而它证明的事实不存在。
     */
    @Test
    void E23_平台账号不能替商家发起代运营申请() {
        UserContext.set(operator());

        assertThatThrownBy(service::requireStoreOwnerUserId)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能替商家作出授权");
    }

    // ---------------- A-1 / PLAN B-23 · 还上 E23 的欠账：编辑也纳入同一道门禁 ----------------

    /**
     * E23 当初**刻意只拦上下架**，把"编辑"留作单独评估（理由写在 requireProxyForThirdParty 的注释里）——
     * 本条把这个欠账的收窄钉住：运营绕过工单直接改第三方商品，必须 403。
     *
     * <p>★ 判据里带「**不落库**」这一条很重要：只在入口抛异常、而写库已经发生，
     * 是最难查的一类问题（接口报错、数据却已经变了）。所以除了断言异常，
     * 还要断言 {@code updateById} 从未被调用。
     */
    @Test
    void A1_运营不能直接编辑第三方商品_与上下架共用同一道门禁() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "第三方商品", null));

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("被平台改掉的标题")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第三方店铺")
                // 编辑的"下一步"与上下架**不一样**：没有代运营工单入口，只有"由店主自行维护" ——
                // 提示指错方向会让运营反复重试一个根本不存在的入口
                .hasMessageContaining("由店主在商家端自行维护");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void A1_正对照_运营仍可编辑自营商品() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 自营是平台自己的货 —— 收窄若把它也拦住，等于"平台连自己的商品都改不了"
        assertThat(service.update(9001L, titleUpdate("运营改自营标题")).title())
                .isEqualTo("运营改自营标题");
    }

    @Test
    void A1_正对照_店主改自己的商品不受影响() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "我的商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.update(9001L, titleUpdate("店主改自己的")).title())
                .isEqualTo("店主改自己的");
    }

    // ---------------- 顺带查出的真缺陷：「传 "" = 清空」在 NOT_NULL 策略下静默失效 ----------------

    /**
     * 类注释把「字段三态」写成了契约：{@code null} = 不改、{@code ""} = 清空、有值 = 替换。
     * 但可清空的三个字段（subtitle / images / detail）走的是 {@code spuMapper.updateById(spu)}，
     * 而 MyBatis-Plus 的更新策略是 **NOT_NULL —— 它不写 null 字段** ⇒ 清空静默失效。
     *
     * <p>症状最坏的地方在于**每一层都看起来正常**：接口 200、审计有记录、内存里的实体也变了、
     * 前端刷新后旧值还在。2026-10-13 是 `proxy-ops-smoke.ps1` 里那条读库断言当场抓到的。
     *
     * <p>★ 为什么单测只断言"多了一条 update 调用"而不断言"库里值变了"：
     * 单测用的是 mock mapper，**本来就不落库**。两层各管一段 ——
     * 这里管「代码有没有真的发出那条 set(null) 语句」，冒烟管「语句到了库里有没有生效」。
     */
    @Test
    void 清空副标题时必须额外发一条显式set_null的UPDATE() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        spu.setSubtitle("旧副标题");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L,
                new SellerProductUpdateRequest(null, "", null, null, null, null, null,
                        null, null, null, null, 0));

        // A-07 起只有一条条件 UPDATE（eq id + version）：内容列与审核列一起写，
        // subtitle 显式 set(null) 才不会被 NOT_NULL 策略吞掉。
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("subtitle", "audit_reason");
        // AssertJ 的 anyMatch 遇到 null 元素会 NPE，这里直接看流结果
        assertThat(update.getParamNameValuePairs().values().stream().anyMatch(java.util.Objects::isNull))
                .as("subtitle 必须显式 set(null)，不能依赖 NOT_NULL 策略")
                .isTrue();
        assertVersionGuarded(update, 0);
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 只改标题时不发多余的显式清空UPDATE() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("新标题"));

        // 反面对照：没有清空动作时也只发一条条件 UPDATE（A-07 前是「updateById + 审核列补丁」两条）。
        // 审核列仍然每次都显式写，否则会出现「已通过 + 上一轮的驳回原因」这种自相矛盾的数据。
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("audit_status", "audit_source", "audit_at", "audit_reason");
        assertVersionGuarded(update, 0);
    }

    @Test
    void E23_正对照_店主能拿到自己的店铺主体userId() {
        UserContext.set(seller(101L));

        assertThat(service.requireStoreOwnerUserId()).isEqualTo(101L);
    }

    @Test
    void 非卖家角色进不了商家端() {
        UserContext.set(plainUser(101L));

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("新标题")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是卖家");
    }

    @Test
    void 商品不存在是404而不是403() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("新标题")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 字段三态_不传不改_且主图不允许清空() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 只传标题：主图必须原样保留（传 null = 不改）
        SellerProductView onlyTitle = service.update(9001L, titleUpdate("新标题"));
        assertThat(onlyTitle.mainImage()).isEqualTo("https://img/old.png");

        // 主图是列表页唯一的视觉锚点，清空即 400（E14 收紧；旧版允许清空）
        // 第一次 update 已把内存版本推到 1，后续请求必须带当前版本，才能走到内容校验
        assertThatThrownBy(() -> service.update(9001L,
                new SellerProductUpdateRequest(null, null, "   ", null, null, null, null,
                        null, null, null, null, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主图不能为空");

        // 标题不允许清空
        assertThatThrownBy(() -> service.update(9001L, titleUpdate("   ", 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("标题不能为空");
    }

    @Test
    void 值没变就不写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, new SellerProductUpdateRequest("原标题", null, "https://img/old.png",
                null, null, null, null, null, null, null, null, 0));

        // 无变化不发 UPDATE：省一次写，也避免 updated_at 被无意义地刷新
        // （刷新 updated_at 还会让索引同步任务白跑一轮）
        verify(spuMapper, never()).update(isNull(), any());
        // A-10：没碰规格参数、没换类目 —— 不校验模板，存量商品不被新模板卡住
        verify(categoryAttrService, never()).validateForPublish(any(), any());
    }

    @Test
    void A10_只改标题不校验属性模板_改规格时才校验() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "旧标题", null);
        spu.setSpecParams("[{\"name\":\"净含量\",\"value\":\"2kg\"}]");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("新标题"));
        verify(categoryAttrService, never()).validateForPublish(any(), any());

        service.update(9001L, new SellerProductUpdateRequest(null, null, null, null, null, null,
                null, List.of(new com.duoduo.product.dto.ProductDtos.KvItem("净含量", "2kg")),
                null, null, null, 1));
        verify(categoryAttrService).validateForPublish(eq(500L), any());
    }

    // ---------------- E14 + E28 · 提交商品：自动放行判定 ----------------

    @Test
    void 第三方商家提交干净商品_自动放行且归属店铺主体() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        SellerProductView view = service.create(createRequest("新商品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        SpuEntity saved = captor.getValue();
        assertThat(saved.getSupplyType()).isEqualTo("merchant");
        assertThat(saved.getSellerId()).isEqualTo(101L);
        // E28：人工审核不再是必经环节 —— 未命中任何风险规则的商品提交即放行，
        // 来源标 AUTO（这是事后抽查能回头找到它的唯一依据）。
        assertThat(saved.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(saved.getAuditSource()).isEqualTo("AUTO");
        assertThat(saved.getAuditBy()).isNull();
        // ★ 抽查的候选集按「audit_at 落在当天」筛（AuditSampleService.sweepOnce）——
        //   自动放行不写这个时间，整条抽查链路就永远抽不到任何东西，而且一声不响
        assertThat(saved.getAuditAt()).isNotNull();
        // 交易维度先按「卖家希望它在售」落，可见性由审核维度把守 ——
        // 这样审核通过的那一刻商品自动出现，卖家不必再点一次上架
        assertThat(saved.getStatus()).isEqualTo("ON_SALE");
        assertThat(saved.getMinPrice()).isEqualTo(1990L);
        assertThat(saved.getMaxPrice()).isEqualTo(3990L);
        assertThat(saved.getRating()).isEqualByComparingTo("0.00");
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(view.auditLabel()).isEqualTo("已通过");
        assertThat(view.skuCount()).isEqualTo(2);
        assertThat(view.totalStock()).isEqualTo(15);
        // A-10：新建商品必须按类目属性模板校验（模板为空时校验直接放行）
        verify(categoryAttrService).validateForPublish(eq(500L), any());
        // A-11：图片表与商品同事务双写
        verify(spuImageService).sync(saved);
        // 两个规格都要落库
        verify(skuMapper, times(2)).insert(any(SkuEntity.class));
    }

    @Test
    void 第三方商家提交命中风险的商品_转人工待审() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        // 详情里放了微信号 —— 「转向站外交易」是最典型的一类需要人看的商品
        SellerProductCreateRequest risky = new SellerProductCreateRequest("新商品", "副标题", 500L, null,
                "https://img/main.png", null, "加微信 duoduo888 咨询",
                List.of(new SellerSkuInput("2kg", 1990L, 10, null)));
        SellerProductView view = service.create(risky);

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        SpuEntity saved = captor.getValue();
        assertThat(saved.getAuditStatus()).isEqualTo("PENDING");
        // 「待审」必须配「来源为 null」：留着 AUTO 会让抽查任务把它当成自动放行的那一批再抽一次，
        // 而它其实正排在人工队列里没上架
        assertThat(saved.getAuditSource()).isNull();
        // 转人工 = 这段内容还没有结论，audit_at 必须为空；留着旧值会被当成「今天刚自动放行」抽进队列
        assertThat(saved.getAuditAt()).isNull();
        assertThat(view.auditStatus()).isEqualTo("PENDING");
        assertThat(view.auditLabel()).isEqualTo("待审核");
    }

    @Test
    void 前置许可类目_转人工核验资质() {
        UserContext.set(seller(101L));
        stubCreateDeps();
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of(500L, "宠物药品"));

        service.create(createRequest("驱虫药"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        assertThat(captor.getValue().getAuditStatus()).isEqualTo("PENDING");
    }

    @Test
    void 有污点的商家提交干净商品_也转人工() {
        UserContext.set(seller(101L));
        stubCreateDeps();
        stubFlaggedSeller();          // 该店此前有驳回 / 停业记录

        service.create(createRequest("新商品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        assertThat(captor.getValue().getAuditStatus()).isEqualTo("PENDING");
    }

    @Test
    void 类目名或商家画像查不到_不因此转人工() {
        UserContext.set(seller(101L));
        stubCreateDeps();
        // 用户域查不通（抛异常）：按无标记处理，否则一次跨域超时会把全平台商品推进待审队列
        when(merchantRiskClient.of(any())).thenThrow(new IllegalStateException("用户域超时"));

        service.create(createRequest("新商品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        assertThat(captor.getValue().getAuditStatus()).isEqualTo("APPROVED");
    }

    @Test
    void 运营提交商品_自营免审且归属平台() {
        UserContext.set(operator());
        stubCreateDeps();

        service.create(createRequest("自营新品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        SpuEntity saved = captor.getValue();
        assertThat(saved.getSupplyType()).isEqualTo("self");
        assertThat(saved.getSellerId()).isEqualTo(0L);
        assertThat(saved.getAuditStatus()).isEqualTo("APPROVED");
        // 自营的放行来源是 SELF 而不是 AUTO：抽查只抽第三方，
        // 混进 AUTO 会让「平台自己的商品被抽查」这种无意义任务出现在运营待办里
        assertThat(saved.getAuditSource()).isEqualTo("SELF");
    }

    @Test
    void 提交商品_缺规格或缺价格一律400() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        SellerProductCreateRequest noSku = new SellerProductCreateRequest("无规格", null, 500L, null,
                "https://img/main.png", null, null, List.of());
        assertThatThrownBy(() -> service.create(noSku))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少需要一个规格");

        SellerProductCreateRequest badPrice = new SellerProductCreateRequest("价格非法", null, 500L, null,
                "https://img/main.png", null, null, List.of(new SellerSkuInput("2kg", 0L, 1, null)));
        assertThatThrownBy(() -> service.create(badPrice))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("价格必须大于 0");

        // 规格名重复：库里不会报错，但卖家在订单里分不清买的是哪个 —— 必须在入口拦
        SellerProductCreateRequest dupSpec = new SellerProductCreateRequest("规格重复", null, 500L, null,
                "https://img/main.png", null, null,
                List.of(new SellerSkuInput("2kg", 1000L, 1, null), new SellerSkuInput("2kg", 2000L, 1, null)));
        assertThatThrownBy(() -> service.create(dupSpec))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规格名重复");

        verify(spuMapper, never()).insert(any(SpuEntity.class));
    }

    @Test
    void 提交商品_类目不存在或主图缺失一律400() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        SellerProductCreateRequest badCategory = new SellerProductCreateRequest("类目错", null, 999L, null,
                "https://img/main.png", null, null, List.of(new SellerSkuInput(null, 1000L, 1, null)));
        assertThatThrownBy(() -> service.create(badCategory))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类目不存在");

        SellerProductCreateRequest noImage = new SellerProductCreateRequest("没主图", null, 500L, null,
                "   ", null, null, List.of(new SellerSkuInput(null, 1000L, 1, null)));
        assertThatThrownBy(() -> service.create(noImage))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主图不能为空");
    }

    @Test
    void 提交商品_标题与详情都要过违禁词() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        service.create(createRequest("普通商品"));

        // 断言「写库前确实过了违禁词检查」：审核是人看得过来才叫审核，
        // 最脏的内容应该挡在入口，而不是等运营审到
        verify(bannedWordGuard).rejectIfHit(eq("普通商品"), eq("商品标题"));
        verify(bannedWordGuard).rejectIfHit(eq("商品详情"), eq("商品详情"));
    }

    // ---------------- E14 + E28 · 内容变更再审 ----------------

    @Test
    void 第三方商品内容变更_重新判定_干净则自动放行() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.update(9001L, titleUpdate("改好的新标题"));

        // E28：内容变更后**重跑判定**，干净则继续在售 —— 若每次改文案都要人工过一遍，
        // 「免除事前人工审核」就只剩一句口号。而「先过审、再改成违规内容」依然被挡住：
        // 改成违规内容会命中风险规则（见下一条用例）。
        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getAuditSource()).isEqualTo("AUTO");
        assertThat(spu.getAuditAt()).isNotNull();     // 重新放行也要留下可被抽查的时间锚点
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("audit_status", "audit_source", "audit_at");
        assertVersionGuarded(update, 0);
    }

    @Test
    void 第三方商品内容变更_命中风险则回到待审() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("加微信 duoduo888 领券"));

        // 「先过审、再改成违规内容」是最常见的绕过手法，这里守住它
        assertThat(spu.getAuditStatus()).isEqualTo("PENDING");
        assertThat(spu.getAuditSource()).isNull();
        assertThat(spu.getAuditAt()).isNull();
        // ★ audit_source 必须靠显式 set(col, null) 落库：updateById 的 NOT_NULL 策略不写 null 字段，
        // 界面上就会出现「待审 + 来源=AUTO」这种自相矛盾的组合（接口照样 200）
        verify(spuMapper).update(isNull(), any());
    }

    @Test
    void 第三方商品驳回后编辑_回到待审并恢复在售() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setAuditReason("图片不清晰");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("改好了"));

        // ★ 曾被判违规的商品，内容改得再干净也要人看 —— 否则「随便改一个字」
        //   就能把 REJECTED 冲掉并重新自动上线，人工驳回与抽查判违规全部作废
        assertThat(spu.getAuditStatus()).isEqualTo("PENDING");
        assertThat(spu.getAuditSource()).isNull();
        assertThat(spu.getAuditReason()).isNull();
        // 驳回时 status 被压成 OFF_SHELF，且 REJECTED 件不可能自己上架（门禁堵死）；
        // 若不在这里恢复，卖家改完并通过审核后仍是不可见状态，且没有任何入口能放它出来
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
    }

    /**
     * 2026-10-24 回归：驳回 → 第一次编辑（状态变 PENDING）→ **第二次编辑**。
     *
     * <p>修复前：第二次编辑时库里已不是 REJECTED，`applyContentReaudit` 只读当前状态，
     * 干净内容会被 decideAudit 自动放行 —— 卖家「改两次」就绕过了人工审核。
     * 修复后：rejected_at 是持久化标记，第一次编辑不会清掉它，第二次仍转人工。
     */
    @Test
    void 驳回后连续两次编辑_第二次仍必须人工审核() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null, "merchant", "PENDING", "ON_SALE");
        // 第一次编辑后的库内状态：PENDING + rejected_at 仍在（这正是修复的关键）
        spu.setRejectedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("第二次改个字"));

        assertThat(spu.getAuditStatus()).isEqualTo("PENDING");
        assertThat(spu.getAuditSource()).isNull();
        assertThat(spu.getAuditAt()).isNull();
    }

    /** 人工通过（平反）后标记必须清空，否则该商品以后每次改内容都要人审 */
    @Test
    void 人工通过后_rejectedAt被清空且此后可自动放行() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        spu.setRejectedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(java.util.Map.of());

        service.audit(9001L, "APPROVE", null, 0);

        assertThat(spu.getRejectedAt()).isNull();
        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
    }

    /**
     * 2026-10-24：改价 / 改库存是运营字段，**不触发重审**，但要同步价格区间。
     *
     * <p>用两次 selectList 模拟「改前 / 改后」：真实库里 update 后第二次查询会读到新价。
     */
    @Test
    void 改价改库存_保持审核态并重算价格区间() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null, "merchant", "APPROVED", "ON_SALE");
        spu.setMinPrice(1000L);
        spu.setMaxPrice(2000L);
        spu.setAuditSource("AUTO");
        SkuEntity a = sku(11L, 9001L, 1000L, 5);
        SkuEntity b = sku(12L, 9001L, 2000L, 8);
        SkuEntity a2 = sku(11L, 9001L, 3000L, 9);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b), List.of(a2, b));

        var view = service.updateSkuPriceStock(9001L,
                new SellerSkuPriceStockRequest(List.of(new SkuPriceStockInput(11L, 3000L, 9))));

        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getAuditSource()).isEqualTo("AUTO");
        assertThat(view.minPrice()).isEqualTo(2000L);
        assertThat(view.maxPrice()).isEqualTo(3000L);
        // 走的是显式 UpdateWrapper（要同步 min/max），不是整行 updateById
        verify(spuMapper, never()).updateById(any());
    }

    @Test
    void 改价必须大于0且库存不能为负() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null, "merchant", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(11L, 9001L, 1000L, 5)));

        assertThatThrownBy(() -> service.updateSkuPriceStock(9001L,
                new SellerSkuPriceStockRequest(List.of(new SkuPriceStockInput(11L, 0L, null)))))
                .isInstanceOf(com.duoduo.common.exception.BizException.class);
        assertThatThrownBy(() -> service.updateSkuPriceStock(9001L,
                new SellerSkuPriceStockRequest(List.of(new SkuPriceStockInput(11L, null, -1)))))
                .isInstanceOf(com.duoduo.common.exception.BizException.class);
    }

    private static SkuEntity sku(long id, long spuId, long price, int stock) {
        SkuEntity sku = new SkuEntity();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setPrice(price);
        sku.setStock(stock);
        sku.setStatus("ACTIVE");
        return sku;
    }

    @Test
    void 自营商品内容变更_不触发审核() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, titleUpdate("自营改标题"));

        // 平台免审：运营改自己商品的文案不该把自己卡进审核队列
        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        // 自营走的是另一条路：不重跑判定、也不重写来源，审核态与来源都保持原值
        assertThat(spu.getAuditSource()).isNull();
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
    }

    // ---------------- E14 · 上架门禁 ----------------

    @Test
    void 待审商品不能自己上架() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        // 「先提交成 PENDING、再自己上架」是绕过审核最直接的一条路
        assertThatThrownBy(() -> service.changeStatus(9001L, "ON_SALE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在审核中");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 驳回商品不能自己上架_提示要能指出下一步() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        // 提示语必须告诉卖家「去改内容 / 去申请复核」，只说「不行」等于没说
        assertThatThrownBy(() -> service.changeStatus(9001L, "ON_SALE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未通过审核")
                .hasMessageContaining("重新提交");
    }

    @Test
    void 已过审商品下架后可以再上架() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "已过审", null, "merchant", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.changeStatus(9001L, "ON_SALE");

        assertThat(view.status()).isEqualTo("ON_SALE");
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
    }

    // ---------------- E15 · 资质门禁（第二道门） ----------------

    @Test
    void 停业店铺不能上架_提示要说明停业原因与下一步() {
        UserContext.set(seller(101L));
        when(merchantLicenseClient.of(101L)).thenReturn(ApiResponse.ok(new MerchantLicenseInfo(
                "710000000000000101", "SUSPENDED", "EXPIRED", null,
                "经营资质已过期（2026-06-30），店铺已暂停营业")));
        SpuEntity spu = spu(9001L, 101L, "本来在架", null, "merchant", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        // 「已停业」这三个字对商家毫无操作价值，必须一并给出原因
        assertThatThrownBy(() -> service.changeStatus(9001L, "ON_SALE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已停业")
                .hasMessageContaining("经营资质已过期");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 续期审核中的店铺也不能上架_判据是状态而不是没过期() {
        UserContext.set(seller(101L));
        // 续期已提交、正在审：证照其实还没过期，但资质尚未续上，同样不能卖。
        // 若用「过期了没有」当判据，这一档会被放过去。
        when(merchantLicenseClient.of(101L)).thenReturn(ApiResponse.ok(new MerchantLicenseInfo(
                "710000000000000101", "SUBMITTED", "T7", null, null)));
        SpuEntity spu = spu(9001L, 101L, "续期中", null, "merchant", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.changeStatus(9001L, "ON_SALE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("资质审核中");
    }

    @Test
    void 停业店铺仍然可以下架自己的商品_不该一刀切拦住所有写操作() {
        UserContext.set(seller(101L));
        when(merchantLicenseClient.of(101L)).thenReturn(ApiResponse.ok(new MerchantLicenseInfo(
                "710000000000000101", "SUSPENDED", "EXPIRED", null, "经营资质已过期")));
        SpuEntity spu = spu(9001L, 101L, "还在架上的", null, "merchant", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 商家在被停业期间清理自己的货架是正当操作；全拦住只会让他以为系统坏了
        assertThat(service.changeStatus(9001L, "OFF_SHELF").status()).isEqualTo("OFF_SHELF");
    }

    @Test
    void 资质查询失败时放行_一次跨域抖动不能变成全平台商家都上不了架() {
        UserContext.set(seller(101L));
        when(merchantLicenseClient.of(any())).thenThrow(new RuntimeException("connection refused"));
        SpuEntity spu = spu(9001L, 101L, "正常商品", null, "merchant", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 这是纵深防御的第二道门：第一道（过期即停业 + 商品联动下架）不依赖用户域可用性，
        // 所以这里可以安全地「故障即放行」，并把异常留在日志里
        assertThat(service.changeStatus(9001L, "ON_SALE").status()).isEqualTo("ON_SALE");
    }

    @Test
    void 无入驻记录时放行_历史商品不该被新门禁卡死() {
        UserContext.set(seller(101L));
        when(merchantLicenseClient.of(any())).thenReturn(ApiResponse.ok(null));
        SpuEntity spu = spu(9001L, 101L, "老数据", null, "merchant", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.changeStatus(9001L, "ON_SALE").status()).isEqualTo("ON_SALE");
    }

    @Test
    void 运营改自营商品状态不查资质_自营没有入驻记录这件事本身就是正常的() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.changeStatus(9001L, "ON_SALE").status()).isEqualTo("ON_SALE");
        verify(merchantLicenseClient, never()).of(any());
    }

    // ---------------- E15 · 资质过期联动停售（内部端点） ----------------

    @Test
    void 内部停售只下架在售商品_返回真实改动条数() {
        when(spuMapper.update(any(), any())).thenReturn(3);

        assertThat(service.offShelfAllBySeller(101L)).isEqualTo(3);

        // 条件必须是「该店铺 + 在售」：漏掉 status 条件会把已下架的商品
        // 也一并重写更新时间，让「本次改了几件」这个返回值失去意义
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(spuMapper).update(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("status");
    }

    @Test
    void 内部停售忽略自营_自营没有资质这个概念() {
        // seller_id = 0 是「平台自营」的既有语义，不是一个真实店铺 id；
        // 传 0 进来只能是调用方搞错了，绝不能把自营商品整店下架
        assertThat(service.offShelfAllBySeller(0L)).isZero();
        assertThat(service.offShelfAllBySeller(null)).isZero();
        verify(spuMapper, never()).update(any(), any());
    }

    @Test
    void 不认识的动作一律400_绝不静默当成下架() {
        UserContext.set(seller(101L));

        assertThatThrownBy(() -> service.changeStatus(9001L, "DELETE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 ON_SALE / OFF_SHELF");
    }

    // ---------------- E14 · 平台审核 ----------------

    @Test
    void 审核通过_不动交易状态() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        AdminProductAuditView view = service.audit(9001L, "APPROVE", null, 0);

        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getAuditReason()).isNull();
        assertThat(spu.getAuditBy()).isEqualTo(900L);
        assertThat(spu.getAuditAt()).isNotNull();
        // 通过时不动 status：卖家提交时期望的就是在售，通过即自动出现在前台；
        // 若它同时被卖家手动下架过，则保持下架（尊重卖家的选择）
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        // A-11：图片审核态跟随商品结论
        verify(spuImageService).applyAuditStatus(spu, "APPROVED");
    }

    @Test
    void 审核驳回_必须填原因且商品被压成下架() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        // 不填原因：卖家只能猜着改，审核结论也留不下可追溯的解释
        assertThatThrownBy(() -> service.audit(9001L, "REJECT", "  ", 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回时必须填写原因");

        service.audit(9001L, "REJECT", "主图与实物不符", 0);

        assertThat(spu.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(spu.getAuditReason()).isEqualTo("主图与实物不符");
        // 驳回同时压成下架：让卖家视角的语义一致（被驳回的货不该挂在「在售」里）
        assertThat(spu.getStatus()).isEqualTo("OFF_SHELF");
        verify(spuImageService).applyAuditStatus(spu, "REJECTED");
    }

    @Test
    void 审核动作非法或对象是自营商品_一律拒绝() {
        UserContext.set(operator());
        SpuEntity selfSpu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(selfSpu);

        assertThatThrownBy(() -> service.audit(9001L, "PASS", null, 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 APPROVE / REJECT");

        // 自营免审：走到这里说明调用方按错了对象，直接拒绝比「静默改个状态」安全
        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null, 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无需审核");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 非运营不能进审核台() {
        UserContext.set(seller(101L));

        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null, 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅运营 / 管理员");
        assertThatThrownBy(() -> service.listForAudit(null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅运营 / 管理员");
    }

    @Test
    void 审核筛选值非法一律400() {
        UserContext.set(operator());

        assertThatThrownBy(() -> service.listForAudit("WHATEVER", null, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("auditStatus 仅支持");
    }

    // ---------------- E14 · 重新提审 ----------------

    @Test
    void 驳回商品可以重新提审并恢复在售() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setAuditReason("类目选错了");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.resubmit(9001L);

        assertThat(spu.getAuditStatus()).isEqualTo("PENDING");
        assertThat(spu.getAuditReason()).isNull();
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
        assertThat(view.auditLabel()).isEqualTo("待审核");
        verify(spuImageService).applyAuditStatus(spu, "PENDING");
    }

    @Test
    void 待审与已过审的商品不能重复提审() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L))
                .thenReturn(spu(9001L, 101L, "待审", null, "merchant", "PENDING", "ON_SALE"));
        assertThatThrownBy(() -> service.resubmit(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在审核中");

        when(spuMapper.selectById(9002L))
                .thenReturn(spu(9002L, 101L, "已过审", null, "merchant", "APPROVED", "ON_SALE"));
        assertThatThrownBy(() -> service.resubmit(9002L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已通过审核");
    }

    // ---------------- E14 · 店员归属（E13 遗留边界的收口） ----------------

    @Test
    void 店员可以管理本店商品() {
        UserContext.set(plainUser(777L));
        // 店员角色仍是 USER，权限来自成员身份（实时查，停用即时生效）
        when(membershipClient.of(777L)).thenReturn(ApiResponse.ok(
                new MembershipInfo("123456", "101", "STAFF", "ACTIVE")));
        // 商品归属写的是**店主**（101），不是店员自己（777）
        SpuEntity spu = spu(9001L, 101L, "本店商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.changeStatus(9001L, "OFF_SHELF");

        assertThat(view.status()).isEqualTo("OFF_SHELF");
        // A-07：上下架改为定向 UPDATE —— 只写 status/updated_at，
        // 不碰审核列（整行覆盖会把并发落下的 REJECTED 冲回 APPROVED），也不递增版本。
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("status", "updated_at");
        assertThat(update.getSqlSet()).doesNotContain("audit_", "version");
    }

    @Test
    void 店员提交的商品归属店铺而不是店员个人() {
        UserContext.set(plainUser(777L));
        when(membershipClient.of(777L)).thenReturn(ApiResponse.ok(
                new MembershipInfo("123456", "101", "STAFF", "ACTIVE")));
        stubCreateDeps();

        service.create(createRequest("店员发的商品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(spuMapper).insert(captor.capture());
        // 归属店主：店主停掉这个店员之后，商品仍留在店里、仍管得着
        assertThat(captor.getValue().getSellerId()).isEqualTo(101L);
        // 店员的商品与店主自己发的走**同一套**自动放行判定（不因为「是员工发的」就收紧或放宽）
        assertThat(captor.getValue().getAuditStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getAuditSource()).isEqualTo("AUTO");
    }

    @Test
    void 已停用店员按非店员处理() {
        UserContext.set(plainUser(777L));
        when(membershipClient.of(777L)).thenReturn(ApiResponse.ok(
                new MembershipInfo("123456", "101", "STAFF", "DISABLED")));

        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是卖家");
    }

    @Test
    void 成员服务故障时按非店员处理_不放行() {
        UserContext.set(plainUser(777L));
        when(membershipClient.of(anyLong())).thenThrow(new RuntimeException("connect refused"));

        // 认证链路的下游故障不能把「拒绝」变成「放行」
        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是卖家");
    }

    @Test
    void 店员管不了别家店的商品() {
        UserContext.set(plainUser(777L));
        when(membershipClient.of(777L)).thenReturn(ApiResponse.ok(
                new MembershipInfo("123456", "101", "STAFF", "ACTIVE")));
        // 商品属于另一家店（店主 202）
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别家商品", null));

        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
    }

    @Test
    void 商家管不了平台自营商品() {
        UserContext.set(seller(101L));
        // seller_id = 0 是平台自营；若不显式拦，这个「0」会被边界条件糊过去
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE"));

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("改自营")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    /**
     * ★ 2026-10-13 行为收窄（A-1 / PLAN B-23）。
     *
     * <p>这条用例**原先叫「运营可以跨店查看与编辑」**，钉的是"运营能改别家商品"这个当时的行为。
     * E23 立项后它变成了越权路径 —— 运营可以绕过代运营工单、直接改第三方商品的内容。
     * 本次收窄后原断言必然失败，所以这里**显式改写**它，并把"收窄了什么、没收窄什么"拆开钉清楚：
     * <b>读仍然放行</b>（跨店排障是运营的正当职责），<b>写不再放行</b>。
     *
     * <p>★ 改写一条**已有**用例而不是删掉它，是刻意的：删掉等于把"这里曾经允许过"这段历史抹掉，
     * 下一个人再看代码只会觉得"本来就该 403"，从而失去「这是一次行为变更」的信息。
     */
    @Test
    void 运营可以跨店查看第三方商品_但不再能直接编辑() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 202L, "别家商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // ① 读：跨店排障不受本次收窄影响
        assertThat(service.ownerDetail(9001L)).isNotNull();

        // ② 写：从「允许」改成「403 + 不落库」（改之前这条断言是 isEqualTo("运营改标题")）
        assertThatThrownBy(() -> service.update(9001L, titleUpdate("运营改标题")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第三方店铺");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 运营不传店铺筛选时看全部商品_而不是空列表() {
        UserContext.set(operator());
        when(spuMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.list(null, null, null, 1, 20);

        // 修掉一个既有缺陷：旧版把 null 塞进 eq()，MyBatis-Plus 会生成
        // `seller_id = NULL`（永不匹配）→「想看全部」得到的是空列表
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("seller_id");
    }

    @Test
    void 非运营账号的列表强制只看自己的店() {
        UserContext.set(seller(101L));
        when(spuMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 就算显式传别人的 sellerId 也不生效 ——「换个 ID 看别人的商品」这条路不存在
        service.list(null, null, 999L, 1, 20);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("seller_id");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(101L);
    }

    @Test
    void 商家详情能读回自己被驳回的商品() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "被驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setAuditReason("主图不清晰");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        // 公开详情对未过审商品 404（防状态码探测），但卖家必须读得回自己的东西才能改
        var detail = service.ownerDetail(9001L);

        assertThat(detail.auditStatus()).isEqualTo("REJECTED");
        assertThat(detail.auditReason()).isEqualTo("主图不清晰");
    }

    // ---------------- A-07 · 编辑 / 审核并发保护（乐观锁） ----------------

    /**
     * 丢失更新的最小复现：卖家打开的编辑页版本是 2，期间运营审核把商品驳回（版本 3），
     * 卖家再保存。若不校验版本，整行写入会把 REJECTED 覆盖回 APPROVED —— 接口两边都 200。
     */
    @Test
    void A07_旧版本编辑_返回409且不写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        spu.setVersion(3);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("旧页面上改的标题", 2)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被其他人修改")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(spuMapper, never()).update(isNull(), any());
        assertThat(spu.getTitle()).isEqualTo("原标题");
    }

    @Test
    void A07_缺少版本号编辑_返回400且不写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.update(9001L,
                new SellerProductUpdateRequest("新标题", null, null, null, null, null, null,
                        null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少版本号")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A07_编辑成功_版本原子递增() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        spu.setVersion(4);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.update(9001L, titleUpdate("新标题", 4));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(spu.getVersion()).isEqualTo(5);
        assertVersionGuarded(captureSpuUpdate(), 4);
    }

    /** 审核台打开的是版本 4 的内容，期间卖家改了一次（版本 5）—— 运营不能对着旧内容下结论。 */
    @Test
    void A07_旧版本审核_返回409且不写库() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        spu.setVersion(5);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null, 4))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内容已变化")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A07_缺少版本号审核_返回400且不写库() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少版本号")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A07_审核成功_版本原子递增且结论与版本同一条语句落库() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        spu.setVersion(2);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        AdminProductAuditView view = service.audit(9001L, "APPROVE", null, 2);

        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getVersion()).isEqualTo(3);
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSet()).contains("audit_status", "audit_source", "audit_at", "audit_reason");
        assertVersionGuarded(update, 2);
    }

    /**
     * 重新提审没有 version 入参：它的前置条件是「当前仍是 REJECTED」。
     * 若运营在卖家点击重提的同一瞬间通过了该商品，重提必须落空并返回 409，
     * 而不是把 APPROVED 又推回 PENDING。
     */
    @Test
    void A07_重新提审_以驳回态为前置条件并递增版本() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setVersion(6);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.resubmit(9001L);

        assertThat(spu.getVersion()).isEqualTo(7);
        LambdaUpdateWrapper<SpuEntity> update = captureSpuUpdate();
        assertThat(update.getSqlSegment()).contains("audit_status");
        // 没有 version 入参：并发安全性来自「仍为 REJECTED」这个状态前置条件
        assertThat(update.getParamNameValuePairs().values()).contains("REJECTED");
        assertThat(update.getSqlSet()).contains("version = version + 1");
    }

    @Test
    void A07_重新提审期间被他人审核_返回409() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(spuMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.resubmit(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态已变化")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    /** 抽查复核判违规前必须确认「商品还是抽中时那一版」，否则等于对着旧内容下架。 */
    @Test
    void A07_抽查判违规遇到并发修改_返回409且不落库() {
        SpuEntity spu = spu(9001L, 101L, "抽中商品", null, "merchant", "APPROVED", "ON_SALE");
        spu.setVersion(1);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(spuMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rejectBySample(9001L, "主图违规", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内容已变化")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    // ---------------- A-08 · 审核历史与变更留痕 ----------------

    /**
     * 提交是审核时间线的起点：没有它，历史从第一次编辑才开始，
     * 「卖家提交时到底写了什么、当时是自动放行还是转人工」就永远缺一块。
     */
    @Test
    void A08_提交第三方商品记录SUBMIT事件() {
        UserContext.set(seller(101L));
        stubCreateDeps();

        service.create(createRequest("新商品"));

        ArgumentCaptor<SpuEntity> captor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordSubmit(captor.capture(), eq(false));
        SpuEntity saved = captor.getValue();
        assertThat(saved.getSupplyType()).isEqualTo("merchant");
        assertThat(saved.getAuditStatus()).isEqualTo("APPROVED");
    }

    /** 自营免审，没有「审核」这回事 —— 写进历史只会让审核台出现平台自己的商品 */
    @Test
    void A08_运营提交自营商品不记录审核历史() {
        UserContext.set(operator());
        stubCreateDeps();

        service.create(createRequest("自营新品"));

        verify(auditHistoryService, never()).recordSubmit(any(), anyBoolean());
    }

    @Test
    void A08_编辑记录EDIT事件_改前快照必须是改前的值() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "旧标题", "https://img/old.png");
        spu.setDetail("旧详情");
        spu.setVersion(3);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.update(9001L, new SellerProductUpdateRequest("新标题", null,
                "https://img/new.png", null, "新详情", null, null, null, null, null, null, 3));

        ArgumentCaptor<SpuAuditHistoryService.ContentSnapshot> beforeCaptor =
                ArgumentCaptor.forClass(SpuAuditHistoryService.ContentSnapshot.class);
        ArgumentCaptor<SpuEntity> afterCaptor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordEdit(beforeCaptor.capture(), afterCaptor.capture(), eq(false));

        SpuAuditHistoryService.ContentSnapshot before = beforeCaptor.getValue();
        assertThat(before.title()).isEqualTo("旧标题");
        assertThat(before.mainImage()).isEqualTo("https://img/old.png");
        assertThat(before.detail()).isEqualTo("旧详情");
        assertThat(before.version()).isEqualTo(3);

        SpuEntity after = afterCaptor.getValue();
        assertThat(after.getTitle()).isEqualTo("新标题");
        assertThat(after.getMainImage()).isEqualTo("https://img/new.png");
        assertThat(after.getDetail()).isEqualTo("新详情");
        assertThat(after.getVersion()).isEqualTo(4);
    }

    @Test
    void A08_编辑没有实质变化不记录事件() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        service.update(9001L, new SellerProductUpdateRequest("原标题", null,
                "https://img/old.png", null, null, null, null, null, null, null, null, 0));

        verify(auditHistoryService, never()).recordEdit(any(), any(), anyBoolean());
    }

    @Test
    void A08_版本冲突不记录事件() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        spu.setVersion(3);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("新标题", 2)))
                .isInstanceOf(BizException.class);

        verify(auditHistoryService, never()).recordEdit(any(), any(), anyBoolean());
    }

    @Test
    void A08_重新提审记录RESUBMIT事件() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "驳回商品", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setVersion(6);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.resubmit(9001L);

        ArgumentCaptor<SpuAuditHistoryService.ContentSnapshot> beforeCaptor =
                ArgumentCaptor.forClass(SpuAuditHistoryService.ContentSnapshot.class);
        ArgumentCaptor<SpuEntity> afterCaptor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordResubmit(beforeCaptor.capture(), afterCaptor.capture(), eq(false));

        assertThat(beforeCaptor.getValue().auditStatus()).isEqualTo("REJECTED");
        assertThat(beforeCaptor.getValue().version()).isEqualTo(6);
        assertThat(afterCaptor.getValue().getAuditStatus()).isEqualTo("PENDING");
        assertThat(afterCaptor.getValue().getVersion()).isEqualTo(7);
    }

    @Test
    void A08_审核通过记录APPROVE事件() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        service.audit(9001L, "APPROVE", null, 0);

        ArgumentCaptor<SpuAuditHistoryService.ContentSnapshot> beforeCaptor =
                ArgumentCaptor.forClass(SpuAuditHistoryService.ContentSnapshot.class);
        ArgumentCaptor<SpuEntity> afterCaptor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordAudit(beforeCaptor.capture(), afterCaptor.capture());

        assertThat(beforeCaptor.getValue().auditStatus()).isEqualTo("PENDING");
        assertThat(afterCaptor.getValue().getAuditStatus()).isEqualTo("APPROVED");
        assertThat(afterCaptor.getValue().getAuditSource()).isEqualTo("MANUAL");
        assertThat(afterCaptor.getValue().getAuditReason()).isNull();
        assertThat(afterCaptor.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    void A08_审核驳回记录REJECT事件并保留原因() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        service.audit(9001L, "REJECT", "主图与实物不符", 0);

        ArgumentCaptor<SpuEntity> afterCaptor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordAudit(any(), afterCaptor.capture());

        assertThat(afterCaptor.getValue().getAuditStatus()).isEqualTo("REJECTED");
        assertThat(afterCaptor.getValue().getAuditReason()).isEqualTo("主图与实物不符");
        // 驳回压成下架是审核动作的一部分，历史行要能解释「为什么商品从在售变成了下架」
        assertThat(afterCaptor.getValue().getStatus()).isEqualTo("OFF_SHELF");
        assertThat(afterCaptor.getValue().getVersion()).isEqualTo(1);
    }

    @Test
    void A08_抽查判违规记录SAMPLE_REJECT事件并带抽样版本() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "抽中商品", null, "merchant", "APPROVED", "ON_SALE");
        spu.setVersion(3);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        service.rejectBySample(9001L, "主图违规", 3);

        ArgumentCaptor<SpuAuditHistoryService.ContentSnapshot> beforeCaptor =
                ArgumentCaptor.forClass(SpuAuditHistoryService.ContentSnapshot.class);
        ArgumentCaptor<SpuEntity> afterCaptor = ArgumentCaptor.forClass(SpuEntity.class);
        verify(auditHistoryService).recordSampleReject(beforeCaptor.capture(), afterCaptor.capture(), eq(3));

        assertThat(beforeCaptor.getValue().version()).isEqualTo(3);
        assertThat(afterCaptor.getValue().getAuditStatus()).isEqualTo("REJECTED");
        assertThat(afterCaptor.getValue().getAuditSource()).isEqualTo("SAMPLE");
        assertThat(afterCaptor.getValue().getAuditReason()).isEqualTo("主图违规");
        assertThat(afterCaptor.getValue().getVersion()).isEqualTo(4);
        verify(spuImageService).applyAuditStatus(spu, "REJECTED");
    }

    /**
     * 审核历史是 strict 写入：历史表写失败必须让业务失败，不能吞异常继续返回 200。
     * （回滚本身由 @Transactional 保证，单测只能钉住「异常没有被吞掉」。）
     */
    @Test
    void A08_历史写入失败向上抛出_不吞异常() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        doThrow(new IllegalStateException("历史表不可写"))
                .when(auditHistoryService).recordEdit(any(), any(), anyBoolean());

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("新标题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("历史表不可写");
    }

    // ---------------- A-09 · 商品软删 / 回收站 / 30 天清理 ----------------

    private static SpuEntity deletedSpu(long id, long sellerId, LocalDateTime deletedAt) {
        SpuEntity spu = spu(id, sellerId, "回收站商品", null);
        spu.setDeletedAt(deletedAt);
        spu.setStatus("OFF_SHELF");
        return spu;
    }

    @Test
    void A09_删除自己的商品_只写deleted_at并压成下架_不动审核态() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "要删的商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.remove(9001L);

        assertThat(view.deletedAt()).isNotNull();
        assertThat(view.purgeAt()).isNotNull();
        assertThat(view.status()).isEqualTo("OFF_SHELF");
        // 删除不是合规判定：审核结论保留，恢复后无需重新走审核
        assertThat(view.auditStatus()).isEqualTo("APPROVED");

        LambdaUpdateWrapper<SpuEntity> write = captureSpuUpdate();
        assertThat(write.getSqlSegment()).contains("deleted_at IS NULL");
        assertThat(write.getSqlSet()).contains("deleted_at").contains("status");
        // 删除是生命周期动作，不参与 A-07 版本
        assertThat(write.getSqlSet()).doesNotContain("version").doesNotContain("version = version + 1");
    }

    @Test
    void A09_重复删除幂等_不刷新删除时间与保留期() {
        UserContext.set(seller(101L));
        LocalDateTime firstDelete = LocalDateTime.now(ZoneOffset.UTC).minusDays(3);
        when(spuMapper.selectById(9001L)).thenReturn(deletedSpu(9001L, 101L, firstDelete));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.remove(9001L);

        // 保留期按第一次删除算；重复删除若刷新 deleted_at，商品就永远清不掉
        assertThat(view.deletedAt()).isEqualTo(firstDelete.toInstant(ZoneOffset.UTC).toString());
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_不能删除别人的商品_403而不是404() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));

        assertThatThrownBy(() -> service.remove(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_运营不能删除第三方商品_必须由店主自己操作() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "第三方商品", null));

        assertThatThrownBy(() -> service.remove(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第三方店铺");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_回收站商品不能再编辑_上下架_重提_改价() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(
                deletedSpu(9001L, 101L, LocalDateTime.now(ZoneOffset.UTC).minusDays(1)));

        assertThatThrownBy(() -> service.update(9001L, titleUpdate("删了还想改")))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.changeStatus(9001L, "ON_SALE"))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.resubmit(9001L))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.updateSkuPriceStock(9001L,
                new SellerSkuPriceStockRequest(List.of(new SkuPriceStockInput(1L, 1990L, 5)))))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_恢复回收站商品_清空deleted_at并保持下架() {
        UserContext.set(seller(101L));
        SpuEntity spu = deletedSpu(9001L, 101L, LocalDateTime.now(ZoneOffset.UTC).minusDays(10));
        spu.setAuditStatus("APPROVED");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(spuMapper.update(isNull(), any())).thenReturn(1);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.restore(9001L);

        assertThat(view.deletedAt()).isNull();
        assertThat(view.purgeAt()).isNull();
        // 恢复后不自动回到前台：卖家核对内容后自己上架
        assertThat(view.status()).isEqualTo("OFF_SHELF");
        assertThat(view.auditStatus()).isEqualTo("APPROVED");

        LambdaUpdateWrapper<SpuEntity> write = captureSpuUpdate();
        assertThat(write.getSqlSegment()).contains("deleted_at");
        assertThat(write.getSqlSet()).contains("deleted_at");
    }

    @Test
    void A09_超过30天保留期不能恢复_即使清理任务还没跑() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(
                deletedSpu(9001L, 101L, LocalDateTime.now(ZoneOffset.UTC).minusDays(31)));

        assertThatThrownBy(() -> service.restore(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保留期")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE));
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_恢复不在回收站的商品_业务错误() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "正常商品", null));

        assertThatThrownBy(() -> service.restore(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在回收站");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A09_清理只物理删除超过保留期的商品_先删SPU再删SKU() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<SpuEntity> expired = new Page<>(1, 500, false);
        expired.setRecords(List.of(
                deletedSpu(1L, 101L, now.minusDays(31)),
                deletedSpu(2L, 101L, now.minusDays(45))));
        when(spuMapper.selectPage(any(), any())).thenReturn(expired);
        when(spuMapper.delete(any())).thenReturn(2);
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of());
        when(skuMapper.delete(any())).thenReturn(2);

        int purged = service.purgeExpired(now);

        assertThat(purged).isEqualTo(2);
        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> selectCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(), selectCaptor.capture());
        assertThat(selectCaptor.getValue().getSqlSegment())
                .contains("deleted_at IS NOT NULL")
                .contains("deleted_at <");

        ArgumentCaptor<LambdaQueryWrapper<SkuEntity>> skuCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(skuMapper).delete(skuCaptor.capture());
        assertThat(skuCaptor.getValue().getSqlSegment()).contains("spu_id");
        assertThat(skuCaptor.getValue().getParamNameValuePairs().values()).contains(1L, 2L);
        // A-11：图片随商品物理删除
        verify(spuImageService).deleteBySpuIds(any());
        // A-12：物理删除后没有任何 updated_at 可供扫描，必须发事件让搜索侧摘文档
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SearchEvents.SpuPurged.class);
        assertThat(((SearchEvents.SpuPurged) eventCaptor.getValue()).spuIds())
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void A09_清理期间被并发恢复的商品_不删它的SKU() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SpuEntity a = deletedSpu(1L, 101L, now.minusDays(31));
        SpuEntity b = deletedSpu(2L, 101L, now.minusDays(31));
        Page<SpuEntity> expired = new Page<>(1, 500, false);
        expired.setRecords(List.of(a, b));
        when(spuMapper.selectPage(any(), any())).thenReturn(expired);
        // 条件删除只删掉了 1（b 在那一刻被卖家恢复）
        when(spuMapper.delete(any())).thenReturn(1);
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(b));
        when(skuMapper.delete(any())).thenReturn(1);

        int purged = service.purgeExpired(now);

        assertThat(purged).isEqualTo(1);
        ArgumentCaptor<LambdaQueryWrapper<SkuEntity>> skuCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(skuMapper).delete(skuCaptor.capture());
        // 先触发 SQL 片段渲染：MyBatis-Plus 的命名参数是惰性填充的，
        // 直接读 paramNameValuePairs 会得到空 Map（第一次跑这条用例就踩到了）
        assertThat(skuCaptor.getValue().getSqlSegment()).contains("spu_id");
        // 若先删 SKU 再删 SPU，恢复的 b 会变成「商品还在、规格全没了」的残缺数据
        assertThat(skuCaptor.getValue().getParamNameValuePairs().values())
                .contains(1L)
                .doesNotContain(2L);
    }

    @Test
    void A09_回收站列表只看已删除_正常列表排除回收站() {
        UserContext.set(seller(101L));
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(skuMapper.selectList(any())).thenReturn(List.of());

        service.list(null, null, null, true, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("deleted_at IS NOT NULL")
                .contains("ORDER BY deleted_at DESC");

        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<>());
        service.list(null, null, null, false, 1, 20);
        ArgumentCaptor<LambdaQueryWrapper<SpuEntity>> normalCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper, times(2)).selectPage(any(), normalCaptor.capture());
        assertThat(normalCaptor.getAllValues().get(1).getSqlSegment())
                .contains("deleted_at IS NULL");
    }

    // ---------------- A-14 · 规格增删改（结构） ----------------

    private static SkuEntity structureSku(long id, long spuId, String code, String specName,
                                          long price, int stock) {
        SkuEntity sku = sku(id, spuId, price, stock);
        sku.setSkuCode(code);
        sku.setSpecJson("{\"规格\":\"" + specName + "\"}");
        return sku;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<SkuEntity> captureSkuUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<SkuEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(skuMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    /**
     * 新增规格是内容变更：必须重审、版本 +1、价格区间重算，并留下规格改前/改后文本。
     * 新规格的 sku_code 由服务端按现有最大序号 +1 生成（不信任客户端）。
     */
    @Test
    void A14_新增规格_触发重审_版本递增_价格区间同步并写历史() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        spu.setAuditSource("AUTO");
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 5);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a), List.of(a, b));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of(
                        new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null),
                        new SellerSkuStructureInput(null, "5kg", 3990L, 5, null))));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.removed()).isZero();
        assertThat(result.reaudited()).isTrue();
        assertThat(spu.getVersion()).isEqualTo(1);
        assertThat(spu.getMinPrice()).isEqualTo(1990L);
        assertThat(spu.getMaxPrice()).isEqualTo(3990L);
        assertThat(result.product().minPrice()).isEqualTo(1990L);
        assertThat(result.product().maxPrice()).isEqualTo(3990L);

        ArgumentCaptor<SkuEntity> inserted = ArgumentCaptor.forClass(SkuEntity.class);
        verify(skuMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSkuCode()).isEqualTo("SPU9001-2");
        assertThat(inserted.getValue().getSpecJson()).contains("5kg");
        assertThat(inserted.getValue().getPrice()).isEqualTo(3990L);
        assertThat(inserted.getValue().getStock()).isEqualTo(5);

        LambdaUpdateWrapper<SpuEntity> write = captureSpuUpdate();
        assertVersionGuarded(write, 0);
        assertThat(write.getSqlSet()).contains("min_price", "max_price");

        verify(auditHistoryService).recordSkuEdit(any(), eq(spu),
                contains("2kg"), contains("5kg"), eq(false));
        verify(spuImageService).sync(spu);
    }

    /** 删除规格前必须问交易域；没卖过的可以删，新规格编码不能复用刚删掉的序号 */
    @Test
    void A14_删除无订单规格_真删并用交易域确认_编码序号不复用() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 0);
        SkuEntity c = structureSku(13L, 9001L, "SPU9001-3", "8kg", 5990L, 2);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b), List.of(a, c));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of(
                        new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null),
                        new SellerSkuStructureInput(null, "8kg", 5990L, 2, null))));

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.reaudited()).isTrue();

        ArgumentCaptor<SkuUsageQuery> usageQuery = ArgumentCaptor.forClass(SkuUsageQuery.class);
        verify(skuUsageClient).usage(usageQuery.capture());
        assertThat(usageQuery.getValue().skuIds()).containsExactly(12L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<SkuEntity>> deleteCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(skuMapper).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getSqlSegment()).contains("spu_id", "id");

        ArgumentCaptor<SkuEntity> inserted = ArgumentCaptor.forClass(SkuEntity.class);
        verify(skuMapper).insert(inserted.capture());
        // 序号 3 已被这次删除的行占用过，新行必须排到 3 —— 复用会与历史订单的 sku_code 撞车
        assertThat(inserted.getValue().getSkuCode()).isEqualTo("SPU9001-3");
    }

    /** 卖过的规格禁止物理删除：售后与对账需要这个锚点；拒绝时必须一行都没改 */
    @Test
    void A14_有订单的规格禁止删除_且零写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 0);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b));
        when(skuUsageClient.usage(any())).thenReturn(ApiResponse.ok(List.of(new SkuUsageInfo(12L, 1))));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已有订单")
                .hasMessageContaining("5kg");

        verify(spuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).insert(any(SkuEntity.class));
        verify(skuMapper, never()).delete(any());
        verify(auditHistoryService, never()).recordSkuEdit(any(), any(), any(), any(), anyBoolean());
    }

    /** 还有冻结库存（未支付订单占着）也不能删 —— 本地门禁先于跨域查询 */
    @Test
    void A14_冻结库存大于0的规格禁止删除_不发起交易域查询() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 0);
        b.setFrozenStock(2);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("冻结库存 2");

        verify(skuUsageClient, never()).usage(any());
        verify(spuMapper, never()).update(isNull(), any());
    }

    /** 交易域不可用时 fail-closed：删除不可逆，宁可不删也不能赌「应该没订单」 */
    @Test
    void A14_交易域不可用时拒绝删除_并说明未删除任何规格() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 0);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b));
        when(skuUsageClient.usage(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂时无法确认")
                .hasMessageContaining("未删除任何规格");

        verify(spuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).delete(any());
    }

    /**
     * 仅改价格 / 库存不重审、不升版本 —— 与 /skus/price-stock 同一口径。
     * 版本不动是刻意的：调价是高频运营动作，每次都 409 会让卖家无法连续改价。
     */
    @Test
    void A14_仅改价改库存_不重审不升版本_只同步价格区间() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        spu.setAuditSource("AUTO");
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 5);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a, b), List.of(a, b));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of(
                        new SellerSkuStructureInput(11L, "2kg", 2990L, 12, null),
                        new SellerSkuStructureInput(12L, "5kg", 3990L, 5, null))));

        assertThat(result.reaudited()).isFalse();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.added()).isZero();
        assertThat(result.removed()).isZero();
        assertThat(result.product().minPrice()).isEqualTo(2990L);
        assertThat(spu.getVersion()).isZero();
        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getAuditSource()).isEqualTo("AUTO");

        LambdaUpdateWrapper<SkuEntity> skuWrite = captureSkuUpdate();
        assertThat(skuWrite.getSqlSet()).contains("price", "stock").doesNotContain("spec_json");
        LambdaUpdateWrapper<SpuEntity> spuWrite = captureSpuUpdate();
        assertThat(spuWrite.getSqlSet()).contains("min_price", "max_price")
                .doesNotContain("audit_status")
                .doesNotContain("version");
        verify(skuMapper, never()).insert(any(SkuEntity.class));
        verify(skuMapper, never()).delete(any());
        verify(auditHistoryService, never()).recordSkuEdit(any(), any(), any(), any(), anyBoolean());
    }

    /** 一字未改：不写库、不留历史，也不制造一次无意义的重审 */
    @Test
    void A14_规格无变化_直接返回且零写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null))));

        assertThat(result.added()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.removed()).isZero();
        assertThat(result.reaudited()).isFalse();
        verify(spuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).insert(any(SkuEntity.class));
        verify(skuMapper, never()).delete(any());
    }

    /** 改规格名同样属于内容变更：曾被驳回的商品改完仍要人审，不能借改名洗白 */
    @Test
    void A14_改规格名_被驳回商品仍转人工并恢复在售() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null, "merchant", "REJECTED", "OFF_SHELF");
        spu.setRejectedAt(LocalDateTime.now(ZoneOffset.UTC));
        spu.setAuditSource("MANUAL");
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity renamed = structureSku(11L, 9001L, "SPU9001-1", "2kg装", 1990L, 10);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a), List.of(renamed));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg装", 1990L, 10, null))));

        assertThat(result.reaudited()).isTrue();
        assertThat(spu.getAuditStatus()).isEqualTo("PENDING");
        assertThat(spu.getAuditSource()).isNull();
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
        assertThat(spu.getVersion()).isEqualTo(1);

        LambdaUpdateWrapper<SpuEntity> write = captureSpuUpdate();
        assertThat(write.getSqlSet()).contains("status", "audit_status");
        verify(auditHistoryService).recordSkuEdit(any(), eq(spu),
                contains("2kg（价格"), contains("2kg装（价格"), eq(false));
    }

    /** 自营免审：结构变更照样升版本（内容变了），但不进审核流程 */
    @Test
    void A14_自营商品结构变更_不重审但升版本() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        SkuEntity b = structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 5);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a), List.of(a, b));

        SellerSkuStructureResultView result = service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of(
                        new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null),
                        new SellerSkuStructureInput(null, "5kg", 3990L, 5, null))));

        assertThat(result.reaudited()).isFalse();
        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getVersion()).isEqualTo(1);
        verify(auditHistoryService, never()).recordSkuEdit(any(), any(), any(), any(), anyBoolean());
    }

    /** 运营同样不能直接改第三方商品的规格结构（E23 收窄的延续） */
    @Test
    void A14_运营不能改第三方商品的规格结构() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "第三方商品", null));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(null, "2kg", 1990L, 10, null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第三方店铺");
        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A14_版本冲突与未知规格id都拒绝且不写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        spu.setVersion(2);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(1,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被其他人修改");

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(2,
                        List.of(new SellerSkuStructureInput(99L, "2kg", 1990L, 10, null)))))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        verify(spuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).update(isNull(), any());
    }

    @Test
    void A14_空列表_重复规格名_超上限_价格库存边界均拒绝() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        SkuEntity a = structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of())))
                .hasMessageContaining("至少需要一个规格");
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, List.of(
                        new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null),
                        new SellerSkuStructureInput(null, "2kg", 2990L, 1, null)))))
                .hasMessageContaining("规格名重复");
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0, java.util.stream.IntStream.rangeClosed(1, 21)
                        .mapToObj(i -> new SellerSkuStructureInput(null, "规格" + i,
                                1000L + i, 1, null))
                        .toList())))
                .hasMessageContaining("最多支持 20 个规格");
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 0L, 10, null)))))
                .hasMessageContaining("价格必须大于 0");
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, -1, null)))))
                .hasMessageContaining("库存必须是不小于 0");

        verify(spuMapper, never()).update(isNull(), any());
        verify(skuMapper, never()).update(isNull(), any());
    }

    @Test
    void A14_缺版本_回收站商品_越权访问都拒绝() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(null,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .hasMessageContaining("缺少版本号");

        when(spuMapper.selectById(9001L)).thenReturn(
                deletedSpu(9001L, 101L, LocalDateTime.now(ZoneOffset.UTC).minusDays(1)));
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));
        assertThatThrownBy(() -> service.updateSkuStructure(9001L,
                new SellerSkuStructureRequest(0,
                        List.of(new SellerSkuStructureInput(11L, "2kg", 1990L, 10, null)))))
                .hasMessageContaining("不属于当前账号");

        verify(spuMapper, never()).update(isNull(), any());
    }

    @Test
    void A14_规格占用查询_按交易域结果返回且查询本商品全部规格() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "商品", null));
        when(skuMapper.selectList(any())).thenReturn(List.of(
                structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10),
                structureSku(12L, 9001L, "SPU9001-2", "5kg", 3990L, 5)));
        when(skuUsageClient.usage(any())).thenReturn(ApiResponse.ok(
                List.of(new SkuUsageInfo(12L, 3))));

        List<SellerSkuUsageView> usage = service.skuUsage(9001L);

        assertThat(usage).containsExactly(new SellerSkuUsageView(12L, 3));
        ArgumentCaptor<SkuUsageQuery> captor = ArgumentCaptor.forClass(SkuUsageQuery.class);
        verify(skuUsageClient).usage(captor.capture());
        assertThat(captor.getValue().skuIds()).containsExactly(11L, 12L);
    }

    @Test
    void A14_规格占用查询_交易域故障时报错而不是报空() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "商品", null));
        when(skuMapper.selectList(any())).thenReturn(List.of(
                structureSku(11L, 9001L, "SPU9001-1", "2kg", 1990L, 10)));
        when(skuUsageClient.usage(any())).thenThrow(new RuntimeException("trade down"));

        assertThatThrownBy(() -> service.skuUsage(9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂时无法确认");
    }
}
