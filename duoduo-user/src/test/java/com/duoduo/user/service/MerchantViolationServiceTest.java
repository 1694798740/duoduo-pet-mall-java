package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.user.dto.MerchantDtos.MerchantViolationInfo;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.support.MerchantStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家违规计数与升级停业单测（E28）。
 *
 * <h2>这组测试守的是什么</h2>
 * 这段代码会**自己把一家店关掉**，而它做错的两个方向都很安静：
 * 计数丢了（并发覆盖）→ 该停的店永远停不了；条件写松了 → 不该停的店被停。
 * 所以这里的断言不能只看返回值，必须看到**下发到数据库的 SQL 片段**：
 *
 * <ul>
 *   <li><b>自增必须在数据库层做</b>：读出来 +1 再写回，并发下两次违规会各自读到 1、
 *       都写回 2 —— 一次违规就这么消失了。断言 {@code getSqlSet()} 里确实是
 *       {@code violation_count = violation_count + 1}；</li>
 *   <li><b>停业必须带状态条件</b>：{@code WHERE status='APPROVED'}，
 *       且在抢不到时（changed=0）不许继续发通知、不许写留痕 ——
 *       否则会出现「店铺状态没变，商家却收到停业通知」；</li>
 *   <li><b>计数只增不减，唯一清零点是复业</b>：抽查复核通过不是「撤销以前的违规」。</li>
 * </ul>
 *
 * <p>注意用了 {@code isNull()} 而不是字面量 {@code null} 作为第一个实参：
 * Mockito 一旦在某个参数上用了 matcher，其余参数也必须用 matcher，混写会抛
 * {@code InvalidUseOfMatchersException}。
 */
class MerchantViolationServiceTest {

    private static final long MERCHANT_ID = 710000000000000101L;
    private static final long OWNER_USER_ID = 710000000000000001L;

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantLicenseNotifier notifier = mock(MerchantLicenseNotifier.class);
    /**
     * E18：留痕从「直接 writeAudit 写 mapper」收敛到唯一入口 {@link AuditLogService}。
     * 单测里 mock 服务而不是 mock mapper，是因为要断言的正是「服务层调了这个统一入口」——
     * 直接 mock mapper 会让「服务层绕过统一入口自己 insert」这种退化也通过。
     */
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private final MerchantViolationService service = new MerchantViolationService(
            merchantMapper, notifier, auditLogService);

