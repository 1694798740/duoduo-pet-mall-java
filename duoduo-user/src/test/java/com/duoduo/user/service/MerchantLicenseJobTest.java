package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.support.LicenseExpiry;
import com.duoduo.user.support.MerchantStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资质到期扫描任务单测（E15）。
 *
 * <h2>这组测试守的是什么</h2>
 * 这个任务的两类错误都不会有人主动发现：
 * <ul>
 *   <li><b>重复提醒</b>：商家每天收到一条「您的执照快到期了」，客服会被打爆；</li>
 *   <li><b>漏提醒 / 漏停业</b>：商家压根不知道，店被停了才来找 —— 而平台这边
 *       日志上一切正常。所以「通知发失败时不能记档位」这条必须钉死，
 *       它是唯一能防止「库里记着发过了、实际没发出去」的机制。</li>
 * </ul>
 * 另外钉住一条并发安全：停业用<b>条件更新</b>（WHERE status='APPROVED'），
 * 不能退化成「按 id 覆盖」—— 否则会把商家刚提交的续期静默压回停业态。
 */
class MerchantLicenseJobTest {

    private static final long MERCHANT_ID = 710000000000000101L;
    private static final long OWNER_ID = 710000000000000001L;

    /** 判定基准：2026-06-15 10:00 UTC */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 10, 0);

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantMaterialMapper materialMapper = mock(MerchantMaterialMapper.class);
    private final MerchantLicenseNotifier notifier = mock(MerchantLicenseNotifier.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final SchedLeaderLock leaderLock = mock(SchedLeaderLock.class);

    private final MerchantLicenseJob job = new MerchantLicenseJob(merchantMapper, materialMapper,
            notifier, auditLogService, idGenerator, leaderLock, true);

    @BeforeAll
    static void initMybatisTableInfo() {
        // LambdaUpdateWrapper 在**运行期**靠 TableInfo 缓存把方法引用翻译成列名，
        // 纯 Mockito 单测里没有 Spring 容器去初始化它 —— 不预热就会在
        // `.set(MerchantEntity::getStatus, …)` 那一行抛
        // 「MybatisPlus can not find lambda cache for this entity」。
        // 这个错单测里看着像产品缺陷，其实是测试环境缺一步（E14 已在商品域踩过一次）。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MerchantEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenReturn(710000000000000301L);
        when(merchantMapper.update(any(), any())).thenReturn(1);
        when(notifier.notifyExpiring(any(), anyString(), any(), anyLong())).thenReturn(true);
        when(notifier.notifySuspended(any(), anyString(), any())).thenReturn(true);
        when(notifier.offShelfAll(anyLong())).thenReturn(3);
        when(materialMapper.selectOne(any())).thenReturn(null);
    }

    // ---------------- 过期停业 ----------------

    @Test
    @DisplayName("★ 过期即停业：状态改 SUSPENDED、写明原因、通知店主、联动下架在售商品")
    void expiredMerchantIsSuspended() {
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 14));
        scan(List.of(merchant), List.of());

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.suspended()).isEqualTo(1);
        assertThat(merchant.getStatus()).isEqualTo("SUSPENDED");
        verify(notifier).notifySuspended(any(), eq("营业执照"), eq(endOfDay(2026, 6, 14)));
        verify(notifier).offShelfAll(OWNER_ID);       // 「停售」不是可选项
        verify(auditLogService).record(any());       // 平台单方面停业必须留痕
    }

    @Test
    @DisplayName("★ 停业用条件更新（WHERE status='APPROVED'），不能按 id 覆盖 —— 否则会吞掉刚提交的续期")
    void suspendUsesConditionalUpdate() {
        merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 14));
        scan(List.of(merchantEntity), List.of());

        job.sweepOnce(NOW);

        ArgumentCaptor<Wrapper<MerchantEntity>> captor = wrapperCaptor();
        verify(merchantMapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        // 断言的是「存在状态条件」这条不变量，而不是具体 SQL 文本
        assertThat(sql).contains("status");
    }

    @Test
    @DisplayName("条件更新影响 0 行（并发续期改掉了状态）→ 不通知、不下架、不计入停业数")
    void suspendSkippedWhenStateChangedConcurrently() {
        when(merchantMapper.update(any(), any())).thenReturn(0);
        merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 14));
        scan(List.of(merchantEntity), List.of());

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.suspended()).isZero();
        verify(notifier, never()).notifySuspended(any(), anyString(), any());
        verify(notifier, never()).offShelfAll(anyLong());
    }

    @Test
    @DisplayName("下架失败要计数（店已停业但商品可能还在卖 = 需要人立刻知道的不一致）")
    void offShelfFailureIsCounted() {
        when(notifier.offShelfAll(anyLong())).thenReturn(-1);
        merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 14));
        scan(List.of(merchantEntity), List.of());

        assertThat(job.sweepOnce(NOW).offShelfFailed()).isEqualTo(1);
    }

    // ---------------- 临期提醒 ----------------

    @Test
    @DisplayName("进入 30 天档 → 发一条提醒并记下档位")
    void firstStageRemindsOnce() {
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 7, 10));
        assertThat(LicenseExpiry.stageOf(merchant.getLicenseExpireAt(), NOW))
                .isEqualTo(LicenseExpiry.Stage.T30);
        scan(List.of(), List.of(merchant));

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.reminded()).isEqualTo(1);
        verify(notifier).notifyExpiring(any(), eq("营业执照"), any(), anyLong());
        assertThat(writtenStage()).isEqualTo("T30");
    }

    @Test
    @DisplayName("★ 同一档位再扫到 → 不重复打扰（这是「记档位而不是记布尔值」的意义）")
    void sameStageDoesNotRemindAgain() {
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 7, 10));
        merchant.setExpireNotifyStage("T30");       // 上一轮已提醒过
        scan(List.of(), List.of(merchant));

        assertThat(job.sweepOnce(NOW).reminded()).isZero();
        verify(notifier, never()).notifyExpiring(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("★ 档位变深（T30 → T7）→ 再提醒一次，共两条而不是每天一条")
    void deeperStageRemindsAgain() {
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 20));
        merchant.setExpireNotifyStage("T30");
        assertThat(LicenseExpiry.stageOf(merchant.getLicenseExpireAt(), NOW))
                .isEqualTo(LicenseExpiry.Stage.T7);
        scan(List.of(), List.of(merchant));

        assertThat(job.sweepOnce(NOW).reminded()).isEqualTo(1);
        assertThat(writtenStage()).isEqualTo("T7");
    }

    @Test
    @DisplayName("★ 通知发失败 → 不记档位，下一轮重试（记了就永远发不出去，且没人能发现）")
    void failedNotifyDoesNotMarkStage() {
        when(notifier.notifyExpiring(any(), anyString(), any(), anyLong())).thenReturn(false);
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 7, 10));
        scan(List.of(), List.of(merchant));

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.notifyFailed()).isEqualTo(1);
        assertThat(report.reminded()).isZero();
        assertThat(merchant.getExpireNotifyStage()).isNull();
    }

    @Test
    @DisplayName("未进提醒区间（还有 60 天）→ 不发")
    void farAwayExpiryIsSilent() {
        MerchantEntity merchant = merchant("BUSINESS_LICENSE", endOfDay(2026, 8, 20));
        scan(List.of(), List.of(merchant));

        assertThat(job.sweepOnce(NOW).reminded()).isZero();
        verify(notifier, never()).notifyExpiring(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("材料行查不到时用「经营资质」兜底文案，而不是不发")
    void missingMaterialFallsBackToGenericLabel() {
        merchant("BUSINESS_LICENSE", endOfDay(2026, 7, 10));
        when(materialMapper.selectOne(any())).thenReturn(null);   // 必须在 merchant() 之后
        scan(List.of(), List.of(merchantEntity));

        job.sweepOnce(NOW);

        verify(notifier).notifyExpiring(any(), eq("经营资质"), any(), anyLong());
    }

    @Test
    @DisplayName("提醒也走条件更新：并发续期进入审核后不再写档位")
    void remindUsesConditionalUpdate() {
        when(merchantMapper.update(any(), any())).thenReturn(0);
        merchant("BUSINESS_LICENSE", endOfDay(2026, 7, 10));
        scan(List.of(), List.of(merchantEntity));

        job.sweepOnce(NOW);

        ArgumentCaptor<Wrapper<MerchantEntity>> captor = wrapperCaptor();
        verify(merchantMapper).update(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("status");
    }

    // ---------------- 顺序与空场景 ----------------

    @Test
    @DisplayName("先停业后提醒：同一轮里刚被停业的店不会又收到一封「即将到期」")
    void suspendRunsBeforeRemind() {
        // 两批查询互不相交（停业批次里的店不会被提醒批次返回），
        // 这条断言守的是「顺序」这个设计决定：反过来写会让刚停业的店再收一封矛盾通知
        MerchantEntity expired = merchant("BUSINESS_LICENSE", endOfDay(2026, 6, 14));
        scan(List.of(expired), List.of());

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.suspended()).isEqualTo(1);
        assertThat(report.reminded()).isZero();
        verify(notifier, times(1)).notifySuspended(any(), anyString(), any());
    }

    @Test
    @DisplayName("两批查询都为空（没有临期也没有过期）→ 什么都不做，不报错")
    void emptyScanIsNoop() {
        scan(List.of(), List.of());

        MerchantLicenseJob.SweepReport report = job.sweepOnce(NOW);

        assertThat(report.suspended()).isZero();
        assertThat(report.reminded()).isZero();
        verify(notifier, never()).offShelfAll(anyLong());
    }

    @Test
    @DisplayName("任务暂停：开关关闭时调度入口直接返回，不碰数据库")
    void disabledJobDoesNothing() {
        MerchantLicenseJob off = new MerchantLicenseJob(merchantMapper, materialMapper,
                notifier, auditLogService, idGenerator, leaderLock, false);

        off.sweep();

        verify(merchantMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("多实例：抢不到选主锁时本轮不执行（避免 N 个实例各发一遍通知）")
    void skipsWhenNotLeader() {
        when(leaderLock.tryAcquire(anyString(), anyLong())).thenReturn(false);

        job.sweep();

        verify(merchantMapper, never()).selectList(any());
    }

    // ---------------- 夹具 ----------------

    private MerchantEntity merchantEntity;

    private MerchantEntity merchant(String materialType, LocalDateTime expireAt) {
        merchantEntity = new MerchantEntity();
        merchantEntity.setId(MERCHANT_ID);
        merchantEntity.setOwnerUserId(OWNER_ID);
        merchantEntity.setName("豆豆宠物用品店");
        merchantEntity.setStatus(MerchantStatus.APPROVED.name());
        merchantEntity.setLicenseExpireAt(expireAt);
        MerchantMaterialEntity material = new MerchantMaterialEntity();
        material.setMerchantId(MERCHANT_ID);
        material.setMaterialType(materialType);
        material.setExpireAt(expireAt);
        when(materialMapper.selectOne(any())).thenReturn(material);
        return merchantEntity;
    }

    /** 按「过期批次、临期批次」的顺序给出两批查询结果 */
    private void scan(List<MerchantEntity> expired, List<MerchantEntity> expiring) {
        when(merchantMapper.selectList(any())).thenReturn(expired, expiring);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<MerchantEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    /**
     * 取出条件更新里<b>实际写入</b>的到期档位值。
     *
     * <p>为什么不能直接断言内存对象：停业 / 提醒都改成了条件更新（{@code mapper.update}），
     * 而条件更新只认库里的状态、<b>不回写内存实体</b>。所以「档位写对了没有」必须去
     * update 语句的参数里看 —— 这也正是生产环境里真正发生的事情。
     *
     * <p>{@code getSqlSegment()} 必须先调一次：MyBatis-Plus 的参数表是<b>懒生成</b>的，
     * 不触发就永远是空的（只读参数表会得到「什么都没写」的假结论）。
     */
    private String writtenStage() {
        ArgumentCaptor<Wrapper<MerchantEntity>> captor = wrapperCaptor();
        verify(merchantMapper, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        Wrapper<MerchantEntity> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<MerchantEntity, ?, ?>) wrapper)
                .getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String)
                .map(String::valueOf)
                .filter(v -> LicenseExpiry.Stage.parse(v) != LicenseExpiry.Stage.NONE)
                .findFirst()
                .orElse(null);
    }

    private static LocalDateTime endOfDay(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 23, 59, 59);
    }
}
