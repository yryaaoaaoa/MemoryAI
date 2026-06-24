/**
 * API 接口模块
 *
 * REST + SSE 控制器
 *
 * 核心包结构：
 * controller     - REST / SSE 接口
 * config         - Spring Security 配置 / WebMvc 配置
 * interceptor    - 认证拦截器 (AuthInterceptor)
 * service        - 应用服务层 (AuthService, StreamingChatService)
 * dto            - 请求/响应 DTO
 */
package com.jobai.api;
