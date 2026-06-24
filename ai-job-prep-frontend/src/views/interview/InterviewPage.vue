<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '@/api/interview.api'
import { resumeApi } from '@/api/resume.api'
import type { Resume } from '@/api/resume.api'
import { useInterviewStore } from '@/stores/interview.store'
import type { SkillDTO, SessionListItem, InterviewQuestionDTO } from '@/api/interview.api'

const router = useRouter()
const store = useInterviewStore()

type Phase = 'list' | 'setup' | 'interview' | 'completed'
const phase = ref<Phase>('list')

// === List ===
const sessions = ref<SessionListItem[]>([])
const loading = ref(false)
const filterTab = ref<'all' | 'active' | 'done'>('all')

const filteredSessions = computed(() => {
  const all = sessions.value
  if (filterTab.value === 'active') return all.filter(s => s.status === 'CREATED' || s.status === 'IN_PROGRESS')
  if (filterTab.value === 'done') return all.filter(s => s.status === 'COMPLETED' || s.status === 'EVALUATED')
  return all
})

const statusMeta = (s: SessionListItem): string => {
  if (s.status === 'EVALUATED') return '已评估'
  if (s.status === 'COMPLETED') return '已完成'
  if (s.status === 'IN_PROGRESS') return '进行中'
  return '未开始'
}

async function loadSessions() {
  loading.value = true
  try { sessions.value = await interviewApi.listSessions() }
  finally { loading.value = false }
}

function viewSession(sid: string) {
  router.push(`/interview/report/${sid}`)
}

function continueSession(sid: string) {
  store.load(sid).then(() => { phase.value = 'interview' })
}

async function deleteSession(sid: string) {
  try {
    await ElMessageBox.confirm('确定删除此面试会话？', '确认')
    await interviewApi.deleteSession(sid)
    loadSessions()
  } catch { /* cancel */ }
}

// === Setup ===
const skills = ref<SkillDTO[]>([])
const selectedSkillId = ref('java-backend')
const difficulty = ref('mid')
const questionCount = ref(6)
const interviewMode = ref('batch')
const creating = ref(false)

const resumeList = ref<Resume[]>([])
const selectedResumeId = ref<number | null>(null)

onMounted(async () => {
  loadSessions()
  interviewApi.listSkills().then(res => skills.value = res).catch(() => {})
  resumeApi.list().then(res => {
    resumeList.value = res.filter(r => r.status === 'READY')
  }).catch(() => {})
})

const skillDescr = computed(() => skills.value.find(s => s.id === selectedSkillId.value)?.description ?? '')

async function createSession() {
  creating.value = true
  try {
    await store.create({
      skillId: selectedSkillId.value,
      difficulty: difficulty.value,
      questionCount: questionCount.value,
      mode: interviewMode.value,
      resumeId: selectedResumeId.value,
    })
    phase.value = 'interview'
  } catch { /* handled */ }
  finally { creating.value = false }
}

// === Interview ===
const answerText = ref('')
const submitting = ref(false)
const currentQuestion = ref<InterviewQuestionDTO | null>(null)

watch(() => store.session, (s) => {
  if (!s) return
  currentQuestion.value = s.questions[s.currentQuestionIndex] ?? null
}, { immediate: true })

async function submitAnswer() {
  const s = store.session
  if (!s || !currentQuestion.value) return
  if (!answerText.value.trim()) { ElMessage.warning('请先填写答案'); return }
  submitting.value = true
  try {
    await interviewApi.submitAnswer(s.sessionId, currentQuestion.value.questionIndex, answerText.value)
    const updated = await store.load(s.sessionId)
    if (!updated) return
    if (updated.currentQuestionIndex >= updated.questions.length) {
      currentQuestion.value = null; phase.value = 'completed'
      ElMessage.success('全部答完！评估已开始，请稍后再查看报告')
    } else {
      currentQuestion.value = updated.questions[updated.currentQuestionIndex]
      answerText.value = ''
    }
  } catch { /* handled */ }
  finally { submitting.value = false }
}

