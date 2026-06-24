<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import { ragApi, type Reference } from '@/api/rag.api'
import { resumeApi, type Resume } from '@/api/resume.api'
import { connectSSE } from '@/api/sse'
import type { ToolCall } from '@/types'

interface ToolCallEntry { name: string; status: string; result?: unknown }

const inputText = ref('')
const messages = ref<{ role: string; content: string; references?: Reference[]; toolCalls?: ToolCallEntry[] }[]>([])
const showHot = ref(true)
const loading = ref(false)
const sending = ref(false)
const chatArea = ref<HTMLDivElement>()
const sessionId = ref<number | undefined>(undefined)
const showingSessions = ref(false)
const sessions = ref<{ id: number; title: string; updatedAt: string }[]>([])
const abortCtrl = ref<AbortController | null>(null)
const lastUsage = ref<{ prompt: number; completion: number; total: number } | null>(null)

const sidebarOpen = ref(false)
const resumeList = ref<Resume[]>([])
const sessionResumeId = ref<number | null>(null)

async function loadResumes() {
  try {
    const all = await resumeApi.list()
    resumeList.value = all.filter(r => r.status === 'READY')
  } catch { /* ok */ }
}

async function setSessionResume(resumeId: number | null) {
  if (!sessionId.value) return
  try {
    await ragApi.setSessionResume(sessionId.value, resumeId)
    sessionResumeId.value = resumeId
    ElMessage.success(resumeId ? '已关联简历' : '已取消简历关联')
  } catch { /* ok */ }
}

const placeholder = '自由对话，AI 会检索知识库并自动分析...'

onMounted(async () => {
  const saved = localStorage.getItem('rag_session_id')
  if (saved) {
    sessionId.value = parseInt(saved, 10)
    try {
      const msgs = await ragApi.getMessages(sessionId.value!)
      messages.value = displayMessages(msgs)
      showHot.value = messages.value.length === 0
    } catch { /* ok */ }
    // restore resumeId for this session
    try {
      const all = await ragApi.listSessions()
      const s = all.find((s: any) => s.id === sessionId.value)
      sessionResumeId.value = s?.resumeId ?? null
    } catch { /* ok */ }
  } else {
    try { sessionId.value = await ragApi.createSession(); localStorage.setItem('rag_session_id', String(sessionId.value)) } catch { /* ok */ }
  }
  loadResumes()
})

async function loadSessions() {
  try { sessions.value = await ragApi.listSessions() } catch { /* ok */ }
  showingSessions.value = true
}

async function switchSession(id: number) {
  sessionId.value = id; localStorage.setItem('rag_session_id', String(id)); showingSessions.value = false
  try {
    const msgs = await ragApi.getMessages(id)
    messages.value = displayMessages(msgs)
    showHot.value = messages.value.length === 0
  } catch {
    messages.value = []; showHot.value = true
  }
  // load session detail to get resumeId
  const sessions = await ragApi.listSessions()
  const s = sessions.find((s: any) => s.id === id)
  sessionResumeId.value = s?.resumeId ?? null
}

async function newSession() {
  try {
    stopStreaming()
    sessionId.value = await ragApi.createSession()
    localStorage.setItem('rag_session_id', String(sessionId.value))
    messages.value = []
    showHot.value = true
    ElMessage.success('新会话已创建')
  } catch { /* ok */ }
}

