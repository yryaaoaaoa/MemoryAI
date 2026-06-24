<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { interviewApi } from '@/api/interview.api'
import type { InterviewReportDTO } from '@/api/interview.api'

const route = useRoute()
const router = useRouter()
const sessionId = route.params.sessionId as string

const report = ref<InterviewReportDTO | null>(null)
const loading = ref(true)
const polling = ref(false)
const error = ref('')

async function fetchReport() {
  loading.value = true
  polling.value = false
  try {
    const res = await interviewApi.getReport(sessionId)
    if (res && 'ready' in res && res.ready === false) {
      polling.value = true
      setTimeout(fetchReport, 3000)
      return
    }
    report.value = res as InterviewReportDTO
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(fetchReport)

function scoreColor(s: number) {
  return s >= 80 ? 'var(--green)' : s >= 60 ? 'var(--amber)' : 'var(--red)'
}

function scoreLevel(s: number): string {
  if (s >= 80) return '优秀'
  if (s >= 60) return '良好'
  return '需加强'
}

function circumference() { return 2 * Math.PI * 40 }

function back() { router.push('/interview') }
</script>

<template>
  <!-- loading -->
  <div v-if="loading || polling" class="loading">
    <div class="spinner" />
    <p>{{ polling ? '评估进行中，自动刷新中...' : '加载中...' }}</p>
  </div>

  <!-- error -->
  <div v-else-if="error" class="loading">
    <p style="color:var(--red)">{{ error }}</p>
    <button class="btn" @click="back" style="margin-top:12px">返回</button>
  </div>

  <!-- ════════ report ════════ -->
  <div v-else-if="report" class="rpt">
    <!-- Header: score ring -->
    <div class="rpt-hdr">
      <div class="rpt-ring">
        <svg viewBox="0 0 100 100" class="rpt-svg">
          <circle cx="50" cy="50" r="40" class="rpt-track" />
          <circle cx="50" cy="50" r="40" class="rpt-arc"
            :stroke="scoreColor(report.overallScore)"
            :stroke-dasharray="circumference() * report.overallScore / 100 + ' ' + circumference()" />
        </svg>
        <span class="rpt-num" :style="{ color: scoreColor(report.overallScore) }">{{ report.overallScore }}</span>
      </div>
      <div>
        <h1 class="rpt-ttl">{{ scoreLevel(report.overallScore) }}</h1>
        <p class="rpt-meta">共 {{ report.totalQuestions }} 题 · {{ report.categoryScores.length }} 个类别</p>
      </div>
      <button class="btn" @click="back" style="margin-left:auto">← 返回</button>
    </div>

    <!-- Overall feedback -->
    <div class="sec">
      <div class="sec-ttl">总体评价</div>
      <p class="sec-text">{{ report.overallFeedback || '无' }}</p>
    </div>

    <!-- Strengths & Improvements -->
    <div class="rpt-grid-2">
      <div class="sec">
        <div class="sec-ttl" style="color:var(--green)"><span class="sec-dot" style="background:var(--green)"></span>优势</div>
        <ul class="sec-list">
          <li v-for="s in report.strengths" :key="s">{{ s }}</li>
          <li v-if="report.strengths.length === 0" class="sec-dim">无</li>
        </ul>
      </div>
      <div class="sec">
        <div class="sec-ttl" style="color:var(--amber)"><span class="sec-dot" style="background:var(--amber)"></span>改进建议</div>
        <ul class="sec-list">
          <li v-for="s in report.improvements" :key="s">{{ s }}</li>
          <li v-if="report.improvements.length === 0" class="sec-dim">无</li>
        </ul>
      </div>
    </div>

    <!-- Category scores -->
    <div class="sec" v-if="report.categoryScores.length > 0">
      <div class="sec-ttl">分类得分</div>
      <div v-for="cs in report.categoryScores" :key="cs.category" class="cr">
        <span class="cr-lbl">{{ cs.category }}</span>
        <div class="cr-bar"><div class="cr-fill" :style="{ width: cs.score + '%', background: scoreColor(cs.score) }" /></div>
        <span class="cr-sc" :style="{ color: scoreColor(cs.score) }">{{ cs.score }}</span>
        <span class="cr-cnt">{{ cs.questionCount }} 题</span>
      </div>
    </div>

    <!-- Question details -->
    <div class="sec" v-if="report.questionDetails.length > 0">
      <div class="sec-ttl">题目详情</div>
      <div v-for="qd in report.questionDetails" :key="qd.questionIndex" class="qd">
        <div class="qd-hdr">
          <span class="qd-idx">Q{{ qd.questionIndex + 1 }}</span>
          <span class="qd-cat">{{ qd.category }}</span>
          <span class="qd-sc" :style="{ color: scoreColor(qd.score) }">{{ qd.score }} 分</span>
        </div>
        <p class="qd-q">{{ qd.question }}</p>
        <div class="qd-ans">
          <span class="qd-ans-lbl">你的回答</span>
          <p class="qd-ans-v">{{ qd.userAnswer || '（未回答）' }}</p>
        </div>
        <p v-if="qd.feedback" class="qd-fb">{{ qd.feedback }}</p>

        <!-- Reference answer for this question if available -->
        <template v-if="report.referenceAnswers.length > 0">
          <div v-for="ra in report.referenceAnswers.filter(r => r.questionIndex === qd.questionIndex)" :key="ra.questionIndex">
            <div v-if="ra.referenceAnswer" class="qd-ref">参考答案：{{ ra.referenceAnswer }}</div>
            <div v-if="ra.keyPoints && ra.keyPoints.length > 0" class="qd-kp">
              <span class="qd-kp-tag" v-for="kp in ra.keyPoints" :key="kp">{{ kp }}</span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div style="display:flex;gap:10px;margin-top:24px">
      <button class="btn btn-accent" @click="back">返回列表</button>
      <button class="btn" @click="() => router.push('/interview')">新面试</button>
    </div>
  </div>
</template>

<style scoped>
.loading { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:14px; padding:80px 0; color:var(--text-mut); }
.spinner { width:26px; height:26px; border:2px solid var(--border); border-top-color:var(--accent); border-radius:50%; animation:spin .8s linear infinite; }
@keyframes spin { to { transform:rotate(360deg); } }

.btn { background:transparent; color:var(--text); border:1px solid var(--border); padding:8px 20px; border-radius:10px; cursor:pointer; font-size:13px; font-family:var(--font); transition:all .15s; white-space:nowrap; }
.btn:hover { border-color:var(--accent); color:var(--accent); }
.btn-accent { background:var(--accent); color:#000; border-color:var(--accent); font-weight:600; }
.btn-accent:hover { background:var(--accent-dim); border-color:var(--accent-dim); color:#fff; }

.rpt { max-width:min(100% - 40px, 900px); margin:0 auto; }

/* ── header ── */
.rpt-hdr { display:flex; align-items:center; gap:24px; margin-bottom:28px; }
.rpt-ring { position:relative; width:86px; height:86px; flex-shrink:0; }
.rpt-svg { width:100%; height:100%; transform:rotate(-90deg); }
.rpt-track { fill:none; stroke:rgba(255,255,255,0.05); stroke-width:6; }
.rpt-arc { fill:none; stroke-width:6; stroke-linecap:round; transition:stroke-dasharray .7s ease-out; }
.rpt-num { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; font-size:22px; font-weight:800; font-family:var(--font-display); }
.rpt-ttl { font-family:var(--font-display); font-size:22px; font-weight:800; margin-bottom:2px; }
.rpt-meta { font-size:13px; color:var(--text-mut); }

/* ── sections ── */
.sec { background:var(--bg-glass); border:1px solid var(--border); border-radius:var(--rad); padding:20px; margin-bottom:14px; }
.sec-ttl { font-size:14px; font-weight:700; margin-bottom:10px; display:flex; align-items:center; gap:8px; }
.sec-dot { width:6px; height:6px; border-radius:50%; flex-shrink:0; }
.sec-text { font-size:13px; line-height:1.8; color:var(--text-mut); }
.sec-dim { font-size:13px; color:var(--text-dim); }
.rpt-grid-2 { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
.sec-list { list-style:none; padding:0; }
.sec-list li { padding:3px 0 3px 16px; font-size:13px; line-height:1.6; color:var(--text-mut); position:relative; }
.sec-list li::before { content:'·'; position:absolute; left:2px; color:var(--text-dim); }

/* ── category scores ── */
.cr { display:flex; align-items:center; gap:10px; margin-bottom:9px; }
.cr:last-child { margin-bottom:0; }
.cr-lbl { width:100px; font-size:13px; flex-shrink:0; }
.cr-bar { flex:1; height:5px; background:rgba(255,255,255,0.04); border-radius:3px; overflow:hidden; }
.cr-fill { height:100%; border-radius:3px; transition:width .6s cubic-bezier(.22,1,.36,1); }
.cr-sc { width:28px; font-size:13px; font-weight:700; text-align:right; font-family:var(--font-display); }
.cr-cnt { font-size:11px; color:var(--text-dim); width:32px; text-align:right; }

/* ── question details ── */
.qd { border-bottom:1px solid var(--border); padding-bottom:16px; margin-bottom:16px; }
.qd:last-child { border:none; margin:0; padding:0; }
.qd-hdr { display:flex; align-items:center; gap:8px; margin-bottom:6px; }
.qd-idx { font-size:11px; font-weight:700; background:var(--accent-glow); color:var(--accent); padding:1px 8px; border-radius:6px; }
.qd-cat { font-size:12px; color:var(--text-mut); }
.qd-sc { font-size:13px; font-weight:700; margin-left:auto; font-family:var(--font-display); }
.qd-q { font-size:13px; line-height:1.7; color:var(--text); margin-bottom:8px; }
.qd-ans { background:var(--bg-input); border-radius:8px; padding:12px 14px; margin-bottom:6px; }
.qd-ans-lbl { font-size:11px; color:var(--text-dim); display:block; margin-bottom:2px; }
.qd-ans-v { font-size:13px; line-height:1.7; white-space:pre-wrap; }
.qd-fb { font-size:12px; color:var(--text-mut); line-height:1.6; background:var(--bg-glass); border-radius:8px; padding:10px 14px; margin-top:6px; }
.qd-ref { font-size:13px; color:var(--green); line-height:1.7; margin-top:8px; padding:8px 12px; background:rgba(52,211,153,0.04); border-radius:8px; border-left:2px solid var(--green); }
.qd-kp { display:flex; flex-wrap:wrap; gap:4px; margin-top:8px; }
.qd-kp-tag { font-size:11px; background:rgba(255,255,255,0.04); border:1px solid var(--border); padding:2px 10px; border-radius:6px; color:var(--text-mut); }
</style>
