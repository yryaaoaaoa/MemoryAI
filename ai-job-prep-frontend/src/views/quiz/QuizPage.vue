<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useFeaturesStore } from '@/stores/features.store'
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

const features = useFeaturesStore()
const canGenerate = computed(() => selectedKbIds.value.size > 0 || !!topic.value.trim())

// KB list
const kbList = ref<KnowledgeBase[]>([])
const loading = ref(false)
const generationError = ref('')
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
const submitting = ref(false)
const submissionStarted = ref(false)
const submissionErrors = ref<Record<number, string>>({})
const uncertainSubmissions = new Set<number>()
const previouslySubmittedIds = new Set<number>()
const submissionMessage = computed(() => Object.entries(submissionErrors.value)
  .map(([index, message]) => `第 ${Number(index) + 1} 题：${message}`).join('；'))
const timer = ref(0)
const questionDurations = ref<Record<number, number>>({})
let timerInterval: ReturnType<typeof setInterval> | null = null
let disposed = false
onBeforeUnmount(() => { disposed = true; if (timerInterval) clearInterval(timerInterval) })

// Stats
const correctCount = computed(() => Object.values(quizResults.value).filter(r => r.correct).length)
const totalAnswered = computed(() => Object.keys(userAnswers.value).length)

const labels = ['A', 'B', 'C', 'D']