async function completeEarly() {
  const s = store.session
  if (!s) return
  try {
    await ElMessageBox.confirm('确定提前交卷？未答题目将记为未回答。', '确认')
    await interviewApi.completeInterview(s.sessionId)
    phase.value = 'completed'
    ElMessage.success('已交卷，评估已开始')
  } catch { /* cancel */ }
}

function goToReport() {
  const sid = store.session?.sessionId
  if (sid) router.push(`/interview/report/${sid}`)
}

function statusBadgeClass(status: string): string {
  if (status === 'EVALUATED') return 'sc-badge sc-badge-ev'
  if (status === 'COMPLETED') return 'sc-badge sc-badge-cp'
  if (status === 'IN_PROGRESS') return 'sc-badge sc-badge-ip'
  return 'sc-badge sc-badge-cr'
}
</script>

<template>
<!-- ════════════ 1 · 列表 ════════════ -->
<div v-if="phase === 'list'" class="page">
  <div class="ph">
    <div><h1 class="pg-ttl">模拟面试</h1><p class="pg-sub">你的求职实战训练记录</p></div>
    <button class="btn btn-accent" @click="phase='setup'">＋ 新面试</button>
  </div>

  <div class="tabbar">
    <button :class="['tb', filterTab==='all'?'on':'']" @click="filterTab='all'">全部</button>
    <button :class="['tb', filterTab==='active'?'on':'']" @click="filterTab='active'">进行中</button>
    <button :class="['tb', filterTab==='done'?'on':'']" @click="filterTab='done'">已完成</button>
  </div>

  <div v-if="loading" class="empty">加载中...</div>
  <div v-else-if="filteredSessions.length === 0" class="empty">暂无面试记录</div>
  <div v-else class="sl">
    <div v-for="s in filteredSessions" :key="s.sessionId" class="sc">
      <div class="sc-body" @click="s.status==='EVALUATED' ? viewSession(s.sessionId) : continueSession(s.sessionId)">
        <div class="sc-hdr">
          <span class="sc-title">{{ s.skillId }}</span>
          <span :class="statusBadgeClass(s.status)">{{ statusMeta(s) }}</span>
        </div>
        <div class="sc-meta">
          <span>{{ s.difficulty === 'junior' ? '初级' : s.difficulty === 'senior' ? '高级' : '中级' }} · {{ s.totalQuestions }} 题</span>
          <span v-if="s.overallScore != null" class="sc-score" :style="{ color: s.overallScore >= 80 ? 'var(--green)' : s.overallScore >= 60 ? 'var(--amber)' : 'var(--red)' }">{{ s.overallScore }} 分</span>
          <span v-else class="sc-score">—</span>
        </div>
      </div>
      <div class="sc-acts">
        <button v-if="s.status==='CREATED'||s.status==='IN_PROGRESS'" class="sa sa-go" @click="continueSession(s.sessionId)">继续</button>
        <button v-else-if="s.status==='EVALUATED'" class="sa sa-go" @click="viewSession(s.sessionId)">报告</button>
        <button class="sa sa-del" @click="deleteSession(s.sessionId)">删除</button>
      </div>
    </div>
  </div>
</div>

