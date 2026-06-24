import { ref } from 'vue'
import { defineStore } from 'pinia'
import { interviewApi } from '@/api/interview.api'
import type { InterviewSessionDTO, InterviewReportDTO } from '@/api/interview.api'

export const useInterviewStore = defineStore('interview', () => {
  const session = ref<InterviewSessionDTO | null>(null)
  const report = ref<InterviewReportDTO | null>(null)
  const loading = ref(false)

  async function create(req: Parameters<typeof interviewApi.createSession>[0]) {
    loading.value = true
    try {
      session.value = await interviewApi.createSession(req)
      return session.value
    } finally {
      loading.value = false
    }
  }

  async function load(sessionId: string) {
    loading.value = true
    try {
      session.value = await interviewApi.getSession(sessionId)
      return session.value
    } finally {
      loading.value = false
    }
  }

  async function loadReport(sessionId: string) {
    const res = await interviewApi.getReport(sessionId)
    if ('ready' in res && res.ready === false) return null
    report.value = res as InterviewReportDTO
    return report.value
  }

  function clear() {
    session.value = null
    report.value = null
  }

  return { session, report, loading, create, load, loadReport, clear }
})
