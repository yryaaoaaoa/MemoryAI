import { test, expect, type Page, type Route } from '@playwright/test'

type RequestEntry = { path: string; method: string; body: any }
type Handler = (entry: RequestEntry, route: Route) => Promise<boolean>

async function respond(route: Route, data: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json',
    body: JSON.stringify({ code: status, message: status === 200 ? 'success' : '模拟保存失败', data }) })
}

async function mockApi(page: Page, handler: Handler) {
  const requests: RequestEntry[] = []
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!path.startsWith('/api/')) { await route.continue(); return }
    let body: any = null
    try { body = request.postDataJSON() } catch { /* GET requests have no JSON body. */ }
    const entry = { path, method: request.method(), body }
    requests.push(entry)
    if (await handler(entry, route)) return
    let data: unknown = []
    if (entry.path === '/api/auth/me') data = { id: 91, username: 'frontend-fixture', role: 'user' }
    if (entry.path === '/api/knowledge-bases') data = { records: [{ id: 7, name: '回归知识库' }], total: 1 }
    if (entry.path === '/api/interview/skills') data = []
    await respond(route, data)
  })
  await page.route('https://api.fontshare.com/**', route => route.abort())
  return requests
}

const questions = [101, 102].map(id => ({ id, question: `回归题${id}`, options: { A: '正确项', B: '错误项', C: '其他项', D: '未知项' }, answer: 'A', explanation: '示例解释' }))
const grade = { correct: true, score: 100, correctAnswer: 'A', explanation: '示例解释' }

test('quiz generation keeps the specific error without a second generic toast and can retry', async ({ page }) => {
  let attempts = 0
  await mockApi(page, async (entry, route) => {
    if (entry.path !== '/api/quiz/generate') return false
    attempts++
    if (attempts === 1) {
      await route.fulfill({ json: { code: 5004, message: '所选知识库暂无可用内容，请先上传并完成文档解析', data: null } })
    } else await respond(route, questions)
    return true
  })
  await page.goto('/quiz')
  await page.getByText('回归知识库', { exact: true }).click()
  await page.getByRole('button', { name: '中等', exact: true }).click()
  await page.getByRole('button', { name: /开始刷题/ }).click()
  await expect(page.locator('.generation-error')).toContainText('所选知识库暂无可用内容')
  await expect(page.locator('.el-message')).toHaveCount(1)
  await expect(page.getByText('出题失败，请稍后重试', { exact: true })).toHaveCount(0)
  await expect(page.locator('.kb-card')).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: /开始刷题/ }).click()
  await expect(page.locator('.q-text')).toHaveText('回归题101')
  await expect(page.locator('.quiz-topic')).toContainText('中等')
  await expect(page.locator('.generation-error')).toHaveCount(0)
})

test('blank quiz direction uses automatic generation and whitespace is omitted', async ({ page }) => {
  const requests = await mockApi(page, async (entry, route) => {
    if (entry.path !== '/api/quiz/generate') return false
    await respond(route, questions)
    return true
  })
  await page.goto('/quiz')
  await page.getByText('回归知识库', { exact: true }).click()
  await page.getByLabel('出题方向（选填）').fill('   ')
  await expect(page.locator('.btn-start-hint')).toHaveText('优先练习薄弱点，无匹配时综合出题')
  await page.getByRole('button', { name: /开始刷题/ }).click()
  await expect(page.locator('.q-text')).toHaveText('回归题101')
  expect(requests.find(r => r.path === '/api/quiz/generate')?.body).toEqual({ kbIds: [7], count: 10, difficulty: 'mixed' })
})

async function answerQuiz(page: Page, count = 2, answer = 'A') {
  await page.goto('/quiz')
  await page.getByText('回归知识库', { exact: true }).click()
  await page.getByRole('button', { name: /开始刷题/ }).click()
  for (let i = 0; i < count; i++) {
    await page.locator('.q-opt').filter({ has: page.locator('.q-opt-lbl', { hasText: answer }) }).click()
    if (i < count - 1) await page.getByRole('button', { name: '下一题 →' }).click()
  }
}

test('slow quiz submission locks double clicks and preserves one POST per question', async ({ page }) => {
  const submitted: number[] = []
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, questions); return true }
    if (entry.path === '/api/quiz/submit') {
      submitted.push(entry.body.questionId)
      if (submitted.length === 1) await gate
      await respond(route, grade); return true
    }
    return false
  })
  await answerQuiz(page)
  await page.getByRole('button', { name: '交卷', exact: true }).evaluate(button => { (button as HTMLButtonElement).click(); (button as HTMLButtonElement).click() })
  await expect.poll(() => submitted.length).toBe(1)
  await expect(page.getByRole('button', { name: '提交中...' })).toBeDisabled()
  await expect(page.locator('.q-opt').first()).toHaveAttribute('aria-disabled', 'true')
  release()
  await expect(page.locator('.res-wrap')).toBeVisible()
  expect(submitted).toEqual([101, 102])
})