async function send() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  showHot.value = false
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  sending.value = true
  setTimeout(() => sending.value = false, 300)
  scrollToBottom()

  let timeoutId: ReturnType<typeof setTimeout> | undefined
  try {
    const msgIdx = messages.value.length
    const tcLog: ToolCallEntry[] = []
    messages.value.push({ role: 'assistant', content: '', toolCalls: tcLog })

    abortCtrl.value = new AbortController()
    // Frontend-level timeout: 4.5min (slightly less than backend SseEmitter 5min)
    timeoutId = setTimeout(() => abortCtrl.value?.abort(), 270_000)
    await connectSSE(
      '/api/agent/chat/stream',
      { message: text, sessionId: sessionId.value },
      {
        onToken(content) {
          messages.value[msgIdx].content += content
          scrollToBottom()
        },
        onToolCall(tc: ToolCall) {
          tcLog.push({ name: tc.toolName, status: 'running' })
          messages.value[msgIdx] = { ...messages.value[msgIdx], toolCalls: [...tcLog] }
          scrollToBottom()
        },
        onToolResult(toolName: string, result: unknown) {
          const entry = tcLog.find(e => e.name === toolName && e.status === 'running')
          if (entry) { entry.status = 'completed'; entry.result = result }
          messages.value[msgIdx] = { ...messages.value[msgIdx], toolCalls: [...tcLog] }
          scrollToBottom()
        },
        onUsage(promptTokens, completionTokens, totalTokens) {
          lastUsage.value = { prompt: promptTokens, completion: completionTokens, total: totalTokens }
        },
        onDone() {
          clearTimeout(timeoutId)
          abortCtrl.value = null
          // If SSE ended with zero content and no tool calls, mark it
          if (messages.value[msgIdx] && messages.value[msgIdx].content.length === 0 && (!messages.value[msgIdx].toolCalls || messages.value[msgIdx].toolCalls.length === 0)) {
            messages.value[msgIdx].content = '（助手未返回内容，请重试）'
          }
        },
        onError(err: Error) {
          clearTimeout(timeoutId)
          abortCtrl.value = null
          if (messages.value[msgIdx] && messages.value[msgIdx].content.length === 0) {
            messages.value[msgIdx].content = '请求失败: ' + err.message
          }
        },
      },
      abortCtrl.value.signal,
    )
  } catch (e: any) {
    clearTimeout(timeoutId)
    if (e?.name === 'AbortError') return
    const em = e?.message || ''; let err = em.includes('timeout') ? '请求超时' : em.includes('Network') ? '网络异常' : '请求失败：' + em
    ElMessage.error(err); messages.value.push({ role: 'assistant', content: '抱歉，' + err })
  } finally { loading.value = false; scrollToBottom() }
}

function stopStreaming() {
  abortCtrl.value?.abort()
  abortCtrl.value = null
  loading.value = false
}

async function scrollToBottom() { await nextTick(); const el = chatArea.value; if (el) el.scrollTop = el.scrollHeight }
function md(text: string) { return marked.parse(text, { async: false }) as string }

/** Strip raw tool-result JSON prefix from message content (LLM sometimes copies tool output verbatim) */
function cleanContent(raw: string): string {
  if (!raw.startsWith('["')) return raw
  const endIdx = raw.indexOf('"]')
  if (endIdx < 0) return raw
  const bracketIdx = raw.indexOf(']', endIdx)
  if (bracketIdx < 0) return raw
  return raw.substring(bracketIdx + 1).replace(/^[\s\n]+/, '').trim()
}

/** Filter out internal messages (tool, empty, intermediate assistant) and clean content */
function displayMessages(msgs: any[]) {
  return msgs
    .filter((m: any) => {
      if (m.role === 'tool') return false
      if (m.role === 'assistant' && !m.content) return false
      if (m.role === 'assistant' && m.metadata && m.metadata.includes('"tool_calls"')) return false
      return true
    })
    .map((m: any) => ({
      role: m.role,
      content: m.role === 'assistant' ? cleanContent(m.content) : m.content,
    }))
}
</script>

