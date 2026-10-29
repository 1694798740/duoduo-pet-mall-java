package com.duoduo.user.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.CoinDtos.CoinMutationResult;
import com.duoduo.user.dto.CoinDtos.CoinSigninResult;
import com.duoduo.user.entity.CoinAccountEntity;
import com.duoduo.user.entity.CoinFreezeEntity;
import com.duoduo.user.entity.CoinFreezeLotEntity;
import com.duoduo.user.entity.CoinLotEntity;
import com.duoduo.user.entity.CoinSigninEntity;
import com.duoduo.user.mapper.CoinAccountMapper;
import com.duoduo.user.mapper.CoinFreezeLotMapper;
import com.duoduo.user.mapper.CoinFreezeMapper;
import com.duoduo.user.mapper.CoinLedgerMapper;
import com.duoduo.user.mapper.CoinLotMapper;
import com.duoduo.user.mapper.CoinSigninMapper;
import com.duoduo.user.mapper.CoinTaskDefMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多多币核心服务单测（R-P1 · 第一百二十六轮）。
 *
 * <p>币是钱，这里的每条断言都在钉一类「账不会乱」的边界：
 * 冻结的幂等与余额硬校验、状态机的 CAS 语义（确认 / 释放互斥）、
 * 退款退币的幂等占位与「过期批次绝不复活」、返币的一次性。
 * 与冒烟互为补充：冒烟验「服务间串起来对不对」，这里验「单个服务里账算得对不对」。
 */
class CoinServiceTest {

    private CoinAccountMapper accountMapper;
    private CoinLotMapper lotMapper;
    private CoinLedgerMapper ledgerMapper;
    private CoinFreezeMapper freezeMapper;
    private CoinFreezeLotMapper freezeLotMapper;
    private CoinSigninMapper signinMapper;
    private CoinTaskDefMapper taskDefMapper;
    private CoinCampaignService campaignService;
    private CoinService service;

    private static final Long USER = 3001L;
    private static final String ORDER = "O20261026000000000001";