<!-- ════════════ 2 · 创建 ════════════ -->
<div v-else-if="phase === 'setup'" class="page" style="max-width:520px">
  <div class="ph">
    <div><h1 class="pg-ttl">创建模拟面试</h1><p class="pg-sub">配置面试参数后开始你的训练</p></div>
    <button class="btn" @click="phase='list'">← 返回</button>
  </div>

  <div class="sg">
    <span class="sg-lbl">面试方向</span>
    <div class="chip-grid">
      <button v-for="sk in skills" :key="sk.id"
        :class="['chip', selectedSkillId===sk.id?'on':'']"
        @click="selectedSkillId=sk.id">{{ sk.name }}</button>
    </div>
    <p v-if="skillDescr" class="sg-descr">{{ skillDescr }}</p>
  </div>
  <div class="sg">
    <span class="sg-lbl">难度</span>
    <div class="chip-row">
      <button :class="['chip', difficulty==='junior'?'on':'']" @click="difficulty='junior'">初级（校招/0-1年）</button>
      <button :class="['chip', difficulty==='mid'?'on':'']" @click="difficulty='mid'">中级（1-3年）</button>
      <button :class="['chip', difficulty==='senior'?'on':'']" @click="difficulty='senior'">高级（3年+）</button>
    </div>
  </div>
  <div class="sg">
    <span class="sg-lbl">题量</span>
    <div class="chip-row">
      <button v-for="n in [3,6,10]" :key="n" :class="['chip', questionCount===n?'on':'']" @click="questionCount=n">{{ n }} 题</button>
    </div>
  </div>
  <div class="sg">
    <span class="sg-lbl">关联简历 <span style="font-weight:400;color:var(--text-dim)">（选填，结合简历内容出题）</span></span>
    <div class="chip-row">
      <button :class="['chip', selectedResumeId===null?'on':'']" @click="selectedResumeId=null">不关联</button>
      <button v-for="r in resumeList" :key="r.id"
        :class="['chip', selectedResumeId===Number(r.id)?'on':'']"
        @click="selectedResumeId=Number(r.id)">{{ r.fileName }}</button>
    </div>
    <p v-if="resumeList.length === 0" class="sg-descr">暂无已解析的简历，可前往「简历解析」页面上传</p>
  </div>
  <div class="sg">
    <span class="sg-lbl">模式</span>
    <div class="chip-row">
      <button :class="['chip', interviewMode==='batch'?'on':'']" @click="interviewMode='batch'">批量出题</button>
      <button :class="['chip', interviewMode==='dynamic'?'on':'']" @click="interviewMode='dynamic'">动态出题</button>
    </div>
    <p class="sg-descr">{{ interviewMode === 'dynamic' ? '逐题出，根据回答质量动态调整后续题目' : '一次生成所有题目，逐题回答' }}</p>
  </div>
  <div class="setup-acts">
    <button class="btn btn-accent" :disabled="creating" @click="createSession">{{ creating ? '生成中...' : '开始面试  →' }}</button>
    <button class="btn" @click="phase='list'">取消</button>
  </div>
</div>

<!-- ════════════ 3 · 答题 ════════════ -->
<div v-else-if="phase === 'interview'" class="page">
  <div class="ph">
    <div><h1 class="pg-ttl">模拟面试</h1><p class="pg-sub">{{ store.session ? 'Java 后端' : '' }}</p></div>
    <button class="btn" @click="phase='list'">退出</button>
  </div>

  <div class="pv">
    <div class="pv-bar"><div class="pv-fill" :style="{ width: store.session ? (store.session.currentQuestionIndex / Math.max(store.session.totalQuestions, 1) * 100) + '%' : '0%' }" /></div>
    <span class="pv-lbl">Q{{ (store.session?.currentQuestionIndex ?? 0) + 1 }}</span>
  </div>

  <template v-if="currentQuestion">
    <div class="qb">
      <span class="qb-tag">● {{ currentQuestion.category }}</span>
      <p class="qb-t">{{ currentQuestion.question }}</p>
      <div v-if="currentQuestion.isFollowUp" class="qb-fup">追问基于：#{{ currentQuestion.parentQuestionIndex }}</div>
    </div>
    <div class="ans-area">
      <span class="ans-lbl">你的回答</span>
      <textarea v-model="answerText" class="ans-ta" :placeholder="currentQuestion.userAnswer ?? '输入你的回答...'" rows="6" />
    </div>
    <div class="iv-acts">
      <button class="btn btn-accent" :disabled="submitting" @click="submitAnswer">{{ submitting ? '提交中...' : '提交并继续' }}</button>
      <button class="btn" @click="answerText = currentQuestion.userAnswer ?? ''">暂存</button>
      <button class="btn btn-del" @click="completeEarly">提前交卷</button>
    </div>
  </template>
  <div v-else class="empty">加载题目中...</div>