// ========== Load KBs ==========
onMounted(async () => {
  if (!features.ragEnabled) return
  try {
    const all: KnowledgeBase[] = []
    for (let page = 1; ; page++) {
      const res = await knowledgeBaseApi.list({ page, size: 100 })
      if (disposed) return
      all.push(...res.records)
      if (all.length >= res.total || res.records.length === 0) break
    }
    kbList.value = all
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
  if (loading.value || !canGenerate.value) return

  loading.value = true
  generationError.value = ''
  userAnswers.value = {}
  quizResults.value = {}
  submissionStarted.value = false
  submissionErrors.value = {}
  uncertainSubmissions.clear()
  previouslySubmittedIds.clear()
  currentIndex.value = 0
  timer.value = 0
  questionDurations.value = {}

  try {
    const res = await quizApi.generateQuiz(
      Array.from(selectedKbIds.value),
      questionCount.value,
      difficulty.value,
      topic.value.trim() || undefined
    )
    if (disposed) return
    questions.value = res as any as QuizQuestion[]
    if (questions.value.length === 0) {
      generationError.value = '未生成题目，请调整出题方向或知识库后重试'
      return
    }
    pageState.value = 'quiz'
    startTimer()
  } catch (error: any) {
    // The request interceptor already displays the specific server error once.
    generationError.value = error?.response?.data?.message || error?.message || '出题失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function startTimer() {
  if (timerInterval) clearInterval(timerInterval)
  timerInterval = setInterval(() => {
    if (activeTab.value !== 'quiz') return
    timer.value++
    questionDurations.value[currentIndex.value] = (questionDurations.value[currentIndex.value] ?? 0) + 1
  }, 1000)
}

function formatTime(s: number): string {
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
}

// ========== Quiz ==========
function selectAnswer(label: string) {
  if (submitting.value || submissionStarted.value) return
  userAnswers.value[currentIndex.value] = label
}

function prevQuestion() {
  if (currentIndex.value > 0) currentIndex.value--
}
function nextQuestion() {
  if (currentIndex.value < questions.value.length - 1) currentIndex.value++
}

async function submitQuiz() {
  if (submitting.value) return
  submitting.value = true
  try {
    const unanswered = questions.value.length - totalAnswered.value
    if (!submissionStarted.value && unanswered > 0) {
      try {
        await ElMessageBox.confirm(
          `还有 ${unanswered} 题未作答，确认交卷吗？`, '交卷确认',
          { confirmButtonText: '确认', cancelButtonText: '继续答题', type: 'warning' }
        )
      } catch { return }
    }
    submissionStarted.value = true
    if (timerInterval) clearInterval(timerInterval)

    for (let i = 0; i < questions.value.length; i++) {
      if (disposed) return
      if (quizResults.value[i]) continue // Never resubmit server-confirmed answers.
      const q = questions.value[i]
      if (!q?.id) {
        submissionErrors.value[i] = '题目缺少保存标识，请重新出题'
        continue
      }
      if (uncertainSubmissions.has(i)) {
        if (previouslySubmittedIds.has(q.id)) {
          submissionErrors.value[i] = '提交结果未确认，请查看答题记录'
          continue
        }
        // Freshly generated question IDs have no earlier attempt to confuse with this submission.
        try {
          const statuses = await quizApi.batchStatus([q.id])
          const saved = statuses[String(q.id)]
          if (saved) {
            quizResults.value[i] = saved
            delete submissionErrors.value[i]
            uncertainSubmissions.delete(i)
            continue
          }
          uncertainSubmissions.delete(i)
        } catch {
          submissionErrors.value[i] = '无法确认保存状态，请恢复网络后重试'
          continue
        }
      }
      try {
        quizResults.value[i] = await quizApi.submitAnswer(q.id, userAnswers.value[i] ?? '', questionDurations.value[i] ?? 0)
        delete submissionErrors.value[i]
      } catch (error: any) {
        if (!error?.response) {
          uncertainSubmissions.add(i)
          submissionErrors.value[i] = previouslySubmittedIds.has(q.id)
            ? '提交结果未确认，请查看答题记录'
            : '提交结果未确认，重试时将先核对保存状态'
        } else {
          submissionErrors.value[i] = '保存失败，请重试未保存题目'
        }
      }
    }
    if (Object.keys(quizResults.value).length === questions.value.length) {
      pageState.value = 'results'
    } else {
      ElMessage.warning('部分题目尚未确认保存，答案已保留')
    }
  } finally {
    submitting.value = false
  }
}

function retryWrong() {
  const wrong = questions.value.filter((_, i) => quizResults.value[i]?.correct === false)
  if (wrong.length === 0) return
  questions.value = wrong
  for (const q of wrong) if (q.id) previouslySubmittedIds.add(q.id)
  userAnswers.value = {}
  quizResults.value = {}
  submissionStarted.value = false
  submissionErrors.value = {}
  uncertainSubmissions.clear()
  currentIndex.value = 0
  timer.value = 0
  questionDurations.value = {}
  pageState.value = 'quiz'
  startTimer()
}

function backToSelection() {
  if (submitting.value) return
  if (timerInterval) clearInterval(timerInterval)
  selectedKbIds.value = new Set()
  pageState.value = 'selection'
}

// ========== History ==========
const activeTab = ref<'quiz' | 'history'>('quiz')
const records = ref<QuizRecordVO[]>([])
const recordLoading = ref(false)
const recordError = ref('')
let recordRequest = 0
const recordPage = ref(1)
const recordPageSize = ref(10)
const recordTotal = ref(0)
const recordTotalPages = ref(0)
const filterTopic = ref('')
const filterCorrect = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const expandedRecord = ref<number | null>(null)
const recordTopics = ref<string[]>([])

function onFilterChange() {
  recordPage.value = 1
  fetchRecords()
}

async function fetchRecords() {
  const requestId = ++recordRequest
  recordLoading.value = true
  recordError.value = ''
  expandedRecord.value = null
  try {
    const params: Record<string, any> = { page: recordPage.value, size: recordPageSize.value }
    if (filterTopic.value) params.topic = filterTopic.value
    if (filterCorrect.value) params.correct = filterCorrect.value === 'true'
    if (filterDateFrom.value) params.dateFrom = filterDateFrom.value
    if (filterDateTo.value) params.dateTo = filterDateTo.value
    const res = await quizApi.listRecords(params)
    if (requestId !== recordRequest || disposed) return
    const data = res as unknown as QuizRecordPage
    records.value = data.records
    recordTotal.value = data.total
    recordTotalPages.value = data.totalPages
  } catch {
    if (requestId !== recordRequest || disposed) return
    recordError.value = '加载答题记录失败，请重试'
    records.value = []
    recordTotal.value = 0
    recordTotalPages.value = 0
  } finally {
    if (requestId === recordRequest) recordLoading.value = false
  }
}

async function fetchRecordTopics() {
  try {
    recordTopics.value = await quizApi.listRecordTopics()
  } catch { /* ignore */ }
}

function switchTab(tab: 'quiz' | 'history') {
  if (submitting.value) return
  activeTab.value = tab
  if (tab === 'history') {
    fetchRecordTopics()
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
        <button :disabled="submitting" :class="['qp-tab', { active: activeTab === 'quiz' }]" @click="switchTab('quiz')">刷题</button>
        <button :disabled="submitting" :class="['qp-tab', { active: activeTab === 'history' }]" @click="switchTab('history')">记录</button>
      </div>
    </div>

    <!-- ====== STATE: SELECTION ====== -->
    <template v-if="activeTab === 'quiz' && pageState === 'selection'">
      <div class="qp-body">
        <div :class="['sel-layout', { 'without-kb': !features.ragEnabled }]">
          <!-- KB Grid -->
          <div v-if="features.ragEnabled">
            <div class="kb-grid">
              <button v-for="kb in kbList" :key="kb.id" type="button"
                :class="['kb-card', { selected: selectedKbIds.has(Number(kb.id)) }]"
                :aria-pressed="selectedKbIds.has(Number(kb.id))"
                @click="toggleKb(Number(kb.id))">
                <span class="kb-card-top">
                  <span class="kb-info">
                    <span class="kb-name">{{ kb.name }}</span>
                    <span class="kb-desc">{{ kb.description || '' }}</span>
                  </span>
                  <span :class="['kb-chk', { on: selectedKbIds.has(Number(kb.id)) }]" aria-hidden="true">
                    <svg v-if="selectedKbIds.has(Number(kb.id))" width="10" height="8" viewBox="0 0 10 8">
                      <path d="M1 4L3.5 6.5L9 1" stroke="var(--on-accent)" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                  </span>
                </span>
              </button>
            </div>
            <p class="sel-count" v-if="selectedKbIds.size === 0">选择参考知识库，或填写出题方向</p>
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
              <label for="quiz-count" class="cfg-lbl">题目数量 <span class="cfg-val">{{ questionCount }}</span></label>
              <input id="quiz-count" type="range" min="5" max="20" v-model.number="questionCount" class="range" />
            </div>

            <div class="cfg-grp">
              <label for="quiz-topic" class="cfg-lbl">{{ selectedKbIds.size ? '出题方向（选填）' : '出题方向' }}</label>
              <input
                id="quiz-topic"
                v-model="topic"
                type="text"
                class="topic-input"
                placeholder="如：JVM、MySQL、Spring、Java基础"
              />
            </div>

            <button class="btn-start" :disabled="!canGenerate || loading" @click="startQuiz">
              {{ loading ? '出题中...' : '开始刷题' }}
              <span class="btn-start-hint" v-if="!loading">{{
                !canGenerate
                  ? '请填写出题方向'
                  : topic.trim()
                    ? `出题方向：${topic.trim()} · ${questionCount} 题`
                    : '优先练习薄弱点，无匹配时综合出题'
              }}</span>
            </button>
            <p v-if="generationError" class="generation-error" role="alert">{{ generationError }}</p>
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
              <span class="quiz-topic">{{ selectedKbIds.size ? '知识库练习' : '专项练习' }} · {{ difficulty === 'easy' ? '简单' : difficulty === 'medium' ? '中等' : difficulty === 'hard' ? '困难' : '混合' }}</span>
              <span class="quiz-timer">{{ formatTime(timer) }}</span>
            </div>

            <!-- Question Card -->
            <transition name="slide" mode="out-in">
              <div class="q-card" :key="currentIndex">
                <div class="q-num">第 {{ currentIndex + 1 }} 题</div>
                <div class="q-text">{{ questions[currentIndex]?.question }}</div>
                <div class="q-opts">
                  <button v-for="lbl in labels" :key="lbl" type="button"
                    :class="['q-opt', { selected: userAnswers[currentIndex] === lbl }]"
                    :aria-pressed="userAnswers[currentIndex] === lbl"
                    :disabled="submitting || submissionStarted"
                    :aria-disabled="submitting || submissionStarted"
                    @click="selectAnswer(lbl)">
                    <span class="q-opt-lbl">{{ lbl }}</span>
                    <span class="q-opt-txt">{{ questions[currentIndex]?.options?.[lbl] }}</span>
                  </button>
                </div>
                <div class="q-nav">
                  <div class="q-nav-left">
                    <button class="btn-nav" :disabled="currentIndex === 0" @click="prevQuestion">← 上一题</button>
                    <button class="btn-nav" :disabled="currentIndex === questions.length - 1" @click="nextQuestion">下一题 →</button>
                  </div>
                   <button class="btn-submit" :disabled="submitting" @click="submitQuiz">{{ submitting ? '提交中...' : submissionStarted ? '重试未保存题目' : '交卷' }}</button>
                </div>
                 <div v-if="submissionMessage" role="alert" class="submission-error">
                   <p>{{ submissionMessage }}</p>
                   <button class="btn-nav" :disabled="submitting" @click="backToSelection">重新出题</button>
                 </div>
              </div>
            </transition>
          </div>

          <!-- Sidebar -->
          <div class="quiz-side">
            <div class="qs-sec">
              <h4>答题情况</h4>
              <div class="palette">
                <button v-for="(_, i) in questions" :key="i" type="button"
                  :class="['p-dot', { current: i === currentIndex, answered: userAnswers[i] }]"
                  :aria-label="`第 ${i + 1} 题，${userAnswers[i] ? '已答' : '未答'}`"
                  :aria-current="i === currentIndex ? 'step' : undefined"
                  @click="currentIndex = i">{{ i + 1 }}</button>
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
                <span class="rev-yours">你的答案：<span>{{ userAnswers[i] ? `${userAnswers[i]}. ${q.options[userAnswers[i]] || ''}` : '未作答' }}</span></span>
                <span v-if="!quizResults[i]?.correct" class="rev-correct">正确答案：<span>{{ q.answer }}. {{ q.options[q.answer] }}</span></span>
              </div>
              <div class="rev-exp">{{ q.explanation }}</div>
            </div>
          </div>

          <!-- Actions -->
          <div class="res-actions">
            <button class="btn-secondary" @click="backToSelection">← 返回选题</button>
            <button v-if="correctCount < questions.length" class="btn-primary" @click="retryWrong">重做错题</button>
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
              <label for="history-topic" class="hf-label">知识点</label>
              <select id="history-topic" v-model="filterTopic" @change="onFilterChange" class="hf-select">
                <option value="">全部</option>
                <option v-for="t in recordTopics" :key="t" :value="t">{{ t }}</option>
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
              <label for="history-from" class="hf-label">起始日期</label>
              <input id="history-from" type="date" v-model="filterDateFrom" @change="onFilterChange" class="hf-date" />
            </div>
            <div class="hf-group">
              <label for="history-to" class="hf-label">截止日期</label>
              <input id="history-to" type="date" v-model="filterDateTo" @change="onFilterChange" class="hf-date" />
            </div>
          </div>

          <!-- Record List -->
          <div class="history-list">
            <div v-if="recordLoading" class="hl-status">加载中...</div>
            <div v-else-if="recordError" class="hl-status" role="alert">
              <p>{{ recordError }}</p>
              <button class="btn-nav" @click="fetchRecords">重试加载</button>
            </div>
            <div v-else-if="records.length === 0" class="hl-status hl-empty">暂无记录</div>
            <div v-for="r in recordLoading || recordError ? [] : records" :key="r.id"
              :class="['hl-item', { expanded: expandedRecord === r.id }]"
              role="button" tabindex="0" :aria-expanded="expandedRecord === r.id"
              @keydown.enter.self.prevent="toggleExpand(r.id)"
              @keydown.space.self.prevent="toggleExpand(r.id)"
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
                    <div class="hl-ans-row wrong">你的答案：{{ r.userAnswer ? `${r.userAnswer}. ${r.options[r.userAnswer] || ''}` : '未作答' }}</div>
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
            <button class="hp-btn" :disabled="recordLoading || recordPage <= 1" @click="recordPrevPage">← 上一页</button>
            <span class="hp-info">{{ recordPage }} / {{ recordTotalPages }} 页（共 {{ recordTotal }} 条）</span>
            <button class="hp-btn" :disabled="recordLoading || recordPage >= recordTotalPages" @click="recordNextPage">下一页 →</button>
          </div>

        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.quiz-page { height: 100%; min-width: 0; display: flex; flex-direction: column; }
.qp-body { flex: 1; min-height: 0; overflow-y: auto; }
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
.sel-layout.without-kb { display: block; max-width: 700px; margin: 0 auto; }
.sel-layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 28px; padding: 0 40px 40px; align-items: start; }
.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(200px, 100%), 1fr)); gap: 10px; }
.kb-card {
  width: 100%; min-width: 0; text-align: left; color: var(--text); font-family: var(--font);
  background: var(--bg-glass); border: 1px solid var(--border);
  border-radius: var(--rad); padding: 16px;
  cursor: pointer; transition: all .2s;
}
.kb-card:hover { border-color: var(--border-active); background: var(--bg-hover); }
.kb-card.selected { border-color: var(--accent); background: var(--accent-glow); }
.kb-card-top { display: flex; justify-content: space-between; align-items: flex-start; }
.kb-info { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.kb-name { display: block; font-size: 14px; font-weight: 600; margin-bottom: 2px; }
.kb-desc { display: block; font-size: 12px; color: var(--text-dim); }
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

.range { -webkit-appearance: none; appearance: none; width: 100%; height: 4px; border-radius: 4px; background: var(--control-track); }
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
  border: none; background: var(--accent); color: var(--on-accent);
  font-size: 14px; font-weight: 700; cursor: pointer;
  font-family: var(--font); margin-top: 8px; transition: all .2s;
}
.btn-start:hover:not(:disabled) { background: var(--accent-dim); }
.btn-start:disabled { opacity: .3; cursor: not-allowed; }
.btn-start-hint { display: block; font-size: 12px; font-weight: 400; margin-top: 2px; overflow-wrap: anywhere; }
.generation-error { margin-top: 12px; color: var(--red); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }

/* ====== Quiz Layout ====== */
.quiz-layout { display: grid; grid-template-columns: minmax(0, 1fr) 220px; gap: 24px; padding: 0 40px 24px; align-items: start; }
.quiz-main { min-width: 0; padding-top: 24px; }

.quiz-progress { margin-bottom: 20px; }
.quiz-progress-top { display: flex; justify-content: space-between; margin-bottom: 6px; }
.quiz-p-label { font-size: 12px; color: var(--text-mut); font-weight: 600; }
.quiz-p-count { font-family: var(--font-mono); font-size: 13px; }
.quiz-p-track { height: 4px; background: var(--control-track); border-radius: 4px; overflow: hidden; }
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
  width: 100%; min-width: 0; text-align: left; color: var(--text); font-family: var(--font);
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
.q-opt.selected .q-opt-lbl { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
.q-opt-txt { flex: 1; min-width: 0; line-height: 1.5; overflow-wrap: anywhere; }

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
  background: var(--green); color: var(--on-accent); font-size: 12px;
  font-weight: 700; font-family: var(--font); cursor: pointer; transition: all .2s;
}
.btn-submit:hover:not(:disabled) { box-shadow: 0 0 0 3px var(--green-dim); }
.btn-submit:disabled { opacity: .55; cursor: not-allowed; }
.q-opt[aria-disabled='true'] { cursor: default; }
.submission-error { margin-top: 16px; color: var(--red); line-height: 1.7; }
.submission-error p { margin-bottom: 12px; }

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
  background: var(--accent); color: var(--on-accent); font-size: 13px;
  font-weight: 700; font-family: var(--font); cursor: pointer; transition: all .2s;
}
.btn-primary:hover { background: var(--accent-dim); }

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
.qp-tab.active { background: var(--accent); color: var(--on-accent); }

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
.hl-opt.hl-opt-correct .hl-opt-key { background: var(--green); border-color: var(--green); color: var(--on-accent); }
.hl-opt.hl-opt-user .hl-opt-key { background: var(--red); border-color: var(--red); color: var(--on-accent); }
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

