# 新 API Key 的真实业务验证

2026-10-22 更新本地 API Key 后，后端已重启并验证 AI 对话、出题和面试。此前的 DeepSeek 402 余额不足错误在本次验证中未再出现。

## 启动方式

本次后端 PID 为 40376，使用 Java 17，并明确加载源码目录的外部配置，避免继续使用 JAR 内打包的旧 Key：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -jar C:\workspace\MemoryAI\ai-rag-agent\bootstrap\target\bootstrap-1.0.0.jar --spring.config.additional-location=file:C:/workspace/MemoryAI/ai-rag-agent/bootstrap/src/main/resources/application.yml
```

当前服务已在后台运行，上面的命令仅供以后启动时参考，不要在已有后端占用 8080 时重复执行。配置文件和 Key 未复制到报告或提交到 Git。

- 前端：<http://127.0.0.1:5173/>
- 后端：<http://127.0.0.1:8080/>
- 模型沿用当前配置的 `deepseek-v4-flash`。

## 验证结果

本次使用真实 HTTP 接口、真实模型及数据库，没有 mock。使用独立临时账户和合成知识资料，未使用原有用户的私人数据。

| 功能 | 实际验证 | 结果 |
|---|---|---|
| AI 对话 | 上传合成 Markdown、实际 embedding 入库；检索后通过 SSE 回答资料内校验码 `VERIFY-AI-20261022`；读取历史确认回答持久化 | 通过，约 4.86 秒 |
| 出题 | 基于新知识库生成 3 道选择题，均有真实数据库 ID、4 个选项、有效答案和解析；非模板兜底题 | 通过，生成约 4.59 秒 |
| 判题和记录 | 提交 2 个正确答案和 1 个错误答案；核对判分、3 条答题记录及 1 条错题 | 通过 |
| 批量面试 | 生成 3 道主问题及 6 道追问，共 9 问；全部答题并完成 | 通过，生成约 4.39 秒 |
| 面试评分报告 | 答完至报告可读取约 33.65 秒；核对完成状态、9 条逐题评分、分类成绩、参考答案及数据库持久化；生成 1 条技能画像 | 通过 |
| 完成后保护 | 对已完成面试再次暂存答案 | 按预期拒绝，HTTP 400 |
| 动态面试 | 创建约 1.81 秒，首题答完推进约 2.75 秒；数据库保存 2 道题、当前索引 1 和题目配额 | 通过 |
| 缓存恢复 | 只删除测试动态面试对应的单个 Redis key，再通过接口读取；核对题目、答案、索引、配额和合成简历恢复一致 | 通过 |

面试同步请求均未超过前端当前的 60 秒超时。动态模式验证到推进与缓存恢复，没有将它的完整评分流程另跑一遍；完整评分链路由批量面试覆盖。本次是功能连通性验证，不代表所有输入和并发场景均已覆盖。

## 清理和证据

测试结束已删除临时账户 10、11，以及本次生成的知识库、文档、切片、聊天、测验题目/记录、面试、答案和技能画像；注销测试登录会话，清理对应面试缓存。未回退已消耗的自增 ID。

最终核对：原有 13 张数据库表的记录数与测试前完全相同；ES `knowledge_chunk` 为 404 条；测试登录会话残留为 0。前端和后端探测均返回 200，服务继续运行。

日志和详细证据目录：`C:\Users\17363\AppData\Local\MemoryAI\verification\20261022-165516`。

主要文件：`processes.json`、`chat_quiz_results.json`、`chat-live.sse`、`quiz-generated.json`、`interview_results.json`、`chat_quiz_cleanup.json`、`final-verification.json`，以及 `backend.stdout.log` / `backend.stderr.log`。
