package com.duoduo.common.web;

import com.duoduo.common.web.internal.InternalCallAuth;
import com.duoduo.common.web.sched.SchedLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web 基础设施自动装配。
 *
 * <p>业务服务只要依赖 duoduo-common-web，即可自动获得：请求 ID、全局异常处理、登录用户上下文。
 *
 * <p><b>为什么用显式 @Bean 而不是靠 @Component 扫描</b>：
 * 本模块的包名是 {@code com.duoduo.common.web}，而各业务服务的组件扫描根是
 * {@code com.duoduo.<service>}，两者不重叠。因此这里的类**不会被业务服务扫描到**，
 * 必须通过自动装配显式登记为 Bean —— 否则全局异常处理器不会生效（异常会变成 500 而不是 422），
 * 请求 ID 过滤器也不会挂上。
 */
@AutoConfiguration
public class WebAutoConfiguration {

    @Bean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    /**
     * 全局异常处理。注意：必须作为 Bean 注册，
     * {@code @RestControllerAdvice} 才不会被忽略（Spring 是按 Bean 找 advice 的）。
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * 64 位整数转字符串的 Jackson 模块（雪花 ID 精度保护，见 T-39）。
     *
     * <p>注册为 Bean 有两个作用：一是让业务服务的 {@code ObjectMapper} 也带上它
     * （Feign / RestTemplate 等非 MVC 场景），二是把这个"隐藏规则"显式暴露在装配清单里，
     * 读代码的人不必翻 {@code extendMessageConverters} 才知道 id 是字符串。
     */
    @Bean
    public LongToStringJacksonModule longToStringJacksonModule() {
        return new LongToStringJacksonModule();
    }

    /**
     * 定时任务选主锁（第五十八轮 · P0-2）。
     *
     * <p>★ 为什么放进一个**独立的内层配置类**而不是直接在本类上写 {@code @Bean}（R59 实测踩到）：
     * 本模块的 {@code spring-boot-starter-jdbc} 是 {@code optional}，不给 JDBC 的服务（AI）
     * 运行时根本没有 {@code JdbcTemplate} 这个类。条件注解是**写在方法上**时，
     * Spring 为了拿到这个 {@code @Bean} 方法的签名（参数类型 {@code JdbcTemplate}）会先去加载它，
     * 于是在条件生效之前就抛 {@code NoClassDefFoundError} —— 也就是说
     * {@code @ConditionalOnClass} 挂在方法上**救不了方法签名本身**。
     * 放到内层类上，条件由 Spring 用 ASM 读字节码判定，不匹配时这个类压根不被加载。
     *
     * <p>两个条件装配各管一件事：
     * <ul>
     *   <li>{@code @ConditionalOnClass(JdbcTemplate)}：不给 JDBC 的环境里连类都不该被加载（内层类级）。</li>
     *   <li>{@code @ConditionalOnBean(DataSource)}：AI 服务依赖本模块但<b>不连数据库</b>，
     *       有 JDBC 却没有数据源时也不该注册 —— 基础设施不该反过来要求业务服务配库。</li>
     * </ul>
     *
     * <p>注意它必须由自动装配显式登记：本模块包名 {@code com.duoduo.common.web}
     * 与各业务服务的组件扫描根不重叠（见本类注释开头）。
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class SchedLeaderConfiguration {

        @Bean
        @ConditionalOnBean(DataSource.class)
        public SchedLeaderLock schedLeaderLock(
                JdbcTemplate jdbcTemplate,
                @Value("${duoduo.sched.leader-enabled:true}") boolean enabled) {
            return new SchedLeaderLock(jdbcTemplate, SchedLeaderLock.defaultOwner(), enabled);
        }
    }

    /**
     * 服务间调用凭据（第五十八轮 · P0-3）：出向 Feign 签发签名、入向内部接口校验签名。
     *
     * <p>必须是 Bean 而不是各域自己 new：密钥派生规则全项目只有一处实现，
     * 一处改错（比如派生时漏了 caller）会表现为「所有内部调用 403」，
     * 分散在六个域里各写一份等于把这种错误复制六遍。
     *
     * <p>{@code spring.application.name} 就是调用方身份（duoduo-trade / duoduo-product …），
     * 因此不需要额外配置「我是谁」—— 身份与 Spring 应用名天然一致，不会配错。
     *
     * <p>两个可选配置：{@code duoduo.internal.signature-skew-seconds}（时间窗，默认 300 秒）
     * 与 {@code duoduo.internal.caller-secrets}（每个调用方一把显式密钥，默认空 = 走派生密钥）。
     */
    @Bean
    public InternalCallAuth internalCallAuth(
            @Value("${duoduo.internal.token}") String rootSecret,
            @Value("${spring.application.name:unknown}") String selfCaller,
            @Value("${duoduo.internal.signature-skew-seconds:300}") long skewSeconds,
            @Value("${duoduo.internal.caller-secrets:}") String callerSecrets) {
        return new InternalCallAuth(rootSecret, selfCaller, skewSeconds,
                InternalCallAuth.parseCallerSecrets(callerSecrets));
    }

