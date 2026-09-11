package com.luky.nexusmind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 配置Spring Security的类
 * 该类定义了应用的安全配置，包括请求的授权规则、CSRF保护的配置以及会话管理策略
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 日志记录器，用于记录安全配置的相关信息
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Autowired
    private OrgTagAuthorizationFilter orgTagAuthorizationFilter;

    /**
     * 配置SecurityFilterChain bean的方法
     * 该方法主要用于配置应用的安全规则，包括哪些请求需要授权、CSRF保护的启用或禁用、会话管理策略等
     *
     * @param http HttpSecurity对象，用于配置应用的安全规则
     * @return SecurityFilterChain对象，代表配置好的安全过滤链
     * @throws Exception 如果配置过程中发生错误，会抛出异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        try {
            // 禁用CSRF保护
            http.csrf(csrf -> csrf.disable())
                    // 配置请求的授权规则
                    .authorizeHttpRequests(authorize -> authorize
                            // 允许静态资源访问
                            .requestMatchers("/", "/test.html", "/static/test.html", "/static/**", "/*.js", "/*.css", "/*.ico").permitAll()
                            // 允许 WebSocket 连接
                            .requestMatchers("/chat/**", "/notifications/**", "/ws/**").permitAll()
                            // 允许登录注册接口
                            .requestMatchers(
                                    "/api/v1/users/register",
                                    "/api/v1/users/registration-code",
                                    "/api/v1/users/password-reset-code",
                                    "/api/v1/users/reset-password",
                                    "/api/v1/users/login",
                                    "/api/v1/auth/refreshToken"
                            ).permitAll()
                            // 健康检查供容器探针使用，其余 Actuator 端点一律需要超级管理员。
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                            // 短期下载票据在控制器中验证，不使用登录 JWT 作为 URL 参数。
                            .requestMatchers("/api/v1/documents/download/content").permitAll()
                            // PDF预览接口在控制器内部按登录态校验文件访问权限，不接受 URL token。
                            .requestMatchers("/api/v1/documents/preview/pdf").permitAll()
                            // SSE 事件流使用一次性短期票据，避免把登录 JWT 放入 URL。
                            .requestMatchers("/api/v1/upload/status/events").permitAll()
                            // 文件上传和下载相关接口 - 普通用户和管理员都可访问
                            .requestMatchers("/api/v1/upload/**", "/api/v1/parse", "/api/v1/documents/download", "/api/v1/documents/preview").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            // 对话历史相关接口 - 用户只能查看自己的历史，管理员可以查看所有
                            .requestMatchers("/api/v1/users/conversation/**").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            // 搜索接口 - 普通用户和管理员都可访问
                            .requestMatchers("/api/v1/search/**").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            // 额度与计价只能由超级管理员维护；需置于通用 admin 规则之前。
                            .requestMatchers("/api/v1/admin/model-usage/**").hasRole("SUPER_ADMIN")
                            // 管理员专属接口 - 知识库管理、系统状态、用户活动监控
                            .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                            // 模型配置：普通用户管理自己的模型，管理员额外管理系统模型
                            .requestMatchers("/api/v1/model-config/**").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            .requestMatchers("/api/v1/graph-prompt-templates/**").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            // 用户组织标签管理接口
                            .requestMatchers("/api/v1/users/primary-org").hasAnyRole("USER", "ADMIN", "SUPER_ADMIN")
                            // 其他请求需要认证
                            .anyRequest().authenticated())
                    // 未登录与无权限必须使用不同状态码，避免前端将普通 403 误判为登录失效。
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint((request, response, exception) ->
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                            .accessDeniedHandler((request, response, exception) ->
                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
                    // 配置会话管理策略
                    // 设置会话创建策略为STATELESS，表示不会创建会话，通常用于无状态的API应用
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .headers(headers -> headers
                            .contentTypeOptions(contentType -> {})
                            .frameOptions(frame -> frame.deny())
                            .referrerPolicy(referrer -> referrer.policy(
                                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                            .contentSecurityPolicy(csp -> csp.policyDirectives(
                                    "default-src 'self'; img-src 'self' blob: data:; script-src 'self'; "
                                            + "style-src 'self' 'unsafe-inline'; connect-src 'self' wss:; "
                                            + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'")))
                    // 添加JWT认证过滤器
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    // 添加组织标签授权过滤器
                    .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class);

            // 记录安全配置加载成功的信息
            logger.info("Security configuration loaded successfully.");
            // 返回配置好的安全过滤链
            return http.build();
        } catch (Exception e) {
            // 记录配置安全过滤链失败的错误信息
            logger.error("Failed to configure security filter chain", e);
            // 抛出异常，以便外部处理
            throw e;
        }
    }
}
