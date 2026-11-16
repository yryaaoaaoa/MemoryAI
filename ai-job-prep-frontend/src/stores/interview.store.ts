import { ref } from 'vue'
import { defineStore } from 'pinia'
import { interviewApi } from '@/api/interview.api'
import type { InterviewSessionDTO, InterviewReportDTO } from '@/api/interview.api'

export const useInterviewStore = defineStore('interview', () => {
  const session = ref<InterviewSessionDTO | null>(null)
  const report = ref<InterviewReportDTO | null>(null)
  const loading = ref(false)
  let sessionRequest = 0

  async function create(req: Parameters<typeof interviewApi.createSession>[0]) {
    const request = ++sessionRequest
    loading.value = true
    try {
      const created = await interviewApi.createSession(req)
      if (request !== sessionRequest) return null
      session.value = created
      return session.value
    } finally {
      if (request === sessionRequest) loading.value = false
    }
  }

  async function load(sessionId: string) {
    const request = ++sessionRequest
    loading.value = true
    try {
      const loaded = await interviewApi.getSession(sessionId)
      if (request !== sessionRequest) return null
      session.value = loaded
      return session.value
    } finally {
      if (request === sessionRequest) loading.value = false
    }
  }

  async function loadReport(sessionId: string) {
    const res = await interviewApi.getReport(sessionId)
    if ('ready' in res && res.ready === false) return null
    report.value = res as InterviewReportDTO
    return report.value
  }

  function clear() {
    sessionRequest++
    loading.value = false
    session.value = null
    report.value = null
  }

  return { session, report, loading, create, load, loadReport, clear }
})
