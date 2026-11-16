<script setup lang="ts">
import { ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { confirmNoteLeave } from '@/utils/note-leave-guard'
import NoteEditor from '@/components/notes/NoteEditor.vue'
import { notesApi, type LearningNote, type SourceMessage } from '@/api/learning-notes.api'
import { useFeaturesStore } from '@/stores/features.store'
import { useAuthStore } from '@/stores/auth.store'
import { ElMessage } from 'element-plus'
import { renderMarkdown } from '@/utils/markdown'
import { ragApi, type Reference } from '@/api/rag.api'
import { resumeApi, type Resume } from '@/api/resume.api'
import { connectSSE } from '@/api/sse'
import type { ToolCall } from '@/types'

interface ToolCallEntry { name: string; status: string; result?: unknown }
interface ChatMessage {
  id?: number
  role: string
  content: string
  references?: Reference[]
  toolCalls?: ToolCallEntry[]
  status?: string
  error?: string
}

const route = useRoute()
const router = useRouter()
const features = useFeaturesStore()
const linkedNote = ref<LearningNote | null>(null)
const noteEditor = ref<{ messages: SourceMessage[]; selectedIds: number[]; note?: LearningNote } | null>(null)
const preparingNote = ref(false)

const inputText = ref('')
const messages = ref<ChatMessage[]>([])
const showHot = ref(true)
const loading = ref(false)
const sending = ref(false)
const chatArea = ref<HTMLDivElement>()
const sessionId = ref<number | undefined>(undefined)
const sessionLoading = ref(true)
const sessionError = ref('')
let sessionVersion = 0
const resumeSaving = ref(false)
const showingSessions = ref(false)
const sessions = ref<{ id: number; title: string; updatedAt: string }[]>([])
const abortCtrl = ref<AbortController | null>(null)
const lastUsage = ref<{ prompt: number; completion: number; total: number } | null>(null)

const sidebarOpen = ref(false)
const resumeList = ref<Resume[]>([])
const sessionResumeId = ref<number | null>(null)
const auth = useAuthStore()
const sessionStorageKey = () => `rag_session_id:${auth.userId}`

async function loadResumes() {
  try {
    const all = await resumeApi.list()
    resumeList.value = all.filter(r => r.status === 'READY')
  } catch { /* ok */ }
}

async function setSessionResume(resumeId: number | null) {
  if (!sessionId.value || sessionLoading.value || sessionError.value || resumeSaving.value) return
  const id = sessionId.value
  const version = sessionVersion
  resumeSaving.value = true
  try {
    await ragApi.setSessionResume(id, resumeId)
    if (version !== sessionVersion) return
    sessionResumeId.value = resumeId
    ElMessage.success(resumeId ? '已关联简历' : '已取消简历关联')
  } catch { /* request interceptor reports errors */ }
  finally { if (version === sessionVersion) resumeSaving.value = false }
}

const placeholder = '输入问题，开始对话…'

onMounted(async () => {
  void loadResumes()
  const saved = route.query.sessionId || localStorage.getItem(sessionStorageKey())
  if (saved && Number.isSafeInteger(Number(saved)) && Number(saved) > 0) await switchSession(Number(saved))
  else await newSession(false)
})

async function loadSessions() {
  try { sessions.value = await ragApi.listSessions() } catch { /* ok */ }
  showingSessions.value = true
}

async function switchSession(id: number) {
  if (noteEditor.value && !await confirmNoteLeave()) return
  const version = beginSessionChange()
  sessionId.value = id; localStorage.setItem(sessionStorageKey(), String(id)); showingSessions.value = false
  try {
    const [msgs, all] = await Promise.all([ragApi.getMessages(id), ragApi.listSessions()])
    if (version !== sessionVersion) return
    messages.value = displayMessages(msgs)
    showHot.value = messages.value.length === 0
    const session = all.find((s: any) => s.id === id)
    sessionResumeId.value = session?.resumeId ?? null
    if (session?.noteId) {
      try {
        const note = await notesApi.get(session.noteId)
        if (version === sessionVersion) linkedNote.value = note
      } catch { /* The note may have been deleted; the conversation remains available. */ }
    }
    if (version === sessionVersion) {
      if (String(route.query.sessionId) !== String(id)) await router.replace({ path: '/ask', query: { sessionId: id } })
      await focusSourceMessage()
    }
  } catch {
    if (version === sessionVersion) sessionError.value = '无法加载会话，请重试或新建对话。'
  } finally {
    if (version === sessionVersion) sessionLoading.value = false
  }
}

function beginSessionChange() {
  stopStreaming()
  sessionLoading.value = true
  sessionError.value = ''
  messages.value = []
  linkedNote.value = null
  noteEditor.value = null
  preparingNote.value = false
  sessionResumeId.value = null
  resumeSaving.value = false
  lastUsage.value = null
  showHot.value = false
  return ++sessionVersion
}

async function newSession(notify = true) {
  if (noteEditor.value && !await confirmNoteLeave()) return
  const version = beginSessionChange()
  sessionId.value = undefined
  try {
    const id = await ragApi.createSession()
    if (version !== sessionVersion) return
    sessionId.value = id
    localStorage.setItem(sessionStorageKey(), String(sessionId.value))
    showHot.value = true
    await router.replace({ path: '/ask', query: { sessionId: id } })
    if (notify) ElMessage.success('新会话已创建')
  } catch {
    if (version === sessionVersion) sessionError.value = '无法创建会话，请重试。'
  } finally {
    if (version === sessionVersion) sessionLoading.value = false
  }
}

async function send() {
  const text = inputText.value.trim()
  if (!text || loading.value || sessionLoading.value || sessionError.value || !sessionId.value) return
  showHot.value = false
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  sending.value = true
  setTimeout(() => sending.value = false, 300)
  scrollToBottom()

  let timeoutId: ReturnType<typeof setTimeout> | undefined
  const tcLog: ToolCallEntry[] = []
  messages.value.push({ role: 'assistant', content: '', toolCalls: tcLog })
  const assistantMessage = messages.value[messages.value.length - 1]!
  const controller = new AbortController()
  abortCtrl.value = controller
  try {
    // Frontend-level timeout: 4.5min (slightly less than backend SseEmitter 5min)
    timeoutId = setTimeout(() => controller.abort(), 270_000)
    await connectSSE(
      '/api/agent/chat/stream',
      { message: text, sessionId: sessionId.value },
      {
        onToken(content) {
          assistantMessage.content += content
          scrollToBottom()
        },
        onToolCall(tc: ToolCall) {
          tcLog.push({ name: tc.toolName, status: 'running' })
          assistantMessage.toolCalls = [...tcLog]
          scrollToBottom()
        },
        onToolResult(toolName: string, result: unknown) {
          const entry = tcLog.find(e => e.name === toolName && e.status === 'running')
          if (entry) { entry.status = 'completed'; entry.result = result }
          assistantMessage.toolCalls = [...tcLog]
          scrollToBottom()
        },
        onUsage(promptTokens, completionTokens, totalTokens) {
          if (abortCtrl.value === controller) {
            lastUsage.value = { prompt: promptTokens, completion: completionTokens, total: totalTokens }
          }
        },
        onStatus(status, message, messageId) {
          if (messageId) assistantMessage.id = messageId
          assistantMessage.status = status
          assistantMessage.error = message
        },
        onDone() {
          clearTimeout(timeoutId)
          if (assistantMessage.status !== 'completed') {
            if (!['stopped', 'failed', 'truncated'].includes(assistantMessage.status || '')) {
              assistantMessage.status = 'failed'
              assistantMessage.error = '连接已中断，回答未完成，请重新提问'
            }
            if (!assistantMessage.content) assistantMessage.content = responseStatus(assistantMessage)
            for (const tool of tcLog) {
              if (tool.status === 'running') tool.status = assistantMessage.status === 'stopped' ? 'stopped' : 'failed'
            }
            return
          }
          // If SSE ended with zero content and no tool calls, mark it
          if (assistantMessage.content.length === 0 && !assistantMessage.toolCalls?.length) {
            assistantMessage.content = '（助手未返回内容，请重试）'
          }
        },
        onError(err: Error) {
          clearTimeout(timeoutId)
          assistantMessage.status = assistantMessage.status === 'truncated' ? 'truncated' : 'failed'
          assistantMessage.error = '请求失败: ' + err.message
          if (assistantMessage.content.length === 0) assistantMessage.content = assistantMessage.error
          for (const tool of tcLog) if (tool.status === 'running') tool.status = 'failed'
        },
      },
      controller.signal,
    )
  } catch (e: any) {
    clearTimeout(timeoutId)
    if (e?.name === 'AbortError') {
      assistantMessage.status = 'stopped'
      if (!assistantMessage.content) assistantMessage.content = '已停止生成'
      for (const tool of tcLog) if (tool.status === 'running') tool.status = 'stopped'
      return
    }
    const em = e?.message || ''; let err = em.includes('timeout') ? '请求超时' : em.includes('Network') ? '网络异常' : '请求失败：' + em
    if (abortCtrl.value === controller) ElMessage.error(err)
    assistantMessage.status = 'failed'
    assistantMessage.error = err
    assistantMessage.content = assistantMessage.content || '抱歉，' + err
    for (const tool of tcLog) if (tool.status === 'running') tool.status = 'failed'
  } finally {
    clearTimeout(timeoutId)
    if (abortCtrl.value === controller) {
      abortCtrl.value = null
      loading.value = false
      scrollToBottom()
    }
  }
}

function stopStreaming() {
  const message = messages.value[messages.value.length - 1]
  if (loading.value && message?.role === 'assistant') {
    message.status = 'stopped'
    if (!message.content) message.content = '已停止生成'
    for (const tool of message.toolCalls || []) if (tool.status === 'running') tool.status = 'stopped'
  }
  abortCtrl.value?.abort()
  abortCtrl.value = null
  loading.value = false
}

onBeforeUnmount(() => { ++sessionVersion; stopStreaming() })
onBeforeRouteLeave(() => confirmNoteLeave())
onBeforeRouteUpdate(async (to, from) => {
  if (to.query.sessionId === from.query.sessionId) return true
  if (!await confirmNoteLeave()) return false
  noteEditor.value = null
  return true
})

async function scrollToBottom() { await nextTick(); const el = chatArea.value; if (el) el.scrollTop = el.scrollHeight }
const md = renderMarkdown

function onInputKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void send()
}

