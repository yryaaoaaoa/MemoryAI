import request from './request'

// ========== 类型定义 ==========

export interface InterviewQuestionDTO {
  questionIndex: number
  question: string
  type: string
  category: string
  userAnswer: string | null
  score: number | null
  feedback: string | null
  isFollowUp: boolean
  parentQuestionIndex: number | null
}

export interface InterviewSessionDTO {
  sessionId: string
  resumeText: string
  totalQuestions: number
  currentQuestionIndex: number
  questions: InterviewQuestionDTO[]
  status: string
}

export interface SubmitAnswerResponse {
  hasNext: boolean
  nextQuestion: InterviewQuestionDTO | null
  currentIndex: number
  totalQuestions: number
}

export interface CreateInterviewRequest {
  skillId: string
  difficulty: string
  questionCount: number
  mode?: string
  resumeId?: number | null
  resumeText?: string | null
  forceCreate?: boolean | null
  llmProvider?: string | null
}

export interface SkillCategoryDTO {
  key: string
  label: string
  priority: string
  ref: string | null
  shared: boolean
}

export interface SkillDTO {
  id: string
  name: string
  description: string
  categories: SkillCategoryDTO[]
  isPreset: boolean
  sourceJd: string | null
  persona: string | null
  display: {
    icon: string | null
    gradient: string | null
    iconBg: string | null
    iconColor: string | null
  } | null
}

export interface InterviewReportDTO {
  sessionId: string
  totalQuestions: number
  overallScore: number
  categoryScores: { category: string; score: number; questionCount: number }[]
  questionDetails: {
    questionIndex: number
    question: string
    category: string
    userAnswer: string | null
    score: number
    feedback: string
  }[]
  overallFeedback: string
  strengths: string[]
  improvements: string[]
  referenceAnswers: { questionIndex: number; question: string; referenceAnswer: string; keyPoints: string[] }[]
}

export interface SessionListItem {
  id: number
  sessionId: string
  skillId: string
  difficulty: string
  totalQuestions: number
  status: string
  overallScore: number | null
  createdAt: string
}

// ========== API 接口 ==========

export const interviewApi = {
  /** 列出所有面试会话 */
  listSessions() {
    return request.get<any, SessionListItem[]>('/api/interview/sessions')
  },

  /** 创建面试会话 */
  createSession(data: CreateInterviewRequest) {
    return request.post<any, InterviewSessionDTO>('/api/interview/sessions', data)
  },

  /** 获取会话详情 + 题目 */
  getSession(sessionId: string) {
    return request.get<any, InterviewSessionDTO>(`/api/interview/sessions/${sessionId}`)
  },

  /** 获取当前问题 */
  getCurrentQuestion(sessionId: string) {
    return request.get<any, { completed: boolean; question?: InterviewQuestionDTO; message?: string }>(`/api/interview/sessions/${sessionId}/question`)
  },

  /** 提交答案（进入下一题） */
  submitAnswer(sessionId: string, questionIndex: number, answer: string) {
    return request.post<any, SubmitAnswerResponse>(`/api/interview/sessions/${sessionId}/answers`, { questionIndex, answer })
  },

  /** 暂存答案 */
  saveAnswer(sessionId: string, questionIndex: number, answer: string) {
    return request.put<any, void>(`/api/interview/sessions/${sessionId}/answers`, { questionIndex, answer })
  },

  /** 提前交卷 */
  completeInterview(sessionId: string) {
    return request.post<any, void>(`/api/interview/sessions/${sessionId}/complete`)
  },

  /** 获取面试报告 */
  getReport(sessionId: string) {
    return request.get<any, InterviewReportDTO | { ready: boolean; message: string }>(`/api/interview/sessions/${sessionId}/report`)
  },

  /** 删除面试会话 */
  deleteSession(sessionId: string) {
    return request.delete<any, void>(`/api/interview/sessions/${sessionId}`)
  },

  /** 面试方向列表 */
  listSkills() {
    return request.get<any, SkillDTO[]>('/api/interview/skills')
  },

  /** 获取面试方向详情 */
  getSkill(id: string) {
    return request.get<any, SkillDTO>(`/api/interview/skills/${id}`)
  },
}
