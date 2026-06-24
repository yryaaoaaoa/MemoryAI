/**
 * Agent 引擎模块
 *
 * 意图识别 → ToolCall 调度 → 结果拼接 → LLM 回复
 * 会话记忆管理（滑动窗口 + 摘要 + MySQL 持久化）
 *
 * 核心包结构：
 * tool           - 工具定义（@Tool）
 * memory         - 会话记忆管理
 * engine         - Agent 主循环调度
 * prompt         - Prompt 模板
 * dto            - 传输对象
 * service        - 业务接口与实现（待实现）
 */
package com.jobai.agent;
