# 前端缺陷检查（2026-10-22）

本轮先修复 AI 对话重复气泡，再检查聊天、登录和状态存储、知识库/文档、ES 管理、简历、刷题、面试和用户画像。下列 11 项记录的是修复前的问题及证据，随后已按用户要求完成修复；当前行为和验证见 [前端修复记录](frontend-fixes-2026-10-22.md)。下文行号对应检查时的代码，修改后可能偏移。P1 优先处理数据丢失或错误操作，P2 为功能或状态错误；本报告不是全量功能通过声明。

## 已修复：AI 回答重复气泡

`AskAI.vue` 原先既插入空 assistant 消息，又另绘制带 AI 头像的加载行。现在等待动画、流式内容和停止按钮共用同一消息行。连接失败也更新原占位消息；首字出现前停止会显示“已停止生成”，不留下空气泡。旧请求收尾只清理自身状态，避免干扰随后启动的请求。

验证：Chrome 下 8 个浏览器测试全部通过，其中新增等待→流式→结束、首字前停止、SSE 连接失败的回归检查；`pnpm build` 通过。测试使用受控 SSE/模拟接口以稳定覆盖中间状态，不调用真实模型、不写数据库。已查看等待状态截图，只有一个 AI 头像和一个加载气泡。Vite 已热更新，后端不需要重启。

## 额外发现

### 1. [P1] 面试“暂存”会清空未保存的输入

- 位置：[InterviewPage.vue:264](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/interview/InterviewPage.vue:264)。
- 触发：新题输入答案，点击“暂存”。
- 原因：点击表达式仅执行 `answerText = currentQuestion.userAnswer ?? ''`，没有调用保存 API。新题的旧答案为 null，所以输入立即被清空。
- 建议：调用已有 `interviewApi.saveAnswer`，成功后同步草稿，失败时保留输入。
- 证据：模板和现有保存接口直接核对。

### 2. [P1] 切换知识库时，旧库文档可能出现在新库标题下

- 位置：[knowledge-base.store.ts:26](C:/workspace/MemoryAI/ai-job-prep-frontend/src/stores/knowledge-base.store.ts:26)、[KnowledgeBaseDetail.vue:183](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/admin/KnowledgeBaseDetail.vue:183)。
- 触发：先访问 A 库，再访问 B 库；B 的详情较快返回，文档请求较慢或失败。
- 原因：详情和文档共用一个 `loading`，任一个请求结束就关遮罩；切换时不清空旧 `documents`。页面可能呈现 B 标题和 A 文档，删除操作仍使用 A 文档的 ID，存在删错文件风险。迟到响应也没有版本校验。
- 建议：切换时重置数据，分开加载状态，拒绝旧请求结果；退出账号时清理用户相关 Store。
- 证据：并行请求、共享 Store 及按文档 ID 删除的接口链路核对；未执行真实删除。

### 3. [P1] 刷题交卷没有锁，双击会重复写答题记录

- 位置：[QuizPage.vue:124](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/quiz/QuizPage.vue:124)、[QuizPage.vue:397](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/quiz/QuizPage.vue:397)。
- 触发：全部题目已答，快速点击两次“交卷”。
- 原因：没有提交锁或禁用状态，两轮逐题请求并发执行。后端每次提交都会写记录并更新掌握度。
- 建议：入口加提交锁，期间禁止重复交卷和修改答案；部分失败重试只补交未确认的题目。
- 证据：原函数与 mock API 执行，两题产生 `[101,101,102,102]` 四次 POST；已核对后端 `WrongQuestionService` 的插入行为。

### 4. [P2] 刷题保存失败仍展示正常成绩，掩盖未提交状态

- 位置：[QuizPage.vue:151](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/quiz/QuizPage.vue:151)。
- 触发：题目已加载，断网后交卷。
- 原因：每次请求失败都被本地答案比较替代，最后无条件进入结果页；历史、错题和画像没有相应保存。
- 建议：明确显示提交失败/未保存，保留失败项并提供重试；本地估分与服务端确认结果分开表示。
- 证据：原函数中所有 POST 模拟抛错后仍进入 `results`，两题均显示正确和 100 分。

### 5. [P2] 快速切换聊天会话会显示另一会话的历史

- 位置：[AskAI.vue:79](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/ask/AskAI.vue:79)。
- 触发：切到响应慢的 A 会话，再切到响应快的 B 会话；A 请求最后返回。
- 原因：先改变 `sessionId`，随后任何返回结果都直接覆盖 `messages`，没有核对当前会话或取消旧读取。
- 后果：界面和存储中的会话 ID 是 B，气泡却是 A；接下来输入仍会发往 B，用户容易误认对话上下文。加载期间发送的新气泡也可能被读取结果覆盖。
- 建议：用切换版本或取消控制器，仅接收当前会话的读取；加载期间清空或遮挡旧内容并禁用发送。
- 证据：真实 Chrome 操作、模拟延迟接口，已复现 `sessionId=30`、localStorage=30、气泡却为会话 20 的历史。未请求真实用户数据。