function responseStatus(message: ChatMessage) {
  if (message.status === 'stopped') return '已停止生成'
  if (message.status === 'truncated') return message.error || '回答达到长度限制，内容未完整生成'
  if (message.status === 'failed') return message.error || '回答未完成，请重新提问'
  return ''
}

function retryMessage(index: number) {
  const original = messages.value[index - 1]
  if (original?.role !== 'user') return
  inputText.value = original.content
  void send()
}

/** Hide internal roles, preserving every character of user-facing message content. */
function displayMessages(msgs: any[]): ChatMessage[] {
  return msgs.filter(m => m.role === 'user' || (m.role === 'assistant' && !m.metadata?.includes('"tool_calls"'))).map(m => {
    let metadata: Record<string, any> = {}
    if (m.metadata) {
      try { metadata = JSON.parse(m.metadata) } catch { /* Older metadata may be plain text. */ }
    }
    return { id: m.id, role: m.role, content: m.content || '', status: metadata.response_status, error: metadata.error_message }
  }).filter(m => m.role === 'user' || m.content || m.status)
}

watch(() => route.query.sessionId, id => {
  const numeric = Number(id)
  if (Number.isSafeInteger(numeric) && numeric > 0 && numeric !== sessionId.value) void switchSession(numeric)
})
watch(() => route.query.messageId, () => { void focusSourceMessage() })
async function focusSourceMessage() {
  await nextTick()
  const id = Number(route.query.messageId)
  if (Number.isSafeInteger(id) && id > 0) document.getElementById(`chat-message-${id}`)?.scrollIntoView({ block: 'center' })
}
async function openNote(index: number, update = false) {
  if (!sessionId.value || loading.value || preparingNote.value) return
  const version = sessionVersion
  const current = messages.value[index]
  preparingNote.value = true
  try {
    const stored = displayMessages(await ragApi.getMessages(sessionId.value))
      .filter((message): message is ChatMessage & { id: number } => !!message.id && !!message.content && (message.role === 'user' || !message.status || message.status === 'completed'))
    if (version !== sessionVersion) return
    const match = current?.id ? stored.findIndex(message => message.id === current.id) : -1
    if (match < 0) { ElMessage.warning('回答尚未保存完成，请稍后再试'); return }
    const answer = stored[match]!
    const previous = stored[match - 1]
    const latestNote = update && linkedNote.value ? await notesApi.get(linkedNote.value.id) : undefined
    if (version !== sessionVersion) return
    if (latestNote) linkedNote.value = latestNote
    noteEditor.value = {
      messages: stored.map(message => ({ id: message.id, role: message.role, content: message.content })),
      selectedIds: previous?.role === 'user' ? [previous.id, answer.id] : [answer.id],
      note: latestNote,
    }
  } catch { /* interceptor reports errors, current conversation is preserved */ }
  finally { if (version === sessionVersion) preparingNote.value = false }
}
function noteSaved(note: LearningNote) {
  if (linkedNote.value?.id === note.id) linkedNote.value = note
  noteEditor.value = null
}