test('partial explicit failure stays unsaved and retries only the failed question', async ({ page }) => {
  const submitted: number[] = []
  await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, questions); return true }
    if (entry.path === '/api/quiz/submit') {
      submitted.push(entry.body.questionId)
      const failed = entry.body.questionId === 102 && submitted.filter(id => id === 102).length === 1
      await respond(route, failed ? null : grade, failed ? 503 : 200); return true
    }
    return false
  })
  await answerQuiz(page)
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.submission-error')).toContainText('第 2 题：保存失败')
  await expect(page.locator('.res-wrap')).toHaveCount(0)
  await page.getByRole('button', { name: '重试未保存题目' }).click()
  await expect(page.locator('.res-wrap')).toBeVisible()
  expect(submitted).toEqual([101, 102, 102])
})

test('a question without a persisted ID never becomes a saved result', async ({ page }) => {
  const requests = await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, [{ ...questions[0], id: undefined }]); return true }
    return false
  })
  await answerQuiz(page, 1)
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.submission-error')).toContainText('题目缺少保存标识')
  await expect(page.locator('.res-wrap')).toHaveCount(0)
  expect(requests.filter(r => r.path === '/api/quiz/submit')).toHaveLength(0)
})

test('lost response for a fresh question is reconciled without a second POST', async ({ page }) => {
  let submits = 0
  const requests = await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, [questions[0]]); return true }
    if (entry.path === '/api/quiz/submit') { submits++; await route.abort('failed'); return true }
    if (entry.path === '/api/quiz/batch-status') { await respond(route, { '101': grade }); return true }
    return false
  })
  await answerQuiz(page, 1)
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.submission-error')).toContainText('提交结果未确认')
  await page.getByRole('button', { name: '重试未保存题目' }).click()
  await expect(page.locator('.res-wrap')).toBeVisible()
  expect(submits).toBe(1)
  expect(requests.some(r => r.path === '/api/quiz/batch-status')).toBe(true)
})

test('quiz records actual time and retrying a wrong answer creates a new graded attempt', async ({ page }) => {
  await page.clock.install()
  const submissions: { questionId: number; userAnswer: string; durationSec: number }[] = []
  await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, [questions[0]]); return true }
    if (entry.path === '/api/quiz/submit') {
      submissions.push(entry.body)
      await respond(route, { ...grade, correct: entry.body.userAnswer === 'A', score: entry.body.userAnswer === 'A' ? 100 : 0 })
      return true
    }
    return false
  })
  await answerQuiz(page, 1, 'B')
  await page.clock.runFor(6000)
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.res-wrap')).toBeVisible()
  expect(submissions[0]).toMatchObject({ questionId: 101, userAnswer: 'B' })
  expect(submissions[0].durationSec).toBeGreaterThanOrEqual(6)
  await page.getByRole('button', { name: '重做错题' }).click()
  await page.locator('.q-opt').first().click()
  await page.clock.runFor(2000)
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.res-wrap')).toBeVisible()
  expect(submissions).toHaveLength(2)
  expect(submissions[1]).toMatchObject({ questionId: 101, userAnswer: 'A' })
  expect(submissions[1].durationSec).toBeGreaterThanOrEqual(2)
  expect(submissions[1].durationSec).toBeLessThan(submissions[0].durationSec)
  await expect(page.locator('.score-pct')).toHaveText('100%')
  await expect(page.getByRole('button', { name: '重做错题' })).toHaveCount(0)
})

test('record topics include correct-only topics and failed history loads can be retried', async ({ page }) => {
  let loads = 0
  const requests = await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/record-topics') { await respond(route, ['全部答对的知识点']); return true }
    if (entry.path === '/api/quiz/records') {
      loads++
      await respond(route, loads === 1 ? null : { records: [], total: 0, totalPages: 0, page: 1, size: 10 }, loads === 1 ? 503 : 200)
      return true
    }
    return false
  })
  await page.goto('/quiz')
  await page.getByRole('button', { name: '记录', exact: true }).click()
  await expect(page.locator('.hl-status[role="alert"]')).toContainText('加载答题记录失败')
  await expect(page.getByText('暂无记录', { exact: true })).toHaveCount(0)
  await expect(page.locator('#history-topic option')).toContainText(['全部', '全部答对的知识点'])
  await page.getByRole('button', { name: '重试加载' }).click()
  await expect(page.getByText('暂无记录', { exact: true })).toBeVisible()
  await page.getByLabel('知识点', { exact: true }).selectOption('全部答对的知识点')
  await expect.poll(() => loads).toBe(3)
  expect(requests.some(r => r.path === '/api/quiz/wrong-topics')).toBe(false)
})

