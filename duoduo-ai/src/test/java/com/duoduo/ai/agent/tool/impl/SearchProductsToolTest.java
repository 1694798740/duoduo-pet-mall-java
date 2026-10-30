package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.ToolConstraintContext;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.memory.SlotState;
import com.duoduo.ai.rag.ProductRagRetriever;
import com.duoduo.ai.rag.QueryPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-013：模型不能通过“不带预算再查一次”绕过服务端槽位。
 */
class SearchProductsToolTest {

    @AfterEach
    void tearDown() {
        ToolConstraintContext.clear();
    }

    @Test
    @DisplayName("槽位有预算时，即使模型不传 budgetMax，也强制按槽位过滤")
    void slotBudgetOverridesMissingModelArgs() {
        ProductRagRetriever retriever = mock(ProductRagRetriever.class);
        when(retriever.retrieve(any(QueryPlan.class))).thenReturn(List.of());
        SearchProductsTool tool = new SearchProductsTool(retriever, new ObjectMapper());

        SlotState slots = new SlotState(null, null, null, null, 100L, null,
                List.of(), List.of(), "NORMAL", null, null);
        ToolConstraintContext.set(slots);

        ObjectNode args = new ObjectMapper().createObjectNode().put("query", "猫粮");
        tool.execute(new ToolContext(null, null, null, "s-def013", "r-def013", false), args);

        ArgumentCaptor<QueryPlan> captor = ArgumentCaptor.forClass(QueryPlan.class);
        verify(retriever).retrieve(captor.capture());
        assertThat(captor.getValue().budgetMaxCents()).isEqualTo(100L);
    }

    @Test
    @DisplayName("模型传了更宽的预算，也必须被槽位覆盖")
    void slotBudgetOverridesWiderModelArgs() {
        ProductRagRetriever retriever = mock(ProductRagRetriever.class);
        when(retriever.retrieve(any(QueryPlan.class))).thenReturn(List.of());
        SearchProductsTool tool = new SearchProductsTool(retriever, new ObjectMapper());

        SlotState slots = new SlotState(null, null, null, null, 100L, null,
                List.of(), List.of(), "NORMAL", null, null);
        ToolConstraintContext.set(slots);

        ObjectNode args = new ObjectMapper().createObjectNode()
                .put("query", "猫粮").put("budgetMax", 99999);
        tool.execute(new ToolContext(null, null, null, "s-def013", "r-def013", false), args);

        ArgumentCaptor<QueryPlan> captor = ArgumentCaptor.forClass(QueryPlan.class);
        verify(retriever).retrieve(captor.capture());
        assertThat(captor.getValue().budgetMaxCents()).isEqualTo(100L);
    }
}
