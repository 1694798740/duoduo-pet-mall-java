package com.duoduo.ai.guardrail;

import org.springframework.stereotype.Component;

/**
 * 引用护栏（2026-10-28）。
 *
 * <p>知识库回答必须带可追溯来源；当前版本先清理模型自造的引用标记，
 * 后续接入 ES 知识检索后，可扩展为「无来源断言删除/重写」。
 */
@Component
public class CitationGuard {

    public String clean(String reply) {
        if (reply == null) {
            return "";
        }
        return reply.replaceAll("\\s*\\[K:[^]]+]", "")
                .replaceAll("\\s*\\[P:[^]]+]", "")
                .trim();
    }
}
