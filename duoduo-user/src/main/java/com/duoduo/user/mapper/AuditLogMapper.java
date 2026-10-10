package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志访问层（A7 / E18）。
 *
 * <p><b>★ 只应使用 {@code insert} 与 {@code select*} 系列</b>：审计日志的价值在于
 * 不可篡改的顺序历史。{@link BaseMapper} 顺带提供了 {@code update*} / {@code delete*}
 * 方法，这是框架给的、不是设计要的 —— 它们的存在意味着「有人可以一行代码改掉历史，
 * 而且没有编译错误、没有运行时报错」。
 *
 * <p>约束靠两件事保证，都不依赖「记得别用」：
 * <ol>
 *   <li><b>没有任何 HTTP 端点暴露删改能力</b>（{@code AdminAuditLogController} 只有 GET）；</li>
 *   <li>{@code scripts/audit-log-smoke.ps1} 里有一条<b>静态断言</b>：全文扫描仓库，
 *       断言不存在对审计 mapper 的 {@code update}/{@code delete} 调用、
 *       也不存在 {@code t_audit_log} 的 {@code UPDATE}/{@code DELETE} SQL。
 *       这类「架构约束」用全文扫描比用单元测试更可靠 —— 单元测试只能覆盖你想到的调用点。</li>
 * </ol>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
