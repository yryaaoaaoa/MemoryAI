import request from './request'

export interface SkillProfileCategory {
  category: string
  level: 'strong' | 'adequate' | 'weak' | 'untouched'
  text: string
}

export interface SkillProfileData {
  summary: string
  categories: SkillProfileCategory[]
  nextAdvice: string
}

export interface SkillProfile {
  skillId: string
  skillName: string
  profileJson: SkillProfileData | null
  interviewCount: number
  icon: string
  gradient: string
}

export interface UserProfile {
  techStack: string[]
  hasResume: boolean
  totalQuizzes: number
  correctRate: number
  streak: number
  wrongQuestionCount: number
  skillProfiles: SkillProfile[]
}

export interface UserVO {
  id: number
  username: string
}

export const authApi = {
  register(username: string, password: string) {
    return request.post<any, UserVO>('/api/auth/register', { username, password })
  },
  login(username: string, password: string) {
    return request.post<any, UserVO>('/api/auth/login', { username, password })
  },
  logout() {
    return request.post<any, void>('/api/auth/logout')
  },
  me() {
    return request.get<any, UserVO>('/api/auth/me')
  },
  getProfile() {
    return request.get<any, UserProfile>('/api/user/profile')
  },
}