    @Bean
    public WebMvcConfigurer duoduoWebMvcConfigurer(
            ObjectProvider<com.duoduo.common.audit.AuditSink> auditSinkProvider) {
        return new WebMvcConfigurer() {

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new UserContextInterceptor())
                        .addPathPatterns("/**")
                        .excludePathPatterns(
                                "/actuator/**",
                                "/health/**",
                                "/error",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/doc.html",
                                "/h2-console/**"
                        );
                // E20 · 租户上下文。★ 排在 UserContextInterceptor **之后**（身份先立、再谈属于谁），
                // 且排在 AuditInterceptor **之前** —— 审计记录里的租户维度读的就是这里建好的上下文。
                // 它把网关写的 X-Tenant-Id 还原成 TenantContext，供数据层追加行级过滤条件；
                // 头不存在时**不设上下文**（= 不追加条件），这正是 E20 之前的行为，上线零风险。
                registry.addInterceptor(new TenantContextInterceptor())
                        .addPathPatterns("/**")
                        .excludePathPatterns(
                                "/actuator/**",
                                "/health/**",
                                "/error",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/doc.html",
                                "/h2-console/**"
                        );
                // E18 · 后台写操作留痕。★ 注册在 UserContextInterceptor 之后（要靠它还原操作人）、
                // 但在 PermissionInterceptor **之前** —— 顺序反过来的话，越权被拒的请求
                // 根本走不到审计拦截器的 afterCompletion（Spring 只为 preHandle 成功过的
                // 拦截器回调）→「连续失败的提权尝试」这类最该被看见的信号全部丢失。
                registry.addInterceptor(new AuditInterceptor(auditSinkProvider))
                        .addPathPatterns("/**")
                        .excludePathPatterns(
                                "/actuator/**",
                                "/health/**",
                                "/error",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/doc.html",
                                "/h2-console/**"
                        );
                // E16 · 声明式判权。★ 必须注册在 UserContextInterceptor **之后**：
                // 它读 UserContext，而上下文由前一个拦截器从网关头还原。
                // 顺序颠倒的表现是「带注解的端点一律 401」，很容易被误读成鉴权更严。
                registry.addInterceptor(new PermissionInterceptor())
                        .addPathPatterns("/**")
                        .excludePathPatterns(
                                "/actuator/**",
                                "/health/**",
                                "/error",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/doc.html",
                                "/h2-console/**"
                        );
            }

            /**
             * 统一在 Content-Type 上带 charset=UTF-8。
             * 规范上 application/json 默认即 UTF-8，但不少老客户端（Windows PowerShell 5.1 等）
             * 在缺少 charset 时按 ISO-8859-1 解码，导致中文乱码。带上 charset 消除这类兼容问题。
             *
             * <p>同时给每个 Jackson 转换器挂上 {@link LongToStringJacksonModule}：
             * 雪花 ID 是 18~19 位，超出 JS 安全整数范围，直接以 JSON number 下发会被浏览器
             * 静默舍入（见该模块的类注释与 T-39）。这里挂模块而不是逐个字段标注注解 ——
             * 漏标的代价是一个只在真实浏览器里才出现的 404，不能靠"记得标"来保证。
             */
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.stream()
                        .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                        .map(MappingJackson2HttpMessageConverter.class::cast)
                        .forEach(converter -> {
                            converter.setDefaultCharset(StandardCharsets.UTF_8);
                            converter.getObjectMapper().registerModule(new LongToStringJacksonModule());
                        });
            }
        };
    }
}