.q-text, .rev-q, .rev-exp, .hl-question, .hl-explanation, .hl-answers { overflow-wrap: anywhere; }

@media (max-width: 1100px) {
  .sel-layout, .quiz-layout { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .cfg { position: static; }
  .quiz-side { padding-top: 0; }
}

@media (max-width: 768px) {
  .qp-hd { padding: 0 0 16px; }
  .sel-layout, .quiz-layout, .history-panel { padding: 0 0 24px; }
  .res-wrap { padding: 16px 0 24px; }
  .cfg, .q-card, .qs-sec, .history-filters, .rev-item { padding: 16px; }
  .quiz-main { padding-top: 0; }
  .quiz-hd, .q-nav, .q-nav-left, .hl-top, .hl-top-left, .hl-top-right { flex-wrap: wrap; gap: 10px; }
  .q-nav-left { flex: 1 1 100%; }
  .q-nav-left .btn-nav { flex: 1; padding: 8px; }
  .q-opt { padding: 12px; gap: 8px; }
  .score-hero { padding: 24px 12px; }
  .score-grid { gap: 20px; flex-wrap: wrap; }
  .res-actions { flex-wrap: wrap; }
  .hf-group { width: 100%; min-width: 0; }
  .hf-select, .hf-date { width: 100%; min-width: 0; }
  .hl-item { padding: 16px 12px; }
  .hl-opt { flex-wrap: wrap; }
  .hl-opt-val { min-width: 0; overflow-wrap: anywhere; }
  .hl-opt-mark { flex-basis: 100%; }
  .history-pagination { flex-wrap: wrap; gap: 12px; }
  .hp-info { flex-basis: 100%; text-align: center; order: -1; }
}
</style>