    @BeforeEach
    void setUp() {
        accountMapper = mock(CoinAccountMapper.class);
        lotMapper = mock(CoinLotMapper.class);
        ledgerMapper = mock(CoinLedgerMapper.class);
        freezeMapper = mock(CoinFreezeMapper.class);
        freezeLotMapper = mock(CoinFreezeLotMapper.class);
        signinMapper = mock(CoinSigninMapper.class);
        taskDefMapper = mock(CoinTaskDefMapper.class);
        campaignService = mock(CoinCampaignService.class);
        // 无活动时加成方法必须原样返回基础值：mock 默认返回 0 会把签到 / 返币静默清零
        when(campaignService.signinBonus(anyLong(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(campaignService.earnBonus(anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(9001L);
        service = new CoinService(accountMapper, lotMapper, ledgerMapper,
                freezeMapper, freezeLotMapper, signinMapper, taskDefMapper, campaignService, idGenerator);
    }

    private static CoinAccountEntity account(long balance) {
        CoinAccountEntity entity = new CoinAccountEntity();
        entity.setId(7001L);
        entity.setUserId(USER);
        entity.setBalance(balance);
        entity.setTotalEarned(balance);
        entity.setTotalSpent(0L);
        return entity;
    }

    private static CoinFreezeEntity freeze(String status, long coins) {
        CoinFreezeEntity entity = new CoinFreezeEntity();
        entity.setId(8001L);
        entity.setUserId(USER);
        entity.setOrderNo(ORDER);
        entity.setCoins(coins);
        entity.setStatus(status);
        return entity;
    }

    private static CoinLotEntity lot(long id, long remaining, long expireOffsetHours) {
        CoinLotEntity entity = new CoinLotEntity();
        entity.setId(id);
        entity.setUserId(USER);
        entity.setBizType("ADMIN_ADJUST");
        entity.setAmount(remaining);
        entity.setRemaining(remaining);
        entity.setExpireAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(expireOffsetHours));
        return entity;
    }

    private void stubAccount(long balance) {
        when(accountMapper.selectOne(any())).thenReturn(account(balance));
    }

    // ==================== 冻结 ====================

    @Test
    void 冻结成功_扣余额扣批次记流水() {
        stubAccount(10000L);
        when(freezeMapper.selectOne(any())).thenReturn(null);
        when(freezeMapper.insertIgnore(any())).thenReturn(1);
        when(accountMapper.deductBalance(eq(USER), eq(500L), any())).thenReturn(1);
        when(lotMapper.selectList(any())).thenReturn(List.of(lot(9101L, 300L, 100), lot(9102L, 300L, 200)));
        when(lotMapper.deductRemaining(eq(9101L), eq(300L), any())).thenReturn(1);
        when(lotMapper.deductRemaining(eq(9102L), eq(200L), any())).thenReturn(1);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);

        CoinMutationResult result = service.freeze(USER, ORDER, 500L);

        assertThat(result.applied()).isTrue();
        verify(accountMapper).deductBalance(eq(USER), eq(500L), any());
        verify(lotMapper).deductRemaining(eq(9101L), eq(300L), any());
        verify(lotMapper).deductRemaining(eq(9102L), eq(200L), any());
    }

    @Test
    void 冻结幂等重入_同单同数量直接返回已处理() {
        stubAccount(9500L);
        when(freezeMapper.selectOne(any())).thenReturn(freeze(CoinFreezeEntity.STATUS_FROZEN, 500L));

        CoinMutationResult result = service.freeze(USER, ORDER, 500L);

        assertThat(result.applied()).isFalse();
        verify(accountMapper, never()).deductBalance(anyLong(), anyLong(), any());
        verify(lotMapper, never()).deductRemaining(anyLong(), anyLong(), any());
    }

    @Test
    void 冻结同单不同数量_409拒绝() {
        stubAccount(9500L);
        when(freezeMapper.selectOne(any())).thenReturn(freeze(CoinFreezeEntity.STATUS_FROZEN, 500L));

        assertThatThrownBy(() -> service.freeze(USER, ORDER, 800L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("数量不一致");
    }

    @Test
    void 冻结余额不足_拒绝且不留冻结单() {
        stubAccount(100L);
        when(freezeMapper.selectOne(any())).thenReturn(null);
        when(freezeMapper.insertIgnore(any())).thenReturn(1);
        when(accountMapper.deductBalance(eq(USER), eq(500L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.freeze(USER, ORDER, 500L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("余额不足");
        verify(lotMapper, never()).deductRemaining(anyLong(), anyLong(), any());
        verify(ledgerMapper, never()).insertIgnore(any());
    }

    @Test
    void 冻结非正数_校验拒绝() {
        assertThatThrownBy(() -> service.freeze(USER, ORDER, 0L))
                .isInstanceOf(BizException.class);
    }

    // ==================== 扣减确认 / 释放 ====================

    @Test
    void 扣减确认_CAS成功推进状态() {
        when(freezeMapper.selectOne(any())).thenReturn(freeze(CoinFreezeEntity.STATUS_FROZEN, 500L));
        stubAccount(9500L);
        when(freezeMapper.markDeducted(eq(ORDER), any())).thenReturn(1);

        CoinMutationResult result = service.deductConfirm(ORDER);

        assertThat(result.applied()).isTrue();
    }

    @Test
    void 扣减确认_重复回调幂等返回已处理() {
        when(freezeMapper.selectOne(any()))
                .thenReturn(freeze(CoinFreezeEntity.STATUS_DEDUCTED, 500L));
        stubAccount(9500L);
        when(freezeMapper.markDeducted(eq(ORDER), any())).thenReturn(0);

        CoinMutationResult result = service.deductConfirm(ORDER);

        assertThat(result.applied()).isFalse();
    }

    @Test
    void 扣减确认_冻结已被释放_拒绝支付() {
        when(freezeMapper.selectOne(any()))
                .thenReturn(freeze(CoinFreezeEntity.STATUS_RELEASED, 500L));
        when(freezeMapper.markDeducted(eq(ORDER), any())).thenReturn(0);

        assertThatThrownBy(() -> service.deductConfirm(ORDER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许确认扣减");
    }

    @Test
    void 释放_CAS成功退回余额与批次() {
        when(freezeMapper.selectOne(any())).thenReturn(freeze(CoinFreezeEntity.STATUS_FROZEN, 500L));
        stubAccount(9500L);
        when(freezeMapper.markReleased(eq(ORDER), any())).thenReturn(1);
        CoinFreezeLotEntity item = new CoinFreezeLotEntity();
        item.setId(8101L);
        item.setFreezeId(8001L);
        item.setLotId(9101L);
        item.setCoins(500L);
        when(freezeLotMapper.selectList(any())).thenReturn(List.of(item));
        when(lotMapper.addRemainingIfValid(eq(9101L), eq(500L), any())).thenReturn(1);
        when(accountMapper.addBalanceAndRefundSpent(eq(USER), eq(500L), any())).thenReturn(1);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);

        CoinMutationResult result = service.release(ORDER);

        assertThat(result.applied()).isTrue();
        verify(accountMapper).addBalanceAndRefundSpent(eq(USER), eq(500L), any());
        // 批次必须成对退回：只还余额不还批次会让「余额 = Σ批次」作废
        verify(lotMapper).addRemainingIfValid(eq(9101L), eq(500L), any());
    }

    @Test
    void 释放_重复释放幂等返回已处理() {
        when(freezeMapper.selectOne(any()))
                .thenReturn(freeze(CoinFreezeEntity.STATUS_RELEASED, 500L));
        stubAccount(9500L);
        when(freezeMapper.markReleased(eq(ORDER), any())).thenReturn(0);

        CoinMutationResult result = service.release(ORDER);

        assertThat(result.applied()).isFalse();
        verify(accountMapper, never()).addBalanceAndRefundSpent(anyLong(), anyLong(), any());
    }

    @Test
    void 释放_冻结单不存在_404() {
        when(freezeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.release(ORDER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== 退款退币 ====================

    @Test
    void 退款退币_幂等占位生效时不再重复退() {
        stubAccount(9000L);
        // reserveLedger 撞唯一键（insertIgnore 返回 0）→ 已退过
        when(ledgerMapper.insertIgnore(any())).thenReturn(0);

        CoinMutationResult result = service.refundBack(USER, ORDER, 500L);

        assertThat(result.applied()).isFalse();
        verify(accountMapper, never()).addBalanceAndEarned(anyLong(), anyLong(), any());
        verify(lotMapper, never()).addRemainingIfValid(anyLong(), anyLong(), any());
    }

    @Test
    void 退款退币_原批次未过期直接加回() {
        stubAccount(9000L);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);
        CoinFreezeEntity freezeRow = freeze(CoinFreezeEntity.STATUS_DEDUCTED, 500L);
        when(freezeMapper.selectOne(any())).thenReturn(freezeRow);
        CoinFreezeLotEntity item = new CoinFreezeLotEntity();
        item.setId(8101L);
        item.setFreezeId(8001L);
        item.setLotId(9101L);
        item.setCoins(500L);
        when(freezeLotMapper.selectList(any())).thenReturn(List.of(item));
        when(lotMapper.addRemainingIfValid(eq(9101L), eq(500L), any())).thenReturn(1);

        CoinMutationResult result = service.refundBack(USER, ORDER, 500L);

        assertThat(result.applied()).isTrue();
        verify(lotMapper).addRemainingIfValid(eq(9101L), eq(500L), any());
        verify(accountMapper).addBalanceAndEarned(eq(USER), eq(500L), any());
    }

    @Test
    void 退款退币_原批次已过期补发30天新批次绝不复活() {
        stubAccount(9000L);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);
        when(freezeMapper.selectOne(any())).thenReturn(freeze(CoinFreezeEntity.STATUS_DEDUCTED, 500L));
        CoinFreezeLotEntity item = new CoinFreezeLotEntity();
        item.setId(8101L);
        item.setFreezeId(8001L);
        item.setLotId(9101L);
        item.setCoins(500L);
        when(freezeLotMapper.selectList(any())).thenReturn(List.of(item));
        // 原批次已过期：条件更新失败（expire_at <= now）
        when(lotMapper.addRemainingIfValid(eq(9101L), eq(500L), any())).thenReturn(0);

        CoinMutationResult result = service.refundBack(USER, ORDER, 500L);

        assertThat(result.applied()).isTrue();
        // 条件更新尝试过但返回 0（批次过期）→ 不再重试旧批次，改走补发
        verify(lotMapper).addRemainingIfValid(eq(9101L), eq(500L), any());
        verify(lotMapper).insert(any(CoinLotEntity.class));
        org.mockito.ArgumentCaptor<CoinLotEntity> captor =
                org.mockito.ArgumentCaptor.forClass(CoinLotEntity.class);
        verify(lotMapper).insert(captor.capture());
        assertThat(captor.getValue().getRemaining()).isEqualTo(500L);
        assertThat(captor.getValue().getBizType()).isEqualTo("EXPIRE_COMPENSATE");
    }

    // ==================== 返币 / 调整 ====================

    @Test
    void 返币_首次发放建批次加余额() {
        stubAccount(0L);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);

        CoinMutationResult result = service.earn(USER, ORDER, 70L);

        assertThat(result.applied()).isTrue();
        verify(lotMapper).insert(any(CoinLotEntity.class));
        verify(accountMapper).addBalanceAndEarned(eq(USER), eq(70L), any());
    }

    @Test
    void 返币_重复调度幂等不重复发放() {
        stubAccount(70L);
        when(ledgerMapper.insertIgnore(any())).thenReturn(0);

        CoinMutationResult result = service.earn(USER, ORDER, 70L);

        assertThat(result.applied()).isFalse();
        verify(lotMapper, never()).insert(any(CoinLotEntity.class));
    }

    @Test
    void 人工调整_零数量或空原因拒绝() {
        assertThatThrownBy(() -> service.adjust(USER, 0L, "测试"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为 0");
        assertThatThrownBy(() -> service.adjust(USER, 100L, " "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
    }

    @Test
    void 人工调整_加发建批次并计入累计获得() {
        stubAccount(100L);
        when(accountMapper.addBalanceAndEarned(eq(USER), eq(200L), any())).thenReturn(1);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);

        CoinMutationResult result = service.adjust(USER, 200L, "客诉补偿");

        assertThat(result.applied()).isTrue();
        verify(accountMapper).addBalanceAndEarned(eq(USER), eq(200L), any());
        verify(lotMapper).insert(any(CoinLotEntity.class));
    }

    @Test
    void 人工调整_回收超过批次可扣量拒绝() {
        stubAccount(100L);
        when(lotMapper.selectList(any())).thenReturn(List.of(lot(9101L, 30L, 100)));

        assertThatThrownBy(() -> service.adjust(USER, -50L, "超量回收"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可扣量");
        verify(accountMapper, never()).adjustBalance(anyLong(), anyLong(), any());
    }

    // ==================== 签到（R-P2） ====================

    /** 昨日签到行工厂：signDate = 今天-1，continuousDays 由用例给定 */
    private static CoinSigninEntity signinYesterday(int continuousDays) {
        CoinSigninEntity entity = new CoinSigninEntity();
        entity.setId(9501L);
        entity.setUserId(USER);
        entity.setSignDate(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
        entity.setCoins(5);
        entity.setContinuousDays(continuousDays);
        return entity;
    }

    private void stubSigninOpen() {
        when(signinMapper.selectCount(any())).thenReturn(0L);
        when(signinMapper.insertIgnore(any())).thenReturn(1);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);
        stubAccount(50L);
    }

    @Test
    void 签到_首次成功发5币连签1() {
        stubSigninOpen();
        when(signinMapper.selectOne(any())).thenReturn(null);

        CoinSigninResult result = service.signin(USER);

        assertThat(result.applied()).isTrue();
        assertThat(result.coins()).isEqualTo(5);
        assertThat(result.continuousDays()).isEqualTo(1);
        verify(lotMapper).insert(any(CoinLotEntity.class));
        verify(accountMapper).addBalanceAndEarned(eq(USER), eq(5L), any());
    }

    @Test
    void 签到_同日重复拒绝() {
        when(signinMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.signin(USER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经签到");
        verify(signinMapper, never()).insertIgnore(any(CoinSigninEntity.class));
        verify(accountMapper, never()).addBalanceAndEarned(anyLong(), anyLong(), any());
    }

    @Test
    void 签到_昨日已签连签递增但未达加发线只发基础5币() {
        stubSigninOpen();
        when(signinMapper.selectOne(any())).thenReturn(signinYesterday(1));

        CoinSigninResult result = service.signin(USER);

        assertThat(result.continuousDays()).isEqualTo(2);
        assertThat(result.coins()).isEqualTo(5);
    }

    @Test
    void 签到_连签达3天当天加发10币() {
        stubSigninOpen();
        when(signinMapper.selectOne(any())).thenReturn(signinYesterday(2));

        CoinSigninResult result = service.signin(USER);

        assertThat(result.continuousDays()).isEqualTo(3);
        assertThat(result.coins()).isEqualTo(15);
        verify(accountMapper).addBalanceAndEarned(eq(USER), eq(15L), any());
    }

    @Test
    void 签到_连签达7天当天加发30币() {
        stubSigninOpen();
        when(signinMapper.selectOne(any())).thenReturn(signinYesterday(6));

        CoinSigninResult result = service.signin(USER);

        assertThat(result.continuousDays()).isEqualTo(7);
        assertThat(result.coins()).isEqualTo(35);
    }

    @Test
    void 签到_断签重置连签从1开始() {
        stubSigninOpen();
        // 昨天没签（selectOne 为 null）→ 无论前天连多少，今天从 1 重开，只发基础 5 币
        when(signinMapper.selectOne(any())).thenReturn(null);

        CoinSigninResult result = service.signin(USER);

        assertThat(result.continuousDays()).isEqualTo(1);
        assertThat(result.coins()).isEqualTo(5);
    }

    /**
     * 分页契约：流水端点必须返回 PageResult（items/total/page/pageCount）。
     * 回归守卫 —— 曾经返回裸 List 导致前端按 items 读、整页白屏（agent/08 T-71）。
     */
    @Test
    void 我的流水_返回分页元数据而非裸数组() {
        when(ledgerMapper.selectCount(any())).thenReturn(45L);
        when(ledgerMapper.selectList(any())).thenReturn(List.of());

        com.duoduo.common.api.PageResult<com.duoduo.user.dto.CoinDtos.CoinLedgerView> page =
                service.ledgerOf(USER, 2, 10);

        assertThat(page.total()).isEqualTo(45L);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.pageCount()).isEqualTo(5);
        assertThat(page.items()).isEmpty();
    }

    @Test
    void 人工调整_成功记流水() {
        stubAccount(100L);
        when(lotMapper.selectList(any())).thenReturn(List.of(lot(9101L, 30L, 100)));
        when(lotMapper.deductRemaining(eq(9101L), eq(30L), any())).thenReturn(1);
        when(accountMapper.adjustBalance(eq(USER), eq(-30L), any())).thenReturn(1);
        when(ledgerMapper.insertIgnore(any())).thenReturn(1);

        CoinMutationResult result = service.adjust(USER, -30L, "客诉补偿回收");

        assertThat(result.applied()).isTrue();
        verify(accountMapper).adjustBalance(eq(USER), eq(-30L), any());
    }
}
