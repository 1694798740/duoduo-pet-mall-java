package com.duoduo.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 管家库表启动自检（2026-10-29 · B1）。
 *
 * <h2>为什么是「自检」而不是「启动时执行 DDL」</h2>
 * <ul>
 *   <li><b>权限</b>：业务账号不应该有 DDL 权限。让应用启动时 ALTER TABLE，
 *       等于把「改表」变成一次重启就能发生的事，事故半径远大于收益；</li>
 *   <li><b>并发</b>：多实例同时启动会同时执行迁移；幂等 SQL 虽然能扛，
 *       但把「谁先跑完」变成竞态条件不是应用该做的事；</li>
 *   <li><b>可回滚</b>：迁移失败要能停在发布流水线里，而不是让服务半死不活地起来。</li>
 * </ul>
 *
 * <p>因此职责拆成两半：
 * <ol>
 *   <li>{@code scripts/apply-73-ai-butler-ddl.ps1} 负责**执行**（可连跑两遍验证幂等）；</li>
 *   <li>本类负责**验证**：启动时确认关键列/表都在，缺任何一项直接启动失败，
 *       而不是让审计写入在运行期静默丢数据（那是比启动失败更糟的故障）。</li>
 * </ol>
 *
 * <p>可用 {@code duoduo.ai.schema-guard.enabled=false} 临时关闭（仅限本地排障；
 * 生产关闭等于主动放弃这道验）。
 */
@Component
public class AiSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSchemaGuard.class);

    /** 迁移脚本路径：失败信息里直接给可执行命令，避免「知道缺列但不知道跑什么」。 */
    private static final String FIX_HINT =
            "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\apply-73-ai-butler-ddl.ps1";

    /** 必需的表（不存在则说明 72/73 没跑）。 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "t_ai_chat_message",
            "t_ai_tool_call_log",
            "t_ai_user_memory",
            "t_ai_memory_event"
    );

    /** 必需的列：表名 + 列名（不存在则说明 73/74 没跑）。 */
    private static final List<String[]> REQUIRED_COLUMNS = List.of(
            new String[]{"t_ai_user_memory", "memory_json"},
            new String[]{"t_ai_chat_message", "page_url"},
            new String[]{"t_ai_chat_message", "card_refs"},
            new String[]{"t_ai_tool_call_log", "page_url"},
            new String[]{"t_ai_tool_call_log", "badge_patch"}
    );

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public AiSchemaGuard(JdbcTemplate jdbcTemplate,
                         @Value("${duoduo.ai.schema-guard.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("AI 库表自检已关闭（duoduo.ai.schema-guard.enabled=false），审计写入可能失败");
            return;
        }
        try {
            verify();
            log.info("AI 库表自检通过：审计与记忆表结构齐全");
        } catch (IllegalStateException e) {
            // 让启动失败：缺列时继续跑起来，只会把「审计写不进去」推迟成线上问题
            log.error("AI 库表自检失败，服务拒绝启动：{}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // 数据库暂时连不上不等于结构缺失：放行启动，由健康检查暴露真实原因
            log.warn("AI 库表自检无法执行（数据库不可用），跳过本次校验: {}", e.getMessage());
        }
    }

    private void verify() {
        for (String table : REQUIRED_TABLES) {
            long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Long.class, table);
            if (count == 0) {
                throw fail("缺表 " + table);
            }
        }
        for (String[] column : REQUIRED_COLUMNS) {
            long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Long.class, column[0], column[1]);
            if (count == 0) {
                throw fail("缺列 " + column[0] + "." + column[1]);
            }
        }
    }

    private static IllegalStateException fail(String detail) {
        return new IllegalStateException(detail + "；请先执行增量 DDL：" + FIX_HINT);
    }
}
