# MemoryAI — 求职备考平台

Monorepo: Java Spring Boot 后端 + Vue 3 前端。

## 项目结构

```
MemoryAI/
├── ai-rag-agent/              # 后端 (Java 17, Spring Boot 3.2.12)
│   ├── common/                # 统一响应体 R, ErrorCode, BusinessException
│   ├── infrastructure/        # ES 8.x, Redis, MyBatis-Plus 配置, User entity
│   ├── knowledge/             # 文档摄入管线, RAG检索, LLM服务, schema.sql
│   ├── rag/                   # 聊天会话管理, ES管理
│   ├── agent/                 # 面试方向(Skill)系统, 出题/错题/掌握度
│   ├── api/                   # REST 控制器, SSE流式聊天, 认证
│   └── bootstrap/             # 启动入口, application.yml, skills/ 资源
├── ai-job-prep-frontend/      # 前端 (Vue 3 + TypeScript + Vite + Pinia)
│   └── src/
│       ├── api/               # Axios API 封装
│       ├── stores/            # Pinia 状态管理
│       ├── views/             # 页面组件
│       ├── types/             # TypeScript 类型定义
│       └── router/            # 路由配置
```

## 后端 — Maven 模块依赖顺序

```
common ← infrastructure ← knowledge ← rag ← agent ← api ← bootstrap
```

编译打包：`cd ai-rag-agent && mvn clean package -DskipTests`

## 后端启动

```bash
cd ai-rag-agent
mvn spring-boot:run -pl bootstrap
# 或在 bootstrap/ 目录: mvn spring-boot:run
# 需要本地 MySQL (jobai), Redis, ES 8.x
```

## 前端

- 包管理: pnpm (禁止 npm/yarn)
- 启动: `cd ai-job-prep-frontend && pnpm run dev`
- 构建: `pnpm run build`

## 关键配置

后端配置: `bootstrap/src/main/resources/application.yml`
- MySQL: localhost:3306/jobai (root/123456)
- Redis: localhost:6379
- ES 8.x: localhost:9200 (https, elastic/zisqvAJfc6duu5suChU5)
- DeepSeek API / SiliconFlow Embedding

## 数据库

DDL: `ai-rag-agent/knowledge/src/main/resources/schema.sql`

12 张表: knowledge_base, document, chunk, chat_session, chat_message, quiz_question, quiz_record, user_mastery, resume, user, interview_session, interview_answer

## 面试方向 (Skill) 系统

预设 10 个面试方向，定义在 `bootstrap/src/main/resources/skills/*/`：
- 每个方向有 SKILL.md (front matter + persona) + skill.meta.yml (分类权重、参考文件映射)
- 共享参考文件: `skills/_shared/references/`
- 核心服务: `InterviewSkillService` — 加载预设、JD解析、考点分配、参考内容注入
- API: `GET /api/interview/skills`, `POST /api/interview/skills/parse-jd`

## 常见操作

补数据库字段：确认实体类字段 → 检查实际表结构 → ALTER TABLE 添加
新增 API：entity → mapper → service → controller 顺序，注意模块依赖方向
前端对接：`src/api/` 加方法 → `src/stores/` 加状态 → 页面组件调用