<template>
  <div class="ask-layout">
    <!-- Resume sidebar -->
    <div v-if="sidebarOpen" class="ask-sidebar">
      <div class="sb-hdr">
        <span>简历</span>
        <button class="sb-close" @click="sidebarOpen = false" title="收起">✕</button>
      </div>
      <div v-if="resumeList.length === 0" class="sb-empty">暂无已解析的简历</div>
      <div class="sb-list">
        <div
          v-for="r in resumeList" :key="r.id"
          :class="['sb-item', sessionResumeId === Number(r.id) ? 'sb-on' : '']"
          @click="setSessionResume(Number(r.id))"
        >
          <span class="sb-fn">{{ r.fileName }}</span>
          <span v-if="sessionResumeId === Number(r.id)" class="sb-chk">✓</span>
        </div>
      </div>
      <div class="sb-divider"></div>
      <div
        :class="['sb-item', sessionResumeId === null ? 'sb-on' : '']"
        @click="setSessionResume(null)"
      >
        <span class="sb-mut">不关联简历</span>
        <span v-if="sessionResumeId === null" class="sb-chk">✓</span>
      </div>
    </div>

    <!-- Sidebar open button (shown when sidebar is closed) -->
    <button v-if="!sidebarOpen" class="sb-open" @click="sidebarOpen = true" title="简历">
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M3 3h10v2H3V3zm0 4h10v2H3V7zm0 4h7v2H3v-2z" fill="currentColor"/></svg>
    </button>

    <div class="ask">
      <!-- Top bar -->
      <div class="ask-top">
        <span class="ask-title">AI 学习助手</span>
        <div class="sess-group">
          <span class="sess-id">会话 #{{ sessionId || '—' }}</span>
          <span v-if="lastUsage" class="sess-usage" title="本次对话累计消耗 token 数">↑{{ lastUsage.prompt }} ↓{{ lastUsage.completion }} = {{ lastUsage.total }}</span>
          <button class="sess-btn" @click="loadSessions">切换</button>
          <button class="sess-btn sess-new" @click="newSession">+ 新对话</button>
        </div>
      </div>

      <!-- Session list dialog -->
      <el-dialog v-model="showingSessions" title="会话列表" width="400px">
        <div v-if="!sessions.length" style="color:var(--text-mut)">暂无历史会话</div>
        <div v-for="s in sessions" :key="s.id" class="sess-row" @click="switchSession(s.id)">
          <span>{{ s.title || '会话 #' + s.id }}</span>
          <span style="font-size:11px;color:var(--text-dim)">{{ s.updatedAt?.slice(0,10) }}</span>
        </div>
      </el-dialog>

      <!-- Hot suggestions -->
      <div v-if="showHot" class="hot-zone">
        <h2 class="hot-h2">Agent 问答</h2>
        <p class="hot-p">知识检索 · 智能出题 · 自动批改 · 薄弱点分析</p>
        <div class="hot-q">
          <div v-for="q in (['什么是 CAP 定理','ThreadLocal 内存泄漏','MyBatis 一二级缓存','Spring 循环依赖'])" :key="q"
            class="hq-item" @click="inputText = q; send()">{{ q }}</div>
        </div>
      </div>

      <!-- Chat messages -->
      <div v-else ref="chatArea" class="chat-zone" role="log" aria-live="polite">
        <div v-for="(msg, i) in messages" :key="i"
          :class="['msg', msg.role, i === messages.length-1 && msg.role === 'assistant' && !loading ? 'msg-latest' : '']">
          <div class="msg-av" :class="msg.role === 'assistant' ? 'av-ai' : ''">{{ msg.role === 'user' ? 'U' : 'AI' }}</div>
          <div class="msg-bd">
            <div class="msg-bub" v-html="md(msg.content)"></div>

            <!-- Tool calls -->
            <div v-if="msg.toolCalls?.length" class="tcs">
              <div v-for="(tc, tci) in msg.toolCalls" :key="tci" :class="['tcs-i', tc.status]">
                <span class="tcs-dot"></span>
                <span class="tcs-name">{{ tc.name }}</span>
                <span class="tcs-st">{{ tc.status === 'running' ? '执行中...' : '完成' }}</span>
                <span v-if="tc.status === 'completed' && tc.result" class="tcs-ok">✓</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Streaming indicator -->
        <div v-if="loading" class="msg assistant">
          <div class="msg-av av-ai pulse">AI</div>
          <div class="msg-bd">
            <div class="think-dots"><span></span><span></span><span></span></div>
            <button class="stop-btn" @click="stopStreaming">停止生成</button>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="inp-bar">
        <div class="inp-pill">
          <textarea
            v-model="inputText"
            :placeholder="placeholder"
            class="inp-text"
            rows="1"
            :disabled="loading"
            @keydown.enter.exact.prevent="send()"
            @keydown.shift.enter.prevent="inputText += '\n'"
          ></textarea>
          <button class="inp-send" :disabled="!inputText.trim() || loading" @click="send()">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8L14 2L8 14L6 9L2 8Z" fill="currentColor"/></svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ask-layout { display: flex; height: 100%; position: relative; }

.ask {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
  padding: 24px 32px 20px;
}

