package com.duoduo.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    @Bean
    public WebMvcConfigurer duoduoWebMvcConfigurer() {
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