</script>

<template>
  <div class="ask-layout">
    <NoteEditor v-if="noteEditor && sessionId" :session-id="sessionId" :messages="noteEditor.messages" :selected-ids="noteEditor.selectedIds" :note="noteEditor.note" @close="noteEditor = null" @saved="noteSaved" />
    <!-- Resume sidebar -->
    <div v-if="sidebarOpen" class="ask-sidebar" @keydown.esc="sidebarOpen = false">
      <div class="sb-hdr">
        <span>简历</span>
        <button class="sb-close" @click="sidebarOpen = false" title="收起简历" aria-label="收起简历">✕</button>
      </div>
      <div v-if="resumeList.length === 0" class="sb-empty">暂无已解析的简历</div>
      <div class="sb-list">
        <button
          v-for="r in resumeList" :key="r.id"
          :class="['sb-item', sessionResumeId === Number(r.id) ? 'sb-on' : '']"
          :aria-pressed="sessionResumeId === Number(r.id)"
          :disabled="resumeSaving || sessionLoading"
          @click="setSessionResume(Number(r.id))"
        >
          <span class="sb-fn">{{ r.fileName }}</span>
          <span v-if="sessionResumeId === Number(r.id)" class="sb-chk">✓</span>
        </button>
      </div>
      <div class="sb-divider"></div>
      <button
        :class="['sb-item', sessionResumeId === null ? 'sb-on' : '']"
        :aria-pressed="sessionResumeId === null"
        :disabled="resumeSaving || sessionLoading"
        @click="setSessionResume(null)"
      >
        <span class="sb-mut">不关联简历</span>
        <span v-if="sessionResumeId === null" class="sb-chk">✓</span>
      </button>
    </div>

    <!-- Sidebar open button (shown when sidebar is closed) -->
    <button v-if="!sidebarOpen" class="sb-open" @click="sidebarOpen = true" title="简历" aria-label="打开简历侧栏">
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
          <button class="sess-btn sess-new" @click="newSession()">+ 新对话</button>
        </div>
      </div>

      <!-- Session list dialog -->
      <el-dialog v-model="showingSessions" title="会话列表" width="400px">
        <div v-if="!sessions.length" style="color:var(--text-mut)">暂无历史会话</div>
        <button v-for="s in sessions" :key="s.id" class="sess-row" @click="switchSession(s.id)">
          <span>{{ s.title || '会话 #' + s.id }}</span>
          <span style="font-size:11px;color:var(--text-dim)">{{ s.updatedAt?.slice(0,10) }}</span>
        </button>
      </el-dialog>

      <!-- Hot suggestions -->
      <div v-if="linkedNote" class="linked-note">
        正在学习：<router-link :to="`/notes/${linkedNote.id}`">{{ linkedNote.title }}</router-link>
      </div>

      <div v-if="sessionLoading" class="hot-zone" role="status">正在加载会话…</div>
      <div v-else-if="sessionError" class="hot-zone" role="alert">
        <p>{{ sessionError }}</p>
        <button class="sess-btn" @click="sessionId ? switchSession(sessionId) : newSession(false)">重试</button>
      </div>
      <div v-else-if="showHot" class="hot-zone">
        <h2 class="hot-h2">Agent 问答</h2>
        <p class="hot-p">{{ features.ragEnabled ? '提问、查阅参考资料，把值得再学的内容留下来' : '提问、追问，把值得再学的内容留下来' }}</p>
        <div class="hot-q">
          <button v-for="q in (['什么是 CAP 定理','ThreadLocal 内存泄漏','MyBatis 一二级缓存','Spring 循环依赖'])" :key="q"
            class="hq-item" @click="inputText = q; send()">{{ q }}</button>
        </div>
      </div>

      <!-- Chat messages -->
      <div v-else ref="chatArea" class="chat-zone" role="log" aria-live="polite">
        <div v-for="(msg, i) in messages" :key="i" :id="msg.id ? `chat-message-${msg.id}` : undefined"
          :class="['msg', msg.role, i === messages.length-1 && msg.role === 'assistant' && !loading ? 'msg-latest' : '']">
          <div class="msg-av" :class="{ 'av-ai': msg.role === 'assistant', pulse: msg.role === 'assistant' && i === messages.length - 1 && loading }">{{ msg.role === 'user' ? 'U' : 'AI' }}</div>
          <div class="msg-bd">
            <div v-if="msg.content" class="msg-bub" v-html="md(msg.content)"></div>
            <div v-else-if="msg.role === 'assistant' && i === messages.length - 1 && loading" class="think-dots" role="status" aria-label="正在生成回答"><span></span><span></span><span></span></div>

            <div v-if="msg.role === 'assistant' && msg.content && (!msg.status || msg.status === 'completed') && !loading" class="note-message-actions">
              <button class="sess-btn" :disabled="preparingNote || sessionLoading" @click="openNote(i)">存为学习笔记</button>
              <button v-if="linkedNote" class="sess-btn" :disabled="preparingNote || sessionLoading" @click="openNote(i, true)">更新这条笔记</button>
            </div>
            <!-- Tool calls -->
            <div v-if="msg.toolCalls?.length" class="tcs">
              <div v-for="(tc, tci) in msg.toolCalls" :key="tci" :class="['tcs-i', tc.status]">
                <span class="tcs-dot"></span>
                <span class="tcs-name">{{ tc.name }}</span>
                <span class="tcs-st">{{ tc.status === 'running' ? '执行中...' : tc.status === 'failed' ? '失败' : tc.status === 'stopped' ? '已停止' : '完成' }}</span>
                <span v-if="tc.status === 'completed' && tc.result" class="tcs-ok">✓</span>
              </div>
            </div>
            <div v-if="responseStatus(msg) && responseStatus(msg) !== msg.content" class="response-status" role="status">{{ responseStatus(msg) }}</div>
            <button v-if="msg.role === 'assistant' && msg.status && msg.status !== 'completed' && !loading" class="sess-btn" @click="retryMessage(i)">重新提问</button>
            <button v-if="msg.role === 'assistant' && i === messages.length - 1 && loading" class="stop-btn" @click="stopStreaming">停止生成</button>
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
            aria-label="对话内容"
            rows="1"
            :disabled="loading || sessionLoading || !!sessionError || !sessionId"
            @keydown="onInputKeydown"
          ></textarea>
          <button class="inp-send" aria-label="发送消息" :disabled="!inputText.trim() || loading || sessionLoading || !!sessionError || !sessionId" @click="send()">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8L14 2L8 14L6 9L2 8Z" fill="currentColor"/></svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.linked-note { padding: 10px 0 16px; color: var(--text-mut); font-size: 13px; overflow-wrap: anywhere; }