### 6. [P2] 评估中的已完成面试被当作可继续答题

- 位置：[InterviewPage.vue:166](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/interview/InterviewPage.vue:166)。
- 触发：交卷后、评分完成前，返回列表再点击状态 `COMPLETED` 的面试。
- 原因：只有 `EVALUATED` 进入报告，其他状态都走继续答题。全部答完时显示“加载题目中”；提前交卷时显示旧题，但后端已拒绝修改。
- 建议：`COMPLETED` 进入报告等待/轮询页；仅 `CREATED/IN_PROGRESS` 继续答题。
- 证据：状态分支和后端完成/报告契约核对。

### 7. [P2] 提前交卷不保存当前输入

- 位置：[InterviewPage.vue:124](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/interview/InterviewPage.vue:124)。
- 触发：当前题已输入答案，未点“提交并继续”，直接确认提前交卷。
- 原因：只调用完成接口，没有保存或传递 `answerText`；当前题按未回答评估，结束后不能补录。
- 建议：交卷前先保存当前草稿；保存失败则不结束面试。
- 证据：前端请求和后端完成流程核对。与第 1 项独立，即使修好暂存按钮，提前交卷仍需处理草稿。

### 8. [P2] 知识库和文档列表只取首页，没有翻页入口

- 位置：[knowledge-base.store.ts:15](C:/workspace/MemoryAI/ai-job-prep-frontend/src/stores/knowledge-base.store.ts:15)、[knowledge-base.store.ts:52](C:/workspace/MemoryAI/ai-job-prep-frontend/src/stores/knowledge-base.store.ts:52)、[KnowledgeBase.vue:11](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/knowledge/KnowledgeBase.vue:11)。
- 触发：超过 10 个可见知识库，或单库超过 10 个文档；普通入口另有 20 条限制。
- 原因：管理页固定默认 `page=1,size=10`，取得 total 后未接分页器；普通入口只请求 20 条。
- 后果：后续数据无法从对应界面进入、查看或管理，容易被误认为丢失。
- 建议：各列表独立保存页码/总数并提供分页或加载更多。
- 证据：页面完整模板、请求参数和后端分页契约核对。

### 9. [P2] ES 每页条数被误当成页码

- 位置：[EsIndexDetail.vue:112](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/admin/EsIndexDetail.vue:112)、[EsIndexDetail.vue:327](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/admin/EsIndexDetail.vue:327)。
- 触发：从 20 条/页改成 50 条/页。
- 原因：`size-change` 与 `current-change` 都调用 `onPageChange`，50 被赋给 `docsPage`，发出 `page=50,size=50`。分页组件后续纠正时还可能产生竞争请求。
- 建议：单独处理每页条数变化，重置 page=1 后查询一次。
- 证据：事件参数、请求构造和后端 offset 计算核对。

### 10. [P2] 上传列表移除文件后，仍可上传该文件

- 位置：[KnowledgeBaseDetail.vue:257](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/admin/KnowledgeBaseDetail.vue:257)。
- 触发：选择文件，再点文件列表的移除，随后点“开始上传”。
- 原因：只绑定 `on-change`，未处理 `on-remove`；业务变量 `selectedFile` 仍指向原文件，上传按钮继续启用。
- 建议：受控文件列表；移除、关闭和非法文件选择时同步清空待上传值。
- 证据：上传处理函数及本地 Element Plus 移除实现核对，移除不会触发用于选中文件的 on-change。

### 11. [P2] 简历解析失败后缺少恢复入口

- 位置：[ResumeParser.vue:63](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/resume/ResumeParser.vue:63)、[ResumeParser.vue:176](C:/workspace/MemoryAI/ai-job-prep-frontend/src/views/resume/ResumeParser.vue:176)。
- 触发：简历因模型或解析异常进入 `PENDING_RETRY/FAILED`，之后外部服务恢复。
- 原因：页面没有重试/删除入口，也不显示错误详情；同文件重传被后端去重并直接返回原失败记录，不重新解析。`PENDING_RETRY` 也没有后台定时重试实现。
- 建议：显示错误，接入已有 reanalyze/delete API，允许重新选择原文件重试。
- 证据：页面状态分组、按钮条件、API 和后端去重/重试代码核对。

## 范围和复现材料

聊天竞争问题的独立 Chrome 复现脚本、JSON 和截图在 `C:\Users\17363\AppData\Local\MemoryAI\verification\frontend-review-20261022`。重复气泡回归测试保存在 `tests/browser/security.spec.ts`，截图在前端忽略目录 `test-results`。其他发现为源码和接口契约核对，其中刷题的两项额外运行了原函数的模拟请求复现。

本轮没有操作原有账号、真实业务记录或用户正在使用的浏览器，也没有为审查调用付费模型。用户画像等已阅读模块未列出问题，不等于其所有场景已验证通过。