/* ====== Top ====== */
.ask-top { display: flex; justify-content: space-between; align-items: center; padding-bottom: 18px; }
.ask-title { font-weight: 700; font-size: 15px; font-family: var(--font-display); }
.sess-group { display: flex; gap: 6px; align-items: center; }
.sess-id { font-size: 11px; color: var(--text-dim); font-feature-settings: "tnum"; font-family: var(--font-mono); }
.sess-usage { font-size: 10px; color: var(--text-dim); font-feature-settings: "tnum"; font-family: var(--font-mono); opacity: 0.6; }
.sess-btn { border: 1px solid var(--border); background: var(--bg-glass); color: var(--text-mut); padding: 4px 10px; border-radius: 6px; font-size: 11px; cursor: pointer; font-family: var(--font); backdrop-filter: blur(8px); transition: all 0.15s; }
.sess-btn:hover { border-color: var(--border-active); color: var(--text); }
.sess-new { border-color: var(--accent); color: var(--accent); }
.sess-new:hover { background: var(--accent-glow); }
.sess-del:hover { border-color: var(--red); color: var(--red); }
.sess-row { display: flex; justify-content: space-between; padding: 10px 14px; border-radius: 8px; cursor: pointer; transition: background 0.1s; }
.sess-row:hover { background: var(--bg-hover); }

/* ====== Hot zone ====== */
.hot-zone {
  flex: 1; text-align: center; margin-top: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding-bottom: 10vh;
}
.hot-h2 { font-family: var(--font-display); font-size: 26px; font-weight: 800; }
.hot-p { color: var(--text-mut); font-size: 13px; margin: 8px 0 32px; }
.hot-q { display: flex; flex-direction: column; gap: 8px; max-width: 440px; margin: 0 auto; }
.hq-item {
  padding: 14px 18px; background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); cursor: pointer; font-size: 14px; transition: all 0.15s;
  backdrop-filter: blur(8px);
}
.hq-item:hover { border-color: var(--border-active); background: var(--bg-hover); }

