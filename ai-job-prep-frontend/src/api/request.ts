import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'
import { dismissSessionExpiryNotice, handleSessionExpiry } from '@/utils/session-expiry'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 60000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json;charset=utf-8' },
})

request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body.code !== 0 && body.code !== 200) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    if (response.config.url !== '/api/auth/register' && response.config.url !== '/api/knowledge-bases/public') {
      dismissSessionExpiryNotice()
    }
    return body.data as any
  },
  (error) => {
    if (error.response?.status === 401) {
      const url = error.config?.url
      if (url === '/api/auth/login') {
        ElMessage.error(error.response?.data?.message || '用户名或密码错误')
      } else if (url !== '/api/auth/me' && url !== '/api/auth/logout') {
        handleSessionExpiry()
      }
      return Promise.reject(error)
    }
    const msg = error.config?.url === '/api/auth/logout'
      ? '退出失败，请重试'
      : error.response?.data?.message || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default request