</div>

<!-- ════════════ 4 · 完成 ════════════ -->
<div v-else-if="phase === 'completed'" class="page" style="text-align:center;padding-top:80px">
  <div class="done-icon">✓</div>
  <h2 class="done-title">面试完成</h2>
  <p class="done-sub">{{ store.session?.totalQuestions ?? 0 }} 题已提交评估</p>
  <div style="margin-top:24px;display:flex;gap:10px;justify-content:center">
    <button class="btn btn-accent" @click="goToReport">查看报告</button>
    <button class="btn" @click="() => { store.clear(); phase='list'; loadSessions() }">返回列表</button>
  </div>
</div>
</template>

<style scoped>
/* ── root page ── */
.page { max-width:min(100% - 40px, 900px); margin:0 auto; }
.ph { display:flex; align-items:center; justify-content:space-between; margin-bottom:28px; flex-wrap:wrap; gap:14px; }
.pg-ttl { font-family:var(--font-display); font-size:26px; font-weight:800; letter-spacing:-.5px; }
.pg-sub { font-size:13px; color:var(--text-mut); margin-top:2px; }
.empty { text-align:center; padding:80px 0; color:var(--text-dim); font-size:14px; }

/* ── buttons ── */
.btn { background:transparent; color:var(--text); border:1px solid var(--border); padding:9px 22px; border-radius:10px; cursor:pointer; font-size:13px; font-family:var(--font); transition:all .15s; white-space:nowrap; }
.btn:hover { border-color:var(--accent); color:var(--accent); }
.btn-accent { background:var(--accent); color:#000; border-color:var(--accent); font-weight:600; }
.btn-accent:hover { background:var(--accent-dim); border-color:var(--accent-dim); color:#fff; }
.btn-accent:disabled { opacity:0.4; cursor:not-allowed; }
.btn-del { color:var(--red); border-color:rgba(248,113,113,0.2); }
.btn-del:hover { border-color:var(--red); color:var(--red); background:rgba(248,113,113,0.04); }

/* ── tab bar ── */
.tabbar { display:flex; gap:2px; background:var(--bg-glass); border:1px solid var(--border); border-radius:12px; padding:3px; margin-bottom:24px; width:fit-content; }
.tb { padding:7px 20px; border:none; border-radius:10px; background:transparent; color:var(--text-mut); cursor:pointer; font-size:12px; font-family:var(--font); transition:all .12s; }
.tb.on { background:var(--accent); color:#000; font-weight:600; }
.tb:hover:not(.on) { color:var(--text); }

/* ── session list ── */
.sl { display:flex; flex-direction:column; gap:6px; }
.sc { display:flex; align-items:center; background:var(--bg-glass); border:1px solid var(--border); border-radius:12px; overflow:hidden; transition:all .2s; }
.sc:hover { border-color:var(--border-active); }
.sc-body { flex:1; padding:16px 22px; cursor:pointer; display:flex; align-items:center; gap:14px; }
.sc-hdr { display:flex; align-items:center; gap:10px; min-width:0; flex:1; }
.sc-title { font-weight:600; font-size:14px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.sc-badge { font-size:11px; padding:2px 10px; border-radius:6px; font-weight:500; border:1px solid var(--border); background:rgba(255,255,255,0.02); white-space:nowrap; }
.sc-badge-ev { border-color:var(--green); color:var(--green); background:rgba(52,211,153,0.06); }
.sc-badge-ip { border-color:var(--accent); color:var(--accent); background:rgba(56,189,248,0.06); }
.sc-badge-cp { border-color:var(--amber); color:var(--amber); background:rgba(251,191,36,0.06); }
.sc-badge-cr { border-color:var(--text-dim); color:var(--text-mut); }
.sc-meta { display:flex; gap:14px; font-size:12px; color:var(--text-mut); white-space:nowrap; }
.sc-score { font-family:var(--font-display); font-weight:700; }
.sc-acts { display:flex; border-left:1px solid var(--border); }
.sa { background:transparent; border:none; color:var(--text-mut); padding:6px 18px; cursor:pointer; font-size:12px; transition:all .12s; font-family:var(--font); white-space:nowrap; }
.sa:hover { background:var(--bg-hover); color:var(--text); }
.sa-go { color:var(--accent); }
.sa-del:hover { color:var(--red); }

/* ── setup ── */
.sg { margin-bottom:22px; }
.sg-lbl { display:block; font-size:12px; font-weight:600; margin-bottom:8px; color:var(--text); }
.sg-descr { font-size:12px; color:var(--text-dim); margin-top:10px; line-height:1.7; }
.chip-grid { display:flex; flex-wrap:wrap; gap:6px; }
.chip-row { display:flex; flex-wrap:wrap; gap:6px; }
.chip { padding:8px 18px; border:1px solid var(--border); border-radius:10px; background:transparent; color:var(--text-mut); cursor:pointer; font-size:13px; transition:all .12s; font-family:var(--font); }
.chip:hover { border-color:var(--accent); color:var(--text); }
.chip.on { border-color:var(--accent); background:var(--accent-glow); color:var(--accent); }

/* ── progress ── */
.pv { display:flex; align-items:center; gap:10px; margin-bottom:24px; }
.pv-bar { flex:1; height:3px; background:rgba(255,255,255,0.06); border-radius:2px; overflow:hidden; }
.pv-fill { height:100%; background:var(--accent); border-radius:2px; transition:width .35s; }
.pv-lbl { font-size:13px; color:var(--text-mut); white-space:nowrap; font-weight:600; }

/* ── question box ── */
.qb { border:1px solid var(--border); border-radius:14px; padding:24px 28px; margin-bottom:20px; }
.qb-tag { display:inline-flex; align-items:center; gap:4px; font-size:12px; color:var(--accent); font-weight:500; margin-bottom:12px; }
.qb-t { font-size:15px; line-height:1.8; color:var(--text); }
.qb-fup { margin-top:16px; padding:14px 18px; border-left:2px solid #a78bfa; background:rgba(167,139,250,0.03); border-radius:0 8px 8px 0; font-size:13px; color:#a78bfa; line-height:1.7; }
.qb-fup::before { content:'追问'; display:block; font-size:10px; font-weight:600; margin-bottom:4px; opacity:.5; letter-spacing:.5px; }

/* ── answer ── */
.ans-area { margin-bottom:16px; }
.ans-lbl { display:block; font-size:12px; font-weight:600; margin-bottom:8px; color:var(--text); }
.ans-ta { width:100%; min-height:140px; resize:vertical; background:var(--bg-input); border:1px solid var(--border); border-radius:12px; color:var(--text); font-family:var(--font); font-size:14px; line-height:1.7; padding:16px 18px; transition:all .2s; outline:none; }
.ans-ta:focus { border-color:var(--border-active); box-shadow:0 0 0 3px var(--accent-glow); }
.ans-ta::placeholder { color:var(--text-dim); }

/* ── actions ── */
.iv-acts { display:flex; gap:10px; flex-wrap:wrap; }

/* ── done ── */
.done-icon { display:inline-flex; width:52px; height:52px; border-radius:50%; background:var(--green); color:#000; font-size:22px; align-items:center; justify-content:center; margin-bottom:16px; font-weight:700; }
.done-title { font-family:var(--font-display); font-size:22px; font-weight:800; margin-bottom:8px; }
.done-sub { font-size:14px; color:var(--text-mut); }

/* ── setup acts ── */
.setup-acts { display:flex; gap:10px; margin-top:28px; }
</style>