/* ====== Chat ====== */
.chat-zone { flex: 1; overflow-y: auto; padding: 8px 0 20px; display: flex; flex-direction: column; gap: 18px; }
.msg { display: flex; gap: 12px; animation: msgIn 0.3s ease-out; }
.msg.user { flex-direction: row-reverse; }
@keyframes msgIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
.msg-av {
  width: 28px; height: 28px; border-radius: 8px; background: var(--bg-glass);
  border: 1px solid var(--border); display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 700; color: var(--text-mut); flex-shrink: 0;
  backdrop-filter: blur(8px);
}
.av-ai { border-color: rgba(56,189,248,0.15); box-shadow: 0 0 8px rgba(56,189,248,0.08); }
.msg-bd { max-width: 78%; }
.msg-bub { padding: 12px 16px; border-radius: 14px; font-size: 14px; line-height: 1.7; word-break: break-word; }
.msg.user .msg-bub { background: var(--accent); color: #fff; border-bottom-right-radius: 6px; }
.msg:not(.user) .msg-bub {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-bottom-left-radius: 6px; backdrop-filter: blur(8px);
}
.msg-bub :deep(p) { margin: 0 0 6px; }
.msg-bub :deep(pre) { background: rgba(0,0,0,0.3); padding: 10px 14px; border-radius: 8px; overflow-x: auto; font-size: 12px; margin: 8px 0; }
.msg-bub :deep(code) { font-family: var(--font-mono); font-size: 12px; }
.msg-latest .av-ai { animation: glowPulse 2s ease-in-out infinite; }
@keyframes glowPulse { 0%,100% { box-shadow: 0 0 6px rgba(56,189,248,0.08); } 50% { box-shadow: 0 0 14px rgba(56,189,248,0.18); } }

/* ====== Thinking ====== */
.think-dots { display: flex; gap: 5px; padding: 12px 16px; background: var(--bg-glass); border: 1px solid var(--border); border-radius: 14px; backdrop-filter: blur(8px); }
.think-dots span { width: 5px; height: 5px; border-radius: 50%; background: var(--accent); animation: td 1.2s infinite ease-in-out; opacity: 0.4; }
.think-dots span:nth-child(2) { animation-delay: 0.2s; }
.think-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes td { 0%,80%,100% { opacity: 0.2; transform: scale(0.7); } 40% { opacity: 1; transform: scale(1.2); } }
.stop-btn { margin-top: 6px; font-size: 11px; border: 1px solid var(--border); background: var(--bg-glass); color: var(--text-mut); padding: 4px 10px; border-radius: 6px; cursor: pointer; font-family: var(--font); transition: all 0.15s; }
.stop-btn:hover { border-color: var(--red); color: var(--red); }

/* ====== Tool calls ====== */
.tcs { margin-top: 8px; display: flex; flex-direction: column; gap: 3px; }
.tcs-i { display: flex; align-items: center; gap: 6px; padding: 4px 10px; background: var(--bg-glass); border: 1px solid var(--border); border-radius: 6px; font-size: 11px; backdrop-filter: blur(4px); }
.tcs-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.tcs-i.running .tcs-dot { background: var(--amber); animation: pulse 1s infinite; }
.tcs-i.completed .tcs-dot { background: var(--green); }
.tcs-name { font-weight: 600; color: var(--text); }
.tcs-st { color: var(--text-dim); }
.tcs-ok { color: var(--green); font-size: 12px; margin-left: auto; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }

/* ====== Input ====== */
.inp-bar { padding-top: 12px; }
.inp-pill {
  display: flex; align-items: end; gap: 8px;
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: 20px; padding: 6px 6px 6px 18px;
  backdrop-filter: blur(12px);
  transition: border-color 0.25s, box-shadow 0.25s;
}
.inp-pill:focus-within { border-color: var(--border-active); box-shadow: 0 0 0 4px var(--accent-glow); }
.inp-text {
  flex: 1; background: none; border: none; color: var(--text); font-size: 14px;
  font-family: var(--font); resize: none; outline: none; line-height: 1.5;
  padding: 6px 0; max-height: 120px;
}
.inp-text::placeholder { color: var(--text-dim); }
.inp-send {
  width: 36px; height: 36px; border-radius: 50%; border: none;
  background: var(--accent); color: #fff; cursor: pointer; display: flex;
  align-items: center; justify-content: center; transition: all 0.2s; flex-shrink: 0;
}
.inp-send:hover:not(:disabled) { background: var(--accent-dim); transform: scale(1.05); }
.inp-send:disabled { opacity: 0.2; cursor: not-allowed; transform: none; }

/* ====== Sidebar open button (float left when closed) ====== */
.sb-open {
  position: absolute; left: 8px; top: 24px; z-index: 10;
  width: 24px; height: 24px; border-radius: 6px; border: 1px solid var(--border);
  background: var(--bg-glass); color: var(--text-dim); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(8px); transition: all 0.15s;
}
.sb-open:hover { border-color: var(--border-active); color: var(--text); }

/* ====== Sidebar — B style ====== */
.ask-sidebar {
  width: 200px; flex-shrink: 0;
  background: var(--bg-sidebar); border-right: 1px solid var(--border);
  padding: 24px 12px; display: flex; flex-direction: column; gap: 2px;
  overflow-y: auto;
}
.sb-hdr {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 10px; font-weight: 600; text-transform: uppercase;
  letter-spacing: .35px; color: var(--text-dim);
  padding: 0 4px 10px 8px;
}
.sb-close {
  width: 18px; height: 18px; border-radius: 4px; border: none;
  background: transparent; color: var(--text-dim); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: 10px; transition: all 0.12s;
}
.sb-close:hover { background: var(--bg-hover); color: var(--text-mut); }
.sb-empty { font-size: 11px; color: var(--text-dim); padding: 20px 8px; text-align: center; }
.sb-list { display: flex; flex-direction: column; gap: 1px; padding: 0; }

.sb-item {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 6px; cursor: pointer; font-size: 12px;
  color: var(--text-mut); border: 1px solid transparent;
  transition: all 0.12s; user-select: none;
}
.sb-item::before {
  content: ''; width: 5px; height: 5px; border-radius: 50%;
  background: currentColor; opacity: 0.35; flex-shrink: 0;
}
.sb-item:hover {
  background: var(--bg-hover);
  border-color: var(--border);
  color: var(--text);
}
.sb-item.sb-on {
  background: var(--accent-glow);
  border-color: var(--accent);
  color: var(--accent);
}
.sb-item.sb-on::before { opacity: 0.7; }

.sb-fn { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-chk { font-size: 10px; opacity: 0.5; flex-shrink: 0; }
.sb-item.sb-on .sb-chk { opacity: 0.7; }
.sb-mut { color: var(--text-dim); }

.sb-divider {
  margin: 8px 8px 4px; border-top: 1px solid var(--border);
}
</style>
