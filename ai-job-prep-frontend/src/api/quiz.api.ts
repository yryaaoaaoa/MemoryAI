import request from './request'

// ========== 错题本 ==========

export interface WrongQuestionVO {
  id: number
  questionText: string
  options: Record<string, string>
  correctAnswer: string
  explanation: string
  topic: string
  userAnswer: string
  score: number
  wrongTime: string
}

export interface WrongQuestionPage {
  records: WrongQuestionVO[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface WrongTopicVO {
  topic: string
  count: number
}

// ========== 提交答案 ==========

export interface SubmitResult {
  correct: boolean
  score: number
  correctAnswer: string
  explanation: string
}

// ========== 刷题记录 ==========

export interface QuizRecordVO {
  id: number
  questionId: number
  questionText: string
  options: Record<string, string>
  correctAnswer: string
  explanation: string
  topic: string
  userAnswer: string
  correct: boolean
  score: number
  duration: number
  createdAt: string
}

export interface QuizRecordPage {
  records: QuizRecordVO[]
  page: number
  size: number
  total: number
  totalPages: number
}

/** 批量生成的测验题目（匹配 QuizService.QuizQuestionDTO） */
export interface QuizQuestionDTO {
  id?: number
  question: string
  options: Record<string, string>
  answer: string
  explanation: string
}

export const quizApi = {
  /** 错题知识点分布 */
  listWrongTopics() {
    return request.get<any, WrongTopicVO[]>('/api/quiz/wrong-topics')
  },

  /** 错题列表（分页） */
  listWrongQuestions(params?: { topic?: string; page?: number; size?: number }) {
    return request.get<any, WrongQuestionPage>('/api/quiz/wrong-questions', { params })
  },

  /** 移出错题本 */
  removeWrongQuestion(questionId: number) {
    return request.post<any, void>(`/api/quiz/wrong-questions/${questionId}/remove`)
  },

  /** 从错题本抽题重做 */
  retryWrongQuestions(count = 5) {
    return request.post<any, WrongQuestionVO[]>('/api/quiz/retry-wrong', { count })
  },

  /** 提交答案 */
  submitAnswer(questionId: number, userAnswer: string, durationSec = 0) {
    return request.post<any, SubmitResult>('/api/quiz/submit', { questionId, userAnswer, durationSec })
  },

  /** 批量出题（按知识库 + 出题方向） */
  generateQuiz(kbIds: number[], count = 10, difficulty = 'mixed', topic?: string) {
    return request.post<any, QuizQuestionDTO[]>('/api/quiz/generate', { kbIds, count, difficulty, topic })
  },

  /** 批量查询题目答题状态 */
  batchStatus(questionIds: number[]) {
    return request.post<any, Record<string, SubmitResult>>('/api/quiz/batch-status', { questionIds })
  },

  /** 刷题记录（分页） */
  listRecords(params?: { topic?: string; correct?: boolean; dateFrom?: string; dateTo?: string; page?: number; size?: number }) {
    return request.get<any, QuizRecordPage>('/api/quiz/records', { params })
  },
}
