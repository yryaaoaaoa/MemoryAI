<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeBaseApi } from '@/api/knowledge-base.api'
import { quizApi } from '@/api/quiz.api'
import type { KnowledgeBase } from '@/types/knowledge-base'
import type { QuizRecordVO, QuizRecordPage } from '@/api/quiz.api'

interface QuizQuestion {
  id?: number
  question: string
  options: Record<string, string>
  answer: string
  explanation: string
}

interface QuizResult {
  correct: boolean
  score: number
  correctAnswer: string
  explanation: string
}

// ========== State ==========
type PageState = 'selection' | 'quiz' | 'results'
const pageState = ref<PageState>('selection')

// KB list
const kbList = ref<KnowledgeBase[]>([])
const loading = ref(false)
const selectedKbIds = ref<Set<number>>(new Set())

// Config
const difficulty = ref('mixed')
const questionCount = ref(10)
const topic = ref('')

// Quiz
const questions = ref<QuizQuestion[]>([])
const currentIndex = ref(0)
const userAnswers = ref<Record<number, string>>({})
const quizResults = ref<Record<number, QuizResult>>({})
const timer = ref(0)
let timerInterval: ReturnType<typeof setInterval> | null = null

// Stats
const correctCount = computed(() => Object.values(quizResults.value).filter(r => r.correct).length)
const totalAnswered = computed(() => Object.keys(userAnswers.value).length)

const labels = ['A', 'B', 'C', 'D']

// ========== Load KBs ==========
onMounted(async () => {
  try {
    const res = await knowledgeBaseApi.list()
    kbList.value = (res as any).records ?? (res as any).data?.records ?? []
  } catch {
    ElMessage.warning('加载知识库失败')
  }
})

