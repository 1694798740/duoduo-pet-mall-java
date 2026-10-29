package com.duoduo.ai.memory;

import com.duoduo.ai.dto.AiMemoryDtos.HighRiskConfirmRequest;
import com.duoduo.ai.dto.AiMemoryDtos.HighRiskUpdate;
import com.duoduo.ai.dto.AiMemoryDtos.LowRiskUpdate;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆管理（第二阶段）：低风险直写、高风险必须走确认令牌、删除幂等。
 *
 * <p>这里不启动 Spring 上下文：记忆服务是纯编排 + 两个 Mapper，
 * 用 Mockito 就能把「校验、令牌、审计」三件事钉死。
 */
class AiMemoryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiUserMemoryMapper userMapper = mock(AiUserMemoryMapper.class);
    private final AiMemoryEventMapper eventMapper = mock(AiMemoryEventMapper.class);
    private final ConfirmService confirmService = mock(ConfirmService.class);
    /** 直接在当前线程执行，审计断言才能同步可见 */
    private final Executor directExecutor = Runnable::run;

    private final AiMemoryService service = new AiMemoryService(
            userMapper, eventMapper, confirmService, mapper, directExecutor);

    @Test
    @DisplayName("非法物种：直接拒绝，不落库")
    void rejectsInvalidSpecies() {
        assertThatThrownBy(() -> service.updateLowRisk(42L,
                new LowRiskUpdate("dinosaur", null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("物种不合法");

        verify(userMapper, never()).insert(any());
        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("低风险更新：写入列并产生变更流水")
    void writesLowRiskAndAudits() {
        when(userMapper.selectOne(any())).thenReturn(null);

        var view = service.updateLowRisk(42L,
                new LowRiskUpdate("CAT", 10000L, 30000L, List.of("主粮"), List.of("皇家"), "小明"));

        assertThat(view.species()).isEqualTo("cat");
        assertThat(view.nickname()).isEqualTo("小明");
        assertThat(view.budgetMinCents()).isEqualTo(10000L);
        assertThat(view.categories()).containsExactly("主粮");
        verify(userMapper).insert(any());
        // 物种 / 预算 / 类目 / 品牌四个键各一条流水
        verify(eventMapper, org.mockito.Mockito.atLeastOnce()).insert(any());
    }

    @Test
    @DisplayName("高风险申请：内容被规范化，令牌绑定规范化后的值")
    void requestHighRiskNormalizes() {
        when(confirmService.issue(any(), anyString(), any()))
                .thenReturn("token-1");

        var ticket = service.requestHighRisk(42L,
                new HighRiskUpdate(List.of(" 鸡肉 ", "鸡肉", "牛肉"), " 肠胃敏感 "));

        assertThat(ticket.token()).isEqualTo("token-1");
        assertThat(ticket.preview().allergens()).containsExactly("鸡肉", "牛肉");
        assertThat(ticket.preview().healthNotes()).isEqualTo("肠胃敏感");
    }

    @Test
    @DisplayName("确认令牌无效/过期：拒绝写入")
    void confirmRejectsInvalidToken() {
        when(confirmService.consume(any(), anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.confirmHighRisk(42L,
                new HighRiskConfirmRequest("expired", List.of("鸡肉"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("确认已过期");

        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("读取：高风险快照里的过敏原正确回显")
    void viewParsesHighRiskSnapshot() {
        AiUserMemoryEntity row = new AiUserMemoryEntity();
        row.setId(1L);
        row.setUserId(42L);
        row.setPreferredSpecies("dog");
        row.setMemoryJson("{\"allergens\":[\"鸡肉\"],\"healthNotes\":\"肠胃敏感\"}");
        when(userMapper.selectOne(any())).thenReturn(row);

        var view = service.view(42L);

        assertThat(view.species()).isEqualTo("dog");
        assertThat(view.allergens()).containsExactly("鸡肉");
        assertThat(view.healthNotes()).isEqualTo("肠胃敏感");
    }

    @Test
    @DisplayName("删除：没有记录时幂等返回，不抛 404")
    void deleteIsIdempotent() {
        when(userMapper.selectOne(any())).thenReturn(null);

        service.delete(42L);

        verify(userMapper, never()).deleteById(any(Long.class));
    }
}
