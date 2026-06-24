import re

with open('src/views/ask/AskAI.vue', 'r', encoding='utf-8') as f:
    c = f.read()

# Insert restoreQuizFromDb() call after showHot in onMounted
old = "      showHot.value = messages.value.length === 0\n    } catch { /* ok */ }\n  } else {\n    try { sessionId.value = await ragApi.createSession()"
new = "      showHot.value = messages.value.length === 0\n      restoreQuizFromDb()\n    } catch { /* ok */ }\n  } else {\n    try { sessionId.value = await ragApi.createSession()"
c = c.replace(old, new, 1)

# Add restoreQuizFromDb function before loadSessions
func = r"""
async function restoreQuizFromDb() {
  const ids = store.agentQuizzes.map((q: any) => q.id).filter(Boolean)
  if (!ids.length) return
  try {
    const status = await quizApi.batchStatus(ids)
    for (const [qidStr, res] of Object.entries(status)) {
      const idx = store.agentQuizzes.findIndex((q: any) => q.id === Number(qidStr))
      if (idx >= 0) {
        store.setQuizResult(idx, {
          score: res.score,
          comment: res.correct ? '回答正确' : '回答错误',
          missing: res.correct ? '' : `正确答案: ${res.correctAnswer}。${res.explanation}`,
        })
      }
    }
  } catch { /* ok */ }
}

async function loadSessions() {"""

old2 = "async function loadSessions() {"
c = c.replace(old2, func, 1)

with open('src/views/ask/AskAI.vue', 'w', encoding='utf-8') as f:
    f.write(c)
print('ok')