test('changing record filters ignores a late response for the previous filter', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let initialRequested = false
  await mockApi(page, async (entry, route) => {
    if (entry.path !== '/api/quiz/records') return false
    const correct = new URL(route.request().url()).searchParams.get('correct')
    if (correct === null) { initialRequested = true; await gate }
    const row = { id: 1, questionId: 101, questionText: correct === 'true' ? '当前筛选正确题' : '旧筛选题目', options: questions[0].options,
      correctAnswer: 'A', explanation: '解释', topic: '并发', userAnswer: 'A', correct: true, score: 100, duration: 8, createdAt: '2026-11-12T10:00:00' }
    await respond(route, { records: [row], total: 1, totalPages: 1, page: 1, size: 10 })
    return true
  })
  await page.goto('/quiz')
  await page.getByRole('button', { name: '记录', exact: true }).click()
  await expect.poll(() => initialRequested).toBe(true)
  await page.getByRole('button', { name: '正确', exact: true }).click()
  await expect(page.locator('.hl-question')).toHaveText('当前筛选正确题')
  const lateResponse = page.waitForResponse(response => response.url().includes('/api/quiz/records') && !new URL(response.url()).searchParams.has('correct'))
  release()
  await lateResponse
  await expect(page.locator('.hl-question')).toHaveText('当前筛选正确题')
})

test('profile loading failure is visible and can recover without reloading the page', async ({ page }) => {
  let loads = 0
  await mockApi(page, async (entry, route) => {
    if (entry.path !== '/api/user/profile') return false
    loads++
    await respond(route, loads === 1 ? null : { techStack: ['Java'], hasResume: false, totalQuizzes: 3, correctRate: 67, streak: 2, wrongQuestionCount: 1, skillProfiles: [] }, loads === 1 ? 503 : 200)
    return true
  })
  await page.goto('/profile')
  await expect(page.locator('.pf-empty[role="alert"]')).toContainText('加载用户画像失败')
  await page.getByRole('button', { name: '重试加载' }).click()
  await expect(page.locator('.pf-stat').filter({ hasText: '错题数' })).toContainText('1')
  await expect(page.locator('.pf-stat').filter({ hasText: '正确率' })).toContainText('67%')
  await expect(page.locator('.pf-tags')).toContainText('Java')
})

test('lost retry response is not confused with a previous attempt or automatically resent', async ({ page }) => {
  let submits = 0
  const requests = await mockApi(page, async (entry, route) => {
    if (entry.path === '/api/quiz/generate') { await respond(route, [questions[0]]); return true }
    if (entry.path === '/api/quiz/submit') {
      submits++
      if (submits === 1) await respond(route, { ...grade, correct: false, score: 0 })
      else await route.abort('failed')
      return true
    }
    return false
  })
  await answerQuiz(page, 1, 'B')
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '重做错题' }).click()
  await page.locator('.q-opt').first().click()
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await expect(page.locator('.submission-error')).toContainText('提交结果未确认，请查看答题记录')
  await page.getByRole('button', { name: '重试未保存题目' }).click()
  await expect(page.getByRole('button', { name: '重试未保存题目' })).toBeEnabled()
  expect(submits).toBe(2)
  expect(requests.some(r => r.path === '/api/quiz/batch-status')).toBe(false)
  await expect(page.locator('.res-wrap')).toHaveCount(0)
})

function interviewFixture(status = 'IN_PROGRESS') {
  return { sessionId: 'mock-interview', resumeText: '', totalQuestions: 3, currentQuestionIndex: 0, status,
    questions: [{ questionIndex: 0, question: '解释事务边界', category: 'Java', type: 'JAVA', userAnswer: null as string | null,
      score: null, feedback: null, isFollowUp: false, parentQuestionIndex: null }] }
}

async function openInterview(page: Page, handler: Handler, status = 'IN_PROGRESS') {
  const fixture = interviewFixture(status)
  const requests = await mockApi(page, async (entry, route) => {
    if (await handler(entry, route)) return true
    if (entry.path === '/api/interview/sessions') {
      await respond(route, [{ id: 1, sessionId: fixture.sessionId, skillId: 'java-backend', status, difficulty: 'mid', totalQuestions: 3, overallScore: null }]); return true
    }
    if (entry.path === '/api/interview/sessions/mock-interview') { await respond(route, fixture); return true }
    return false
  })
  await page.goto('/interview')
  if (status === 'IN_PROGRESS') await page.getByRole('button', { name: '继续', exact: true }).click()
  return { fixture, requests }
}

