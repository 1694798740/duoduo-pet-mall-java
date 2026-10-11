package com.duoduo.product.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.client.MembershipClient;
import com.duoduo.product.client.MerchantLicenseClient;
import com.duoduo.product.client.MerchantRiskClient;
import com.duoduo.product.client.dto.MembershipInfo;
import com.duoduo.product.client.dto.MerchantLicenseInfo;
import com.duoduo.product.client.dto.MerchantRiskInfo;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditView;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductUpdateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProductDtos.SellerSkuInput;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.CatalogLookup;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private final BannedWordGuard bannedWordGuard = mock(BannedWordGuard.class);
    private final CatalogLookup catalogLookup = mock(CatalogLookup.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SellerProductService service = new SellerProductService(
            spuMapper, skuMapper, membershipClient, merchantLicenseClient, merchantRiskClient,
            new SnowflakeIdGenerator(1, 1), bannedWordGuard, catalogLookup, objectMapper);

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
        return new SellerProductUpdateRequest(title, null, null, null, null, null, null);
    }

    // ---------------- 归属与准入（沿用旧版断言，防止改动走形） ----------------

    @Test
    void 卖家可以改自己商品的标题与主图() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "旧标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        SellerProductView view = service.update(9001L,
                new SellerProductUpdateRequest("新标题", null, "https://img/1.png", null, null, null, null));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.mainImage()).isEqualTo("https://img/1.png");
        verify(spuMapper).updateById(spu);
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
        assertThatThrownBy(() -> service.update(9001L,
                new SellerProductUpdateRequest(null, null, "   ", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主图不能为空");

        // 标题不允许清空
        assertThatThrownBy(() -> service.update(9001L, titleUpdate("   ")))
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
                null, null, null, null));

        // 无变化不发 UPDATE：省一次写，也避免 updated_at 被无意义地刷新
        // （刷新 updated_at 还会让索引同步任务白跑一轮）
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
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
        verify(spuMapper).updateById(spu);
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

        AdminProductAuditView view = service.audit(9001L, "APPROVE", null);

        assertThat(spu.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(spu.getAuditReason()).isNull();
        assertThat(spu.getAuditBy()).isEqualTo(900L);
        assertThat(spu.getAuditAt()).isNotNull();
        // 通过时不动 status：卖家提交时期望的就是在售，通过即自动出现在前台；
        // 若它同时被卖家手动下架过，则保持下架（尊重卖家的选择）
        assertThat(spu.getStatus()).isEqualTo("ON_SALE");
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
    }

    @Test
    void 审核驳回_必须填原因且商品被压成下架() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 101L, "待审商品", null, "merchant", "PENDING", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of());

        // 不填原因：卖家只能猜着改，审核结论也留不下可追溯的解释
        assertThatThrownBy(() -> service.audit(9001L, "REJECT", "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("驳回时必须填写原因");

        service.audit(9001L, "REJECT", "主图与实物不符");

        assertThat(spu.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(spu.getAuditReason()).isEqualTo("主图与实物不符");
        // 驳回同时压成下架：让卖家视角的语义一致（被驳回的货不该挂在「在售」里）
        assertThat(spu.getStatus()).isEqualTo("OFF_SHELF");
    }

    @Test
    void 审核动作非法或对象是自营商品_一律拒绝() {
        UserContext.set(operator());
        SpuEntity selfSpu = spu(9001L, 0L, "自营商品", null, "self", "APPROVED", "ON_SALE");
        when(spuMapper.selectById(9001L)).thenReturn(selfSpu);

        assertThatThrownBy(() -> service.audit(9001L, "PASS", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 APPROVE / REJECT");

        // 自营免审：走到这里说明调用方按错了对象，直接拒绝比「静默改个状态」安全
        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无需审核");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 非运营不能进审核台() {
        UserContext.set(seller(101L));

        assertThatThrownBy(() -> service.audit(9001L, "APPROVE", null))
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
        verify(spuMapper).updateById(spu);
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

    @Test
    void 运营可以跨店查看与编辑() {
        UserContext.set(operator());
        SpuEntity spu = spu(9001L, 202L, "别家商品", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SellerProductView view = service.update(9001L, titleUpdate("运营改标题"));

        assertThat(view.title()).isEqualTo("运营改标题");
        verify(spuMapper).updateById(spu);
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
}