    /**
     * 注册 MyBatis-Plus 的实体元数据：{@code LambdaUpdateWrapper.eq(MerchantEntity::getX, v)}
     * 要在运行期把方法引用解析成列名，纯 Mockito 单测里没有容器帮你建这块缓存。
     */
    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MerchantEntity.class);
    }

    private static MerchantEntity merchant(String status, int violationCount) {
        MerchantEntity m = new MerchantEntity();
        m.setId(MERCHANT_ID);
        m.setOwnerUserId(OWNER_USER_ID);
        m.setName("豆豆宠物用品店");
        m.setStatus(status);
        m.setViolationCount(violationCount);
        return m;
    }

    /** 把「刚 +1 之后那一行」摆好：方法会 selectById 读回最新计数 */
    private void stubReadBack(int countAfter) {
        when(merchantMapper.selectById(MERCHANT_ID))
                .thenReturn(merchant(MerchantStatus.APPROVED.name(), countAfter));
    }

    /** 捕获下发给数据库的所有 update 的 SET 片段（返回值断言看不出「自增是不是原子」） */
    @SuppressWarnings("unchecked")
    private List<String> capturedSqlSets() {
        ArgumentCaptor<Wrapper<MerchantEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(merchantMapper, atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .map(w -> String.valueOf(w.getSqlSet()))
                .toList();
    }

    // ---------------- 找不到店铺 ----------------

    @Test
    @DisplayName("ownerUserId 为空 → 直接返回 null，不查库")
    void nullOwnerReturnsNull() {
        assertThat(service.markViolation(null, "测试")).isNull();
        verify(merchantMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("找不到店铺（历史数据 / 自营）→ 记 WARN 返回 null，不抛异常")
    void missingMerchantIsNotAnError() {
        when(merchantMapper.selectOne(any())).thenReturn(null);

        assertThat(service.markViolation(OWNER_USER_ID, "商品违规")).isNull();

        // 商品那边已经驳回下架了，这里再抛只会把一次已完成的操作变成一次报错
        verify(merchantMapper, never()).update(isNull(), any());
    }

    // ---------------- 计数自增 ----------------

    @Test
    @DisplayName("★ 计数用数据库原子自增（violation_count = violation_count + 1），不是读出来 +1 再写回")
    void countIsIncrementedAtomically() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 1));
        stubReadBack(2);
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "商品内容违规");

        assertThat(info).isNotNull();
        assertThat(info.violationCount()).isEqualTo(2);
        assertThat(info.suspended()).isFalse();
        assertThat(capturedSqlSets())
                .as("自增必须在 SQL 里做，否则并发下会丢计数")
                .anySatisfy(sql -> assertThat(sql).contains("violation_count = violation_count + 1"));
    }

    @Test
    @DisplayName("未达阈值（1、2 次）不停业、不通知")
    void belowThresholdDoesNotSuspend() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 0));
        stubReadBack(1);
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "商品内容违规");

        assertThat(info.suspended()).isFalse();
        verify(notifier, never()).notifyViolationSuspended(any(), anyInt(), any());
        verify(notifier, never()).offShelfAll(anyLong());
    }

    // ---------------- 达阈值升级停业 ----------------

    @Test
    @DisplayName("★ 累计到 3 次且店铺在营 → 停业 + 通知 + 联动下架 + 审计留痕")
    void thirdViolationSuspendsShop() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 2));
        stubReadBack(3);                                  // +1 之后正好到阈值
        when(merchantMapper.update(isNull(), any())).thenReturn(1);
        when(notifier.offShelfAll(OWNER_USER_ID)).thenReturn(5);

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "第 3 件商品违规");

        assertThat(info.suspended()).isTrue();
        assertThat(info.violationCount()).isEqualTo(3);
        verify(notifier).notifyViolationSuspended(any(), eq(3), eq("第 3 件商品违规"));
        verify(notifier).offShelfAll(OWNER_USER_ID);
        verify(auditLogService).record(any());
        // 停业用的是条件更新：CAS 里必须带 status='APPROVED'
        verify(merchantMapper, atLeastOnce()).update(isNull(), any());
    }

    @Test
    @DisplayName("★ 抢不到条件更新（changed=0）→ 不停业、不发通知：避免「状态没变却通知商家」")
    void lostCasDoesNotNotify() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 2));
        stubReadBack(3);
        when(merchantMapper.update(isNull(), any())).thenReturn(0);   // 并发下已被别人改掉

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "第 3 件商品违规");

        assertThat(info.suspended()).isFalse();
        verify(notifier, never()).notifyViolationSuspended(any(), anyInt(), any());
        verify(notifier, never()).offShelfAll(anyLong());
    }

    @Test
    @DisplayName("已达阈值但店铺本来就不在营（如已过期停业）→ 不再停一次")
    void alreadySuspendedIsNotSuspendedAgain() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.SUSPENDED.name(), 5));
        when(merchantMapper.selectById(MERCHANT_ID))
                .thenReturn(merchant(MerchantStatus.SUSPENDED.name(), 6));
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "商品违规");

        assertThat(info.suspended()).isFalse();
        verify(notifier, never()).notifyViolationSuspended(any(), anyInt(), any());
    }

    @Test
    @DisplayName("★ 审计留痕失败不阻断停业：店铺该停还是要停，留痕是 best-effort")
    void auditFailureDoesNotBlockSuspension() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 2));
        stubReadBack(3);
        when(merchantMapper.update(isNull(), any())).thenReturn(1);
        // 注意桩的是「返回 false」而不是「抛异常」：AuditLogService.record 的契约是
        // **永不外抛**、用返回值表达失败（见其类注释）。让 mock 抛异常测的就不是真实契约，
        // 而是一个本不该存在的场景 —— 那样的用例会给服务层加上一层没必要的 try/catch。
        when(auditLogService.record(any())).thenReturn(false);

        MerchantViolationInfo info = service.markViolation(OWNER_USER_ID, "第 3 件商品违规");

        assertThat(info.suspended()).isTrue();
        verify(notifier).notifyViolationSuspended(any(), eq(3), any());
    }

    // ---------------- 入驻抽查判违规：不攒次数，直接停业 ----------------

    @Test
    @DisplayName("★ 入驻资质判违规（suspendByAudit）不看次数：第 1 次就停业 —— 执照是假的，主体资格归零")
    void auditViolationSuspendsImmediately() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.APPROVED.name(), 0));
        stubReadBack(1);
        when(merchantMapper.update(isNull(), any())).thenReturn(1);
        when(notifier.offShelfAll(OWNER_USER_ID)).thenReturn(3);

        MerchantViolationInfo info = service.suspendByAudit(OWNER_USER_ID, "执照与工商信息不符");

        assertThat(info.suspended()).isTrue();
        assertThat(info.violationCount()).isEqualTo(1);
        verify(notifier).notifyViolationSuspended(any(), eq(1), eq("执照与工商信息不符"));
        verify(auditLogService).record(any());
    }

    @Test
    @DisplayName("店铺已不是 APPROVED 时，suspendByAudit 只记数不再停业（不重复处理）")
    void auditViolationOnSuspendedShopOnlyCounts() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(MerchantStatus.SUSPENDED.name(), 1));
        when(merchantMapper.selectById(MERCHANT_ID))
                .thenReturn(merchant(MerchantStatus.SUSPENDED.name(), 2));
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        MerchantViolationInfo info = service.suspendByAudit(OWNER_USER_ID, "执照与工商信息不符");

        assertThat(info.suspended()).isFalse();
        assertThat(info.violationCount()).isEqualTo(2);
        verify(notifier, never()).notifyViolationSuspended(any(), anyInt(), any());
    }

    // ---------------- 复业清零 ----------------

    @Test
    @DisplayName("★ 复业清零违规计数（唯一重置点）：否则一犯即停，那不是既定规则")
    void resetOnResume() {
        service.resetViolations(MERCHANT_ID);

        List<String> sqlSets = capturedSqlSets();
        assertThat(sqlSets).anySatisfy(sql -> {
            assertThat(sql).contains("violation_count");
            assertThat(sql).doesNotContain("violation_count + 1");
        });
    }

    @Test
    @DisplayName("清零传 null 时不动作（防御式）")
    void resetWithNullDoesNothing() {
        service.resetViolations(null);
        verify(merchantMapper, never()).update(isNull(), any());
    }
}