test('answer response advances to the new question and updates dynamic progress without refetching', async ({ page }) => {
  const { requests } = await openInterview(page, async (entry, route) => {
    if (entry.path.endsWith('/answers') && entry.method === 'POST') {
      expect(entry.body).toEqual({ questionIndex: 0, answer: '事务保证原子性' })
      await respond(route, { hasNext: true, currentIndex: 1, totalQuestions: 4,
        nextQuestion: { ...interviewFixture().questions[0], questionIndex: 1, question: '解释事务回滚',
          isFollowUp: true, parentQuestionIndex: 0 } }); return true
    }
    return false
  })
  await page.locator('.ans-ta').fill('事务保证原子性')
  await page.getByRole('button', { name: '提交并继续', exact: true }).click()
  await expect(page.locator('.qb-t')).toHaveText('解释事务回滚')
  await expect(page.locator('.pv-lbl')).toHaveText('Q2')
  await expect(page.locator('.pv-fill')).toHaveAttribute('style', /width: 25%/)
  await expect(page.locator('.ans-ta')).toHaveValue('')
  expect(requests.filter(r => r.path === '/api/interview/sessions/mock-interview')).toHaveLength(1)
})

test('draft save calls PUT, keeps text on failure, and supports retry', async ({ page }) => {
  const saves: any[] = []
  await openInterview(page, async (entry, route) => {
    if (entry.path.endsWith('/answers') && entry.method === 'PUT') {
      saves.push(entry.body)
      await respond(route, null, saves.length === 1 ? 503 : 200); return true
    }
    return false
  })
  await page.locator('.ans-ta').fill('应保存的草稿')
  await page.getByRole('button', { name: '暂存', exact: true }).click()
  await expect(page.getByRole('button', { name: '暂存', exact: true })).toBeEnabled()
  await expect(page.locator('.ans-ta')).toHaveValue('应保存的草稿')
  await page.getByRole('button', { name: '暂存', exact: true }).click()
  await expect(page.getByText('答案已暂存', { exact: true })).toBeVisible()
  await expect(page.locator('.ans-ta')).toHaveValue('应保存的草稿')
  expect(saves).toEqual([{ questionIndex: 0, answer: '应保存的草稿' }, { questionIndex: 0, answer: '应保存的草稿' }])
})

test('early completion waits for draft persistence before calling complete', async ({ page }) => {
  const order: string[] = []
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await openInterview(page, async (entry, route) => {
    if (entry.path.endsWith('/answers') && entry.method === 'PUT') {
      order.push('save-request'); expect(entry.body.answer).toBe('交卷前最后输入')
      await gate; order.push('save-confirmed'); await respond(route, null); return true
    }
    if (entry.path.endsWith('/complete')) { order.push('complete'); await respond(route, null); return true }
    return false
  })
  await page.locator('.ans-ta').fill('交卷前最后输入')
  await page.getByRole('button', { name: '提前交卷', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提前交卷', exact: true }).click()
  await expect.poll(() => order).toEqual(['save-request'])
  await expect(page.locator('.ans-ta')).toBeDisabled()
  release()
  await expect(page.getByRole('heading', { name: '面试完成' })).toBeVisible()
  expect(order).toEqual(['save-request', 'save-confirmed', 'complete'])
})

test('failed early-completion save preserves input and never ends the interview', async ({ page }) => {
  const { requests } = await openInterview(page, async (entry, route) => {
    if (entry.path.endsWith('/answers') && entry.method === 'PUT') { await respond(route, null, 503); return true }
    return false
  })
  await page.locator('.ans-ta').fill('网络恢复后继续保存')
  await page.getByRole('button', { name: '提前交卷', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提前交卷', exact: true }).click()
  await expect(page.locator('.ans-ta')).toBeEnabled()
  await expect(page.locator('.ans-ta')).toHaveValue('网络恢复后继续保存')
  expect(requests.some(r => r.path.endsWith('/complete'))).toBe(false)
  await expect(page.getByRole('heading', { name: '面试完成' })).toHaveCount(0)
})

test('completed sessions open the report, poll until ready, and never reopen the editor', async ({ page }) => {
  let reports = 0
  const { requests } = await openInterview(page, async (entry, route) => {
    if (entry.path.endsWith('/report')) {
      reports++
      await respond(route, reports === 1 ? { ready: false } : { sessionId: 'mock-interview', totalQuestions: 3, overallScore: 80,
        categoryScores: [], questionDetails: [], strengths: [], improvements: [], referenceAnswers: [], overallFeedback: '报告已完成' }); return true
    }
    return false
  }, 'COMPLETED')
  await page.locator('.sc-body').click()
  await expect(page).toHaveURL(/\/interview\/report\/mock-interview$/)
  await expect(page.getByText('评估进行中，自动刷新中...')).toBeVisible()
  await expect(page.getByText('报告已完成')).toBeVisible({ timeout: 10000 })
  await expect(page.locator('.ans-ta')).toHaveCount(0)
  expect(requests.some(r => r.path === '/api/interview/sessions/mock-interview')).toBe(false)
})