.linked-note a { color: var(--accent); text-decoration: none; }
.note-message-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
.msg:target { outline: 1px solid var(--accent); }

.response-status { margin-top: 8px; color: var(--text-mut); font-size: 13px; }
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
.sess-usage { font-size: 10px; color: var(--text-dim); font-feature-settings: "tnum"; font-family: var(--font-mono); }
.sess-btn { border: 1px solid var(--border); background: var(--bg-glass); color: var(--text-mut); padding: 4px 10px; border-radius: 6px; font-size: 11px; cursor: pointer; font-family: var(--font); backdrop-filter: blur(8px); transition: all 0.15s; }
.sess-btn:hover { border-color: var(--border-active); color: var(--text); }
.sess-new { border-color: var(--accent); color: var(--accent); }
.sess-new:hover { background: var(--accent-glow); }
.sess-row { width: 100%; text-align: left; gap: 12px; border: none; background: transparent; color: var(--text); display: flex; justify-content: space-between; padding: 10px 14px; border-radius: 8px; cursor: pointer; transition: background 0.1s; }
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
  backdrop-filter: blur(8px); color: var(--text);
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
.msg-bd { min-width: 0; max-width: 78%; }
.msg-bub { padding: 12px 16px; border-radius: 14px; font-size: 14px; line-height: 1.7; word-break: break-word; }
.msg.user .msg-bub { background: var(--accent); color: var(--on-accent); border-bottom-right-radius: 6px; }
.msg:not(.user) .msg-bub {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-bottom-left-radius: 6px; backdrop-filter: blur(8px);
}
.msg-bub :deep(p) { margin: 0 0 6px; }
.msg-bub :deep(pre) { background: var(--bg-code); color: var(--text); padding: 10px 14px; border-radius: 8px; overflow-x: auto; font-size: 12px; margin: 8px 0; }
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
  flex: 1; min-width: 0; background: none; border: none; color: var(--text); font-size: 14px;
  font-family: var(--font); resize: none; outline: none; line-height: 1.5;
  padding: 6px 0; max-height: 120px;
}
.inp-text::placeholder { color: var(--text-dim); }
.inp-send {
  width: 36px; height: 36px; border-radius: 50%; border: none;
  background: var(--accent); color: var(--on-accent); cursor: pointer; display: flex;
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
  width: 100%; background: transparent; text-align: left;
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
.sb-item:disabled { cursor: wait; }
.inp-text:focus-visible { outline: none; }
.inp-pill:focus-within { border-color: var(--accent); }
@media (max-width: 1100px) {
  .ask { padding: 16px 0 0; }
  .ask-top { flex-wrap: wrap; gap: 12px; padding-left: 32px; }
  .sess-group { flex-wrap: wrap; }
  .sb-open { left: 0; top: 14px; }
  .ask-sidebar { position: absolute; inset: 0 auto 0 0; z-index: 15; width: min(280px, 100%); background: var(--bg-panel); box-shadow: 8px 0 24px rgba(0,0,0,.15); }
}
@media (max-width: 768px) {
  .ask { padding-top: 0; }
  .ask-top { padding-bottom: 12px; align-items: flex-start; }
  .sb-open { top: 0; width: 28px; height: 28px; }
  .sess-group { width: 100%; }
  .sess-usage { flex-basis: 100%; order: 1; }
  .sess-id, .sess-btn, .sess-usage { font-size: 12px; }
  .hot-zone { min-height: 0; overflow-y: auto; padding-bottom: 12px; justify-content: flex-start; padding-top: 24px; }
  .hot-h2 { font-size: 22px; }
  .hot-p { margin-bottom: 20px; }
  .hot-q { width: 100%; }
  .hq-item { padding: 12px; }
  .chat-zone { min-height: 0; }
  .msg { gap: 8px; }
  .msg-bd { max-width: calc(100% - 36px); }
  .msg-bub { padding: 10px 12px; }
  .inp-pill { padding-left: 12px; }
  .sb-close { width: 32px; height: 32px; font-size: 14px; }
}
</style>