// ========== Selection ==========
function toggleKb(id: number) {
  const s = new Set(selectedKbIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedKbIds.value = s
}

// ========== Start Quiz ==========
async function startQuiz() {
  if (selectedKbIds.value.size === 0) return

  loading.value = true
  userAnswers.value = {}
  quizResults.value = {}
  currentIndex.value = 0
  timer.value = 0

  try {
    const res = await quizApi.generateQuiz(
      Array.from(selectedKbIds.value),
      questionCount.value,
      difficulty.value,
      topic.value || undefined
    )
    questions.value = res as any as QuizQuestion[]
    if (questions.value.length === 0) {
      ElMessage.warning('出题失败，请重试')
      return
    }
    pageState.value = 'quiz'
    startTimer()
  } catch {
    ElMessage.error('出题失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function startTimer() {
  if (timerInterval) clearInterval(timerInterval)
  timerInterval = setInterval(() => timer.value++, 1000)
}

function formatTime(s: number): string {
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
}

// ========== Quiz ==========
function selectAnswer(label: string) {
  userAnswers.value[currentIndex.value] = label
}

function prevQuestion() {
  if (currentIndex.value > 0) currentIndex.value--
}
function nextQuestion() {
  if (currentIndex.value < questions.value.length - 1) currentIndex.value++
}

async function submitQuiz() {
  const unanswered = questions.value.length - totalAnswered.value
  if (unanswered > 0) {
    try {
      await ElMessageBox.confirm(
        `还有 ${unanswered} 题未作答，确认交卷吗？`,
        '交卷确认',
        { confirmButtonText: '确认', cancelButtonText: '继续答题', type: 'warning' }
      )
    } catch { return }
  }

  if (timerInterval) clearInterval(timerInterval)

  // Submit each answered question
  for (let i = 0; i < questions.value.length; i++) {
    const ans = userAnswers.value[i]
    const q = questions.value[i]
    if (!ans || !q.id) {
      quizResults.value[i] = {
        correct: false,
        score: 0,
        correctAnswer: q.answer,
        explanation: q.explanation,
      }
      continue
    }
    try {
      const res = await quizApi.submitAnswer(q.id, ans, 0)
      quizResults.value[i] = res as any as QuizResult
    } catch {
      // Fallback: local compare
      quizResults.value[i] = {
        correct: ans === q.answer,
        score: ans === q.answer ? 100 : 0,
        correctAnswer: q.answer,
        explanation: q.explanation,
      }
    }
  }

  pageState.value = 'results'
}

function retryWrong() {
  const wrong = questions.value.filter((q, i) => {
    const ans = userAnswers.value[i]
    return ans !== q.answer
  })
  if (wrong.length === 0) return
  questions.value = wrong
  userAnswers.value = {}
  quizResults.value = {}
  currentIndex.value = 0
  timer.value = 0
  pageState.value = 'quiz'
  startTimer()
}

function backToSelection() {
  if (timerInterval) clearInterval(timerInterval)
  selectedKbIds.value = new Set()
  pageState.value = 'selection'
}

// ========== History ==========
const activeTab = ref<'quiz' | 'history'>('quiz')
const records = ref<QuizRecordVO[]>([])
const recordLoading = ref(false)
const recordPage = ref(1)
const recordPageSize = ref(10)
const recordTotal = ref(0)
const recordTotalPages = ref(0)
const filterTopic = ref('')
const filterCorrect = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const expandedRecord = ref<number | null>(null)
const wrongTopics = ref<{ topic: string; count: number }[]>([])

function onFilterChange() {
  recordPage.value = 1
  fetchRecords()
}

async function fetchRecords() {
  recordLoading.value = true
  try {
    const params: Record<string, any> = { page: recordPage.value, size: recordPageSize.value }
    if (filterTopic.value) params.topic = filterTopic.value
    if (filterCorrect.value) params.correct = filterCorrect.value === 'true'
    if (filterDateFrom.value) params.dateFrom = filterDateFrom.value
    if (filterDateTo.value) params.dateTo = filterDateTo.value
    const res = await quizApi.listRecords(params)
    const data = res as unknown as QuizRecordPage
    records.value = data.records
    recordTotal.value = data.total
    recordTotalPages.value = data.totalPages
  } catch {
    ElMessage.error('加载记录失败')
    records.value = []
  } finally {
    recordLoading.value = false
  }
}

async function fetchWrongTopics() {
  try {
    const res = await quizApi.listWrongTopics()
    wrongTopics.value = res as { topic: string; count: number }[]
  } catch { /* ignore */ }
}

function switchTab(tab: 'quiz' | 'history') {
  activeTab.value = tab
  if (tab === 'history') {
    fetchWrongTopics()
    fetchRecords()
  }
}

function toggleExpand(id: number) {
  expandedRecord.value = expandedRecord.value === id ? null : id
}

function recordPrevPage() {
  if (recordPage.value > 1) {
    recordPage.value--
    fetchRecords()
  }
}

function recordNextPage() {
  if (recordPage.value < recordTotalPages.value) {
    recordPage.value++
    fetchRecords()
  }
}

function formatDuration(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function formatDate(d: string): string {
  if (!d) return ''
  return d.slice(0, 10) + ' ' + d.slice(11, 16)
}

</script>

<template>
  <div class="quiz-page">
    <!-- Header -->
    <div class="qp-hd">
      <div class="qp-tabs">
        <button :class="['qp-tab', { active: activeTab === 'quiz' }]" @click="switchTab('quiz')">刷题</button>
        <button :class="['qp-tab', { active: activeTab === 'history' }]" @click="switchTab('history')">记录</button>
      </div>
    </div>

    <!-- ====== STATE: SELECTION ====== -->
    <template v-if="activeTab === 'quiz' && pageState === 'selection'">
      <div class="qp-body">
        <div class="sel-layout">
          <!-- KB Grid -->
          <div>
            <div class="kb-grid">
              <div v-for="kb in kbList" :key="kb.id"
                :class="['kb-card', { selected: selectedKbIds.has(kb.id) }]"
                @click="toggleKb(kb.id)">
                <div class="kb-card-top">
                  <div class="kb-info">
                    <div class="kb-name">{{ kb.name }}</div>
                    <div class="kb-desc">{{ kb.description || '' }}</div>
                  </div>
                  <div :class="['kb-chk', { on: selectedKbIds.has(kb.id) }]">
                    <svg v-if="selectedKbIds.has(kb.id)" width="10" height="8" viewBox="0 0 10 8">
                      <path d="M1 4L3.5 6.5L9 1" stroke="#08080c" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                  </div>
                </div>
              </div>
            </div>
            <p class="sel-count" v-if="selectedKbIds.size === 0">选择知识库开始刷题</p>
            <p class="sel-count" v-else>已选 {{ selectedKbIds.size }} 个知识库 · {{ questionCount }} 题</p>
          </div>

          <!-- Config Panel -->
          <div class="cfg">
            <h3>刷题配置</h3>

            <div class="cfg-grp">
              <div class="cfg-lbl">难度</div>
              <div class="diff-opts">
                <button :class="['diff-btn', { on: difficulty === 'easy' }]" @click="difficulty = 'easy'">简单</button>
                <button :class="['diff-btn', { on: difficulty === 'mixed' }]" @click="difficulty = 'mixed'">混合</button>
                <button :class="['diff-btn', { on: difficulty === 'medium' }]" @click="difficulty = 'medium'">中等</button>
                <button :class="['diff-btn', { on: difficulty === 'hard' }]" @click="difficulty = 'hard'">困难</button>
              </div>
            </div>

            <div class="cfg-grp">
              <div class="cfg-lbl">题目数量 <span class="cfg-val">{{ questionCount }}</span></div>
              <input type="range" min="5" max="20" v-model.number="questionCount" class="range" />
            </div>

            <div class="cfg-grp">
              <div class="cfg-lbl">出题方向</div>
              <input
                v-model="topic"
                type="text"
                class="topic-input"
                placeholder="如：JVM、MySQL、Spring、Java基础"
              />
            </div>

            <button class="btn-start" :disabled="selectedKbIds.size === 0 || loading" @click="startQuiz">
              {{ loading ? '出题中...' : '开始刷题' }}
              <span class="btn-start-hint" v-if="!loading">{{
                selectedKbIds.size === 0
                  ? '至少选择一个知识库'
                  : topic.trim()
                    ? `出题方向：${topic.trim()} · ${questionCount} 题`
                    : '智能模式 — 按薄弱知识点出题'
              }}</span>
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- ====== STATE: QUIZ ====== -->
    <template v-if="activeTab === 'quiz' && pageState === 'quiz'">
      <div class="qp-body qp-body-full">
        <div class="quiz-layout">
          <div class="quiz-main">
            <!-- Progress -->
            <div class="quiz-progress">
              <div class="quiz-progress-top">
                <span class="quiz-p-label">答题进度</span>
                <span class="quiz-p-count">{{ currentIndex + 1 }} / {{ questions.length }}</span>
              </div>
              <div class="quiz-p-track">
                <div class="quiz-p-fill" :style="{ width: (totalAnswered / questions.length * 100) + '%' }"></div>
              </div>
            </div>

            <!-- Header -->
            <div class="quiz-hd">
              <span class="quiz-topic">Java 求职备考 · {{ difficulty === 'easy' ? '简单' : difficulty === 'hard' ? '困难' : '混合' }}</span>
              <span class="quiz-timer">{{ formatTime(timer) }}</span>
            </div>

            <!-- Question Card -->
            <transition name="slide" mode="out-in">
              <div class="q-card" :key="currentIndex">
                <div class="q-num">第 {{ currentIndex + 1 }} 题</div>
                <div class="q-text">{{ questions[currentIndex]?.question }}</div>
                <div class="q-opts">
                  <div v-for="lbl in labels" :key="lbl"
                    :class="['q-opt', { selected: userAnswers[currentIndex] === lbl }]"
                    @click="selectAnswer(lbl)">
                    <span class="q-opt-lbl">{{ lbl }}</span>
                    <span class="q-opt-txt">{{ questions[currentIndex]?.options?.[lbl] }}</span>
                  </div>
                </div>
                <div class="q-nav">
                  <div class="q-nav-left">
                    <button class="btn-nav" :disabled="currentIndex === 0" @click="prevQuestion">← 上一题</button>
                    <button class="btn-nav" :disabled="currentIndex === questions.length - 1" @click="nextQuestion">下一题 →</button>
                  </div>
                  <button class="btn-submit" @click="submitQuiz">交卷</button>
                </div>
              </div>
            </transition>
          </div>

          <!-- Sidebar -->
          <div class="quiz-side">
            <div class="qs-sec">
              <h4>答题情况</h4>
              <div class="palette">
                <div v-for="(_, i) in questions" :key="i"
                  :class="['p-dot', { current: i === currentIndex, answered: userAnswers[i] }]"
                  @click="currentIndex = i">{{ i + 1 }}</div>
              </div>
            </div>
            <div class="qs-sec">
              <h4>刷题统计</h4>
              <div class="qs-stats">
                <div class="qs-stat"><span class="qs-stat-num">{{ totalAnswered }}</span><span class="qs-stat-lbl">已答</span></div>
                <div class="qs-stat"><span class="qs-stat-num">{{ questions.length - totalAnswered }}</span><span class="qs-stat-lbl">剩余</span></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- ====== STATE: RESULTS ====== -->
    <template v-if="activeTab === 'quiz' && pageState === 'results'">
      <div class="qp-body">
        <div class="res-wrap">
          <!-- Score Hero -->
          <div class="score-hero">
            <div class="score-ring" :style="{ borderColor: (correctCount / questions.length * 100) >= 70 ? 'var(--green)' : (correctCount / questions.length * 100) >= 40 ? 'var(--amber)' : 'var(--red)' }">
              <span class="score-pct">{{ Math.round(correctCount / questions.length * 100) }}%</span>
            </div>
            <div class="score-grid">
              <div class="score-item"><span class="score-num" style="color:var(--green)">{{ correctCount }}</span><span class="score-lbl">正确</span></div>
              <div class="score-item"><span class="score-num" style="color:var(--red)">{{ questions.length - correctCount }}</span><span class="score-lbl">错误</span></div>
              <div class="score-item"><span class="score-num" style="color:var(--text)">{{ formatTime(timer) }}</span><span class="score-lbl">用时</span></div>
            </div>
          </div>

          <!-- Review List -->
          <div class="rev-list">
            <div v-for="(q, i) in questions" :key="i" class="rev-item">
              <div class="rev-top">
                <span class="rev-num">第 {{ i + 1 }} 题</span>
                <span :class="['rev-tag', quizResults[i]?.correct ? 'ok' : 'bad']">
                  {{ quizResults[i]?.correct ? '✓ 正确' : '✗ 错误' }}
                </span>
              </div>
              <div class="rev-q">{{ q.question }}</div>
              <div class="rev-answers">
                <span class="rev-yours">你的答案：<span>{{ userAnswers[i] }}. {{ q.options[userAnswers[i]] || '' }}</span></span>
                <span v-if="!quizResults[i]?.correct" class="rev-correct">正确答案：<span>{{ q.answer }}. {{ q.options[q.answer] }}</span></span>
              </div>
              <div class="rev-exp">{{ q.explanation }}</div>
            </div>
          </div>

          <!-- Actions -->
          <div class="res-actions">
            <button class="btn-secondary" @click="backToSelection">← 返回选题</button>
            <button class="btn-primary" @click="retryWrong">重做错题</button>
          </div>
        </div>
      </div>
    </template>

    <!-- ====== STATE: HISTORY ====== -->
    <template v-if="activeTab === 'history'">
      <div class="qp-body">
        <div class="history-panel">

          <!-- Filter Bar -->
          <div class="history-filters">
            <div class="hf-group">
              <label class="hf-label">知识点</label>
              <select v-model="filterTopic" @change="onFilterChange" class="hf-select">
                <option value="">全部</option>
                <option v-for="t in wrongTopics" :key="t.topic" :value="t.topic">{{ t.topic }}</option>
              </select>
            </div>
            <div class="hf-group">
              <label class="hf-label">结果</label>
              <div class="hf-tgls">
                <button :class="['hf-tgl', { active: filterCorrect === '' }]" @click="filterCorrect = ''; onFilterChange()">全部</button>
                <button :class="['hf-tgl', { active: filterCorrect === 'true' }]" @click="filterCorrect = 'true'; onFilterChange()">正确</button>
                <button :class="['hf-tgl', { active: filterCorrect === 'false' }]" @click="filterCorrect = 'false'; onFilterChange()">错误</button>
              </div>
            </div>
            <div class="hf-group">
              <label class="hf-label">起始日期</label>
              <input type="date" v-model="filterDateFrom" @change="onFilterChange" class="hf-date" />
            </div>
            <div class="hf-group">
              <label class="hf-label">截止日期</label>
              <input type="date" v-model="filterDateTo" @change="onFilterChange" class="hf-date" />
            </div>
          </div>

          <!-- Record List -->
          <div class="history-list">
            <div v-if="recordLoading" class="hl-status">加载中...</div>
            <div v-else-if="records.length === 0" class="hl-status hl-empty">暂无记录</div>
            <div v-for="r in records" :key="r.id"
              :class="['hl-item', { expanded: expandedRecord === r.id }]"
              @click="toggleExpand(r.id)">
              <div class="hl-top">
                <div class="hl-top-left">
                  <span class="hl-topic-tag">{{ r.topic || '综合' }}</span>
                  <span class="hl-date">{{ formatDate(r.createdAt) }}</span>
                </div>
                <div class="hl-top-right">
                  <span class="hl-duration">{{ formatDuration(r.duration) }}</span>
                  <span class="hl-score">{{ r.score }}分</span>
                  <span :class="['hl-result', r.correct ? 'ok' : 'bad']">{{ r.correct ? '正确' : '错误' }}</span>
                </div>
              </div>
              <div class="hl-question">{{ r.questionText }}</div>
              <Transition name="hfade">
                <div v-if="expandedRecord === r.id" class="hl-detail" @click.stop>
                  <div class="hl-opts">
                    <div v-for="(opt, key) in r.options" :key="key"
                      :class="['hl-opt', {
                        'hl-opt-user': key === r.userAnswer && key !== r.correctAnswer,
                        'hl-opt-correct': key === r.correctAnswer,
                      }]">
                      <span class="hl-opt-key">{{ key }}</span>
                      <span class="hl-opt-val">{{ opt }}</span>
                      <span v-if="key === r.correctAnswer" class="hl-opt-mark cr">✓ 正确答案</span>
                      <span v-else-if="key === r.userAnswer" class="hl-opt-mark wr">✗ 你的选择</span>
                    </div>
                  </div>
                  <div class="hl-answers" v-if="r.userAnswer !== r.correctAnswer">
                    <div class="hl-ans-row wrong">你的答案：{{ r.userAnswer }}. {{ r.options[r.userAnswer] || '' }}</div>
                    <div class="hl-ans-row correct">正确答案：{{ r.correctAnswer }}. {{ r.options[r.correctAnswer] }}</div>
                  </div>
                  <div class="hl-explanation">
                    <strong>解析：</strong>{{ r.explanation }}
                  </div>
                </div>
              </Transition>
            </div>
          </div>

          <!-- Pagination -->
          <div class="history-pagination" v-if="recordTotalPages > 1">
            <button class="hp-btn" :disabled="recordPage <= 1" @click="recordPrevPage">← 上一页</button>
            <span class="hp-info">{{ recordPage }} / {{ recordTotalPages }} 页（共 {{ recordTotal }} 条）</span>
            <button class="hp-btn" :disabled="recordPage >= recordTotalPages" @click="recordNextPage">下一页 →</button>
          </div>

        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.quiz-page { height: 100%; display: flex; flex-direction: column; }
.qp-body { flex: 1; overflow-y: auto; }
.qp-body-full { padding: 0; }

/* Header */
.qp-hd {
  display: flex; align-items: center; gap: 14px;
  padding: 24px 40px 16px; flex-shrink: 0;
}
.qp-hd h1 { font-size: 20px; font-weight: 800; letter-spacing: -.3px; }
.qp-sub {
  font-size: 12px; color: var(--text-dim);
  padding: 2px 10px; border: 1px solid var(--border);
  border-radius: 20px; font-weight: 500;
}

/* ====== Selection Layout ====== */
.sel-layout { display: grid; grid-template-columns: 1fr 300px; gap: 28px; padding: 0 40px 40px; }
.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.kb-card {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 16px;
  cursor: pointer; transition: all .2s;
}
.kb-card:hover { border-color: var(--border-active); background: var(--bg-hover); }
.kb-card.selected { border-color: var(--accent); background: var(--accent-glow); }
.kb-card-top { display: flex; justify-content: space-between; align-items: flex-start; }
.kb-info { flex: 1; }
.kb-name { font-size: 14px; font-weight: 600; margin-bottom: 2px; }
.kb-desc { font-size: 11px; color: var(--text-dim); }
.kb-chk {
  width: 20px; height: 20px; border-radius: 6px;
  border: 1.5px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; transition: all .15s;
}
.kb-chk.on { border-color: var(--accent); background: var(--accent); }

.sel-count { font-size: 12px; color: var(--text-dim); margin-top: 12px; }

/* Config Panel */
.cfg {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 24px; position: sticky; top: 0;
}
.cfg h3 { font-size: 13px; font-weight: 700; margin-bottom: 20px; }
.cfg-grp { margin-bottom: 20px; }
.cfg-lbl {
  display: flex; justify-content: space-between;
  font-size: 11px; color: var(--text-mut); font-weight: 600;
  text-transform: uppercase; letter-spacing: .3px; margin-bottom: 8px;
}
.cfg-val { font-family: var(--font-mono); color: var(--text); font-size: 12px; }

.diff-opts { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.diff-btn {
  padding: 8px; border-radius: var(--rad-xs); font-size: 12px;
  border: 1px solid var(--border); background: transparent;
  color: var(--text-mut); cursor: pointer; font-family: var(--font);
  font-weight: 500; transition: all .15s;
}
.diff-btn:hover { border-color: var(--border-active); color: var(--text); }
.diff-btn.on { border-color: var(--accent); background: var(--accent-glow); color: var(--accent); }

.range { -webkit-appearance: none; appearance: none; width: 100%; height: 4px; border-radius: 4px; background: rgba(255,255,255,0.08); outline: none; }
.range::-webkit-slider-thumb { -webkit-appearance: none; width: 16px; height: 16px; border-radius: 50%; background: var(--accent); cursor: pointer; border: 2px solid var(--bg-root); box-shadow: 0 0 0 2px var(--accent); }
.range::-moz-range-thumb { width: 16px; height: 16px; border-radius: 50%; background: var(--accent); cursor: pointer; border: 2px solid var(--bg-root); box-shadow: 0 0 0 2px var(--accent); }

.topic-input {
  width: 100%; padding: 8px 12px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: var(--bg-input);
  color: var(--text); font-size: 13px; font-family: var(--font);
  outline: none; transition: border-color .15s; box-sizing: border-box;
}
.topic-input::placeholder { color: var(--text-dim); }
.topic-input:focus { border-color: var(--accent); }

.tgl-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-top: 1px solid var(--border); }
.tgl-lbl { font-size: 12px; color: var(--text-mut); }
.tgl {
  width: 36px; height: 20px; border-radius: 12px;
  background: rgba(255,255,255,0.08); border: none; cursor: pointer;
  position: relative; transition: background .2s;
}
.tgl::after {
  content: ''; position: absolute; width: 16px; height: 16px;
  border-radius: 50%; background: #fff; top: 2px; left: 2px;
  transition: transform .2s;
}
.tgl.on { background: var(--accent); }
.tgl.on::after { transform: translateX(16px); }

.btn-start {
  width: 100%; padding: 12px; border-radius: var(--rad-sm);
  border: none; background: var(--accent); color: #08080c;
  font-size: 14px; font-weight: 700; cursor: pointer;
  font-family: var(--font); margin-top: 8px; transition: all .2s;
}
.btn-start:hover:not(:disabled) { background: #60cffa; }
.btn-start:disabled { opacity: .3; cursor: not-allowed; }
.btn-start-hint { display: block; font-size: 10px; font-weight: 400; opacity: .6; margin-top: 2px; }

/* ====== Quiz Layout ====== */
.quiz-layout { display: grid; grid-template-columns: 1fr 220px; gap: 24px; height: 100%; padding: 0 40px 24px; align-items: start; }
.quiz-main { padding-top: 24px; }

.quiz-progress { margin-bottom: 20px; }
.quiz-progress-top { display: flex; justify-content: space-between; margin-bottom: 6px; }
.quiz-p-label { font-size: 12px; color: var(--text-mut); font-weight: 600; }
.quiz-p-count { font-family: var(--font-mono); font-size: 13px; }
.quiz-p-track { height: 4px; background: rgba(255,255,255,0.06); border-radius: 4px; overflow: hidden; }
.quiz-p-fill { height: 100%; background: var(--accent); border-radius: 4px; transition: width .3s; }

.quiz-hd { display: flex; justify-content: space-between; align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--border); margin-bottom: 20px; }
.quiz-topic { font-size: 13px; color: var(--text-mut); }
.quiz-timer { font-family: var(--font-mono); font-size: 13px; color: var(--text-dim); letter-spacing: .5px; }

/* Question Card */
.q-card {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 28px;
}
.q-num { font-family: var(--font-mono); font-size: 11px; color: var(--accent); font-weight: 600; margin-bottom: 4px; letter-spacing: .5px; }
.q-text { font-size: 16px; font-weight: 600; line-height: 1.6; margin-bottom: 24px; }
.q-opts { display: flex; flex-direction: column; gap: 8px; }
.q-opt {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; background: var(--bg-input);
  border: 1px solid var(--border); border-radius: var(--rad-sm);
  cursor: pointer; transition: all .15s; font-size: 14px;
}
.q-opt:hover { border-color: var(--border-active); background: var(--bg-hover); }
.q-opt.selected { border-color: var(--accent); background: var(--accent-glow); }
.q-opt-lbl {
  width: 24px; height: 24px; border-radius: 6px;
  background: rgba(255,255,255,0.04); border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; font-family: var(--font-mono);
  color: var(--text-mut); flex-shrink: 0; transition: all .15s;
}
.q-opt.selected .q-opt-lbl { background: var(--accent); border-color: var(--accent); color: #08080c; }
.q-opt-txt { flex: 1; line-height: 1.5; }

.q-nav { display: flex; justify-content: space-between; align-items: center; margin-top: 20px; }
.q-nav-left { display: flex; gap: 8px; }
.btn-nav {
  padding: 8px 18px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: transparent;
  color: var(--text-mut); font-size: 12px; font-family: var(--font);
  cursor: pointer; transition: all .15s; font-weight: 500;
}
.btn-nav:hover:not(:disabled) { border-color: var(--border-active); color: var(--text); }
.btn-nav:disabled { opacity: .25; cursor: not-allowed; }
.btn-submit {
  padding: 8px 24px; border-radius: var(--rad-xs); border: none;
  background: var(--green); color: #08080c; font-size: 12px;
  font-weight: 700; font-family: var(--font); cursor: pointer; transition: all .2s;
}
.btn-submit:hover { background: #6ee7b7; }

/* Sidebar */
.quiz-side { padding-top: 24px; }
.qs-sec {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 20px; margin-bottom: 12px;
}
.qs-sec h4 {
  font-size: 10px; color: var(--text-dim); font-weight: 700;
  text-transform: uppercase; letter-spacing: .5px; margin-bottom: 12px;
}
.palette { display: flex; flex-wrap: wrap; gap: 6px; }
.p-dot {
  width: 28px; height: 28px; border-radius: 6px;
  background: var(--bg-input); border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-family: var(--font-mono); font-size: 11px;
  color: var(--text-dim); cursor: pointer; transition: all .12s;
}
.p-dot:hover { border-color: var(--border-active); }
.p-dot.current { border-color: var(--accent); color: var(--accent); }
.p-dot.answered { background: var(--accent-glow); border-color: rgba(56,189,248,0.2); color: var(--accent); }

.qs-stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.qs-stat { text-align: center; padding: 10px; background: var(--bg-input); border-radius: var(--rad-xs); border: 1px solid var(--border); }
.qs-stat-num { display: block; font-family: var(--font-mono); font-size: 18px; font-weight: 600; color: var(--accent); line-height: 1.2; }
.qs-stat-lbl { font-size: 10px; color: var(--text-dim); margin-top: 2px; }

/* ====== Results ====== */
.res-wrap { max-width: 700px; margin: 0 auto; padding: 24px 40px 40px; }

.score-hero {
  text-align: center; padding: 40px 20px;
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); margin-bottom: 28px;
}
.score-ring {
  width: 100px; height: 100px; border-radius: 50%; border: 6px solid var(--green);
  margin: 0 auto 16px; display: flex; align-items: center; justify-content: center;
}
.score-pct { font-family: var(--font-mono); font-size: 28px; font-weight: 700; }
.score-grid { display: flex; justify-content: center; gap: 32px; }
.score-item { text-align: center; }
.score-num { display: block; font-family: var(--font-mono); font-size: 20px; font-weight: 600; line-height: 1.2; }
.score-lbl { font-size: 11px; color: var(--text-dim); margin-top: 2px; }

.rev-list { display: flex; flex-direction: column; gap: 12px; }
.rev-item { background: var(--bg-glass); border: 1px solid var(--border); border-radius: var(--rad); padding: 20px 24px; }
.rev-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.rev-num { font-family: var(--font-mono); font-size: 11px; color: var(--text-dim); font-weight: 600; }
.rev-tag { font-size: 10px; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.rev-tag.ok { background: var(--green-dim); color: var(--green); }
.rev-tag.bad { background: var(--red-dim); color: var(--red); }
.rev-q { font-size: 14px; font-weight: 500; margin-bottom: 12px; line-height: 1.5; }
.rev-answers { display: flex; gap: 20px; font-size: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.rev-yours { color: var(--text-mut); }
.rev-yours span { color: var(--text); }
.rev-correct { color: var(--text-mut); }
.rev-correct span { color: var(--green); }
.rev-exp {
  font-size: 12px; color: var(--text-mut); line-height: 1.6;
  padding: 10px 14px; background: var(--bg-input);
  border-radius: var(--rad-xs); border: 1px solid var(--border);
}

.res-actions { display: flex; gap: 10px; justify-content: center; margin-top: 28px; padding-bottom: 40px; }
.btn-secondary {
  padding: 10px 24px; border-radius: var(--rad-sm);
  border: 1px solid var(--border); background: transparent;
  color: var(--text-mut); font-size: 13px; font-family: var(--font);
  cursor: pointer; font-weight: 600; transition: all .15s;
}
.btn-secondary:hover { border-color: var(--border-active); color: var(--text); }
.btn-primary {
  padding: 10px 24px; border-radius: var(--rad-sm); border: none;
  background: var(--accent); color: #08080c; font-size: 13px;
  font-weight: 700; font-family: var(--font); cursor: pointer; transition: all .2s;
}
.btn-primary:hover { background: #60cffa; }

/* Transition */
.slide-enter-active, .slide-leave-active { transition: all .2s ease-out; }
.slide-enter-from { opacity: 0; transform: translateX(20px); }
.slide-leave-to { opacity: 0; transform: translateX(-20px); }

/* ====== Tabs ====== */
.qp-tabs {
  display: flex; gap: 4px;
  background: var(--bg-glass);
  border: 1px solid var(--border);
  border-radius: var(--rad-sm);
  padding: 3px;
}
.qp-tab {
  padding: 6px 20px; border: none; border-radius: 6px;
  background: transparent; color: var(--text-mut);
  font-size: 13px; font-weight: 600; font-family: var(--font);
  cursor: pointer; transition: all .15s;
}
.qp-tab:hover { color: var(--text); }
.qp-tab.active { background: var(--accent); color: #08080c; }

/* ====== History Panel ====== */
.history-panel { max-width: 800px; margin: 0 auto; padding: 0 40px 40px; }

/* Filters */
.history-filters {
  display: flex; gap: 16px; align-items: flex-end;
  padding: 20px; background: var(--bg-glass);
  border: 1px solid var(--border); border-radius: var(--rad);
  margin-bottom: 20px; flex-wrap: wrap;
}
.hf-group { display: flex; flex-direction: column; gap: 4px; }
.hf-label { font-size: 11px; color: var(--text-dim); font-weight: 600; }
.hf-select, .hf-date {
  padding: 6px 10px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: var(--bg-input);
  color: var(--text); font-size: 12px; font-family: var(--font);
  outline: none;
}
.hf-select:focus, .hf-date:focus { border-color: var(--accent); }
.hf-tgls { display: flex; gap: 4px; }
.hf-tgl {
  padding: 6px 12px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: transparent;
  color: var(--text-mut); font-size: 12px; font-family: var(--font);
  cursor: pointer; transition: all .15s; font-weight: 500;
}
.hf-tgl:hover { border-color: var(--border-active); color: var(--text); }
.hf-tgl.active { border-color: var(--accent); background: var(--accent-glow); color: var(--accent); }

/* Record List */
.history-list { display: flex; flex-direction: column; gap: 8px; }
.hl-status { text-align: center; padding: 40px 20px; color: var(--text-dim); font-size: 13px; }

.hl-item {
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 16px 20px;
  cursor: pointer; transition: all .15s;
}
.hl-item:hover { border-color: var(--border-active); }
.hl-item.expanded { border-color: var(--accent); }

.hl-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.hl-top-left, .hl-top-right { display: flex; align-items: center; gap: 10px; }

.hl-topic-tag {
  font-size: 10px; padding: 2px 8px; border-radius: 4px;
  background: var(--accent-glow); color: var(--accent); font-weight: 600;
}
.hl-date { font-size: 11px; color: var(--text-dim); }
.hl-duration { font-size: 11px; color: var(--text-dim); font-family: var(--font-mono); }
.hl-score { font-size: 12px; font-weight: 600; font-family: var(--font-mono); }
.hl-result { font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.hl-result.ok { background: var(--green-dim); color: var(--green); }
.hl-result.bad { background: var(--red-dim); color: var(--red); }

.hl-question {
  font-size: 14px; font-weight: 500; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.hl-item.expanded .hl-question { -webkit-line-clamp: unset; }

/* Expanded Detail */
.hl-detail { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--border); }
.hl-opts { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.hl-opt {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: var(--bg-input);
  font-size: 13px;
}
.hl-opt.hl-opt-user { border-color: var(--red); background: var(--red-dim); }
.hl-opt.hl-opt-correct { border-color: var(--green); background: var(--green-dim); }
.hl-opt-key {
  width: 22px; height: 22px; border-radius: 4px;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; font-family: var(--font-mono);
  background: rgba(255,255,255,0.04); border: 1px solid var(--border);
  flex-shrink: 0;
}
.hl-opt.hl-opt-correct .hl-opt-key { background: var(--green); border-color: var(--green); color: #08080c; }
.hl-opt.hl-opt-user .hl-opt-key { background: var(--red); border-color: var(--red); color: #08080c; }
.hl-opt-val { flex: 1; }
.hl-opt-mark { font-size: 10px; font-weight: 600; }
.hl-opt-mark.cr { color: var(--green); }
.hl-opt-mark.wr { color: var(--red); }

.hl-answers { display: flex; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.hl-ans-row { font-size: 12px; }
.hl-ans-row.wrong { color: var(--red); }
.hl-ans-row.correct { color: var(--green); }

.hl-explanation {
  font-size: 12px; color: var(--text-mut); line-height: 1.6;
  padding: 10px 14px; background: var(--bg-input);
  border-radius: var(--rad-xs); border: 1px solid var(--border);
}

/* Pagination */
.history-pagination {
  display: flex; justify-content: center; align-items: center;
  gap: 16px; margin-top: 20px; padding-bottom: 40px;
}
.hp-btn {
  padding: 8px 18px; border-radius: var(--rad-xs);
  border: 1px solid var(--border); background: transparent;
  color: var(--text-mut); font-size: 12px; font-family: var(--font);
  cursor: pointer; transition: all .15s; font-weight: 500;
}
.hp-btn:hover:not(:disabled) { border-color: var(--border-active); color: var(--text); }
.hp-btn:disabled { opacity: .25; cursor: not-allowed; }
.hp-info { font-size: 12px; color: var(--text-dim); }

/* Transition */
.hfade-enter-active, .hfade-leave-active { transition: all .2s ease; overflow: hidden; }
.hfade-enter-from, .hfade-leave-to { opacity: 0; max-height: 0; margin-top: 0; padding-top: 0; }
.hfade-enter-to, .hfade-leave-from { opacity: 1; }
</style>
