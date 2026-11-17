<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { notesApi, type LearningNote } from '@/api/learning-notes.api'

const route = useRoute(), router = useRouter()
const records = ref<LearningNote[]>([])
const total = ref(0), query = ref(''), page = ref(1)
const loading = ref(false), deleting = ref<number | null>(null), error = ref('')
let requestVersion = 0

async function load() {
  const version = ++requestVersion
  loading.value = true; error.value = ''
  try {
    if (route.params.id !== undefined) {
      const id = Number(route.params.id)
      if (!Number.isSafeInteger(id) || id <= 0) throw new Error('Invalid note ID')
      const sessionId = await notesApi.openTree(id)
      if (version === requestVersion) await router.replace({ path: '/ask', query: { sessionId } })
    } else {
      const result = await notesApi.list({ query: query.value.trim() || undefined, page: page.value, size: 20 })
      if (version !== requestVersion) return
      records.value = result.records; total.value = result.total
    }
  } catch {
    if (version === requestVersion) error.value = route.params.id !== undefined
      ? '无法打开这份学习记录，可能已被删除。' : '无法加载学习记录，请重试。'
  } finally { if (version === requestVersion) loading.value = false }
}
function search() { page.value = 1; void load() }
async function remove(note: LearningNote) {
  if (deleting.value !== null) return
  const version = requestVersion
  try {
    await ElMessageBox.confirm('其中保存的历史笔记和原问答快照会一并删除，现有聊天记录保留。', '删除这份学习记录？', {
      confirmButtonText: '删除学习记录', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  if (version !== requestVersion) return
  deleting.value = note.id; error.value = ''
  try {
    await notesApi.remove(note.id)
    if (version !== requestVersion) return
    if (records.value.length === 1 && page.value > 1) --page.value
    await load()
  } catch { if (version === requestVersion) error.value = '无法删除学习记录，请重试。' }
  finally { deleting.value = null }
}
watch(() => route.params.id, () => { void load() }, { immediate: true })
onBeforeUnmount(() => { ++requestVersion })
</script>

<template>
  <section class="notes-page" aria-label="学习笔记">
    <template v-if="route.params.id !== undefined">
      <router-link to="/notes" class="back-link">← 全部学习树</router-link>
      <p v-if="loading" role="status">正在打开学习树…</p>
      <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重试</el-button></div>
    </template>
    <template v-else>
      <header class="notes-heading">
        <div><h1>学习笔记</h1><span v-if="total">{{ total }} 份学习记录</span></div>
        <router-link :to="{ path: '/ask', query: { new: '1' } }" class="start-topic">开始新话题 ↗</router-link>
      </header>
      <form class="notes-search" @submit.prevent="search">
        <el-input v-model="query" aria-label="搜索学习内容" placeholder="搜索问题或历史笔记" maxlength="200" clearable @clear="search" />
        <el-button native-type="submit">搜索</el-button>
      </form>
      <p v-if="loading" role="status">正在加载学习记录…</p>
      <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重新加载</el-button></div>
      <div v-if="!loading && !error && !records.length" class="notes-empty">
        <template v-if="query"><h2>没有找到相关内容</h2><el-button @click="query = ''; search()">清除搜索</el-button></template>
        <template v-else><h2>从一个问题开始</h2><p>聊过的问题会自动整理成树，保存在这里。</p><router-link :to="{ path: '/ask', query: { new: '1' } }">开始提问 →</router-link></template>
      </div>
      <div v-if="!loading && records.length" class="notes-list">
        <div v-for="item in records" :key="item.id" class="note-row">
          <router-link :to="`/notes/${item.id}`" class="note-link">
            <span class="tree-mark" aria-hidden="true">⌘</span><h2>{{ item.title }}</h2>
            <time>{{ item.updatedAt?.slice(0, 10) }}</time><span aria-hidden="true">↗</span>
          </router-link>
          <button class="delete-note" :disabled="deleting !== null" :aria-label="`删除 ${item.title}`" @click="remove(item)">删除</button>
        </div>
      </div>
      <el-pagination v-if="total > 20" v-model:current-page="page" :disabled="loading" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
    </template>
  </section>
</template>

<style scoped>
.notes-page { max-width: 980px; margin: 0 auto; padding: 20px 0 40px; }
.notes-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 32px; }
.notes-heading>div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 16px; }
h1 { font-size: 26px; line-height: 1.5; margin: 0; }
.notes-heading span, time { color: var(--text-mut); font-size: 12px; }
a { color: var(--accent); text-decoration: none; }
.start-topic { font-size: 13px; flex-shrink: 0; }
.notes-search { display: flex; gap: 12px; margin-bottom: 28px; max-width: 540px; }
.notes-list { border-top: 1px solid var(--border); }
.note-row { display: flex; align-items: center; gap: 18px; border-bottom: 1px solid var(--border); }
.note-row:hover { background: var(--bg-hover); }
.note-link { flex: 1; min-width: 0; display: flex; align-items: center; gap: 18px; padding: 26px 8px; color: var(--text); }
.tree-mark { color: var(--accent); font-size: 20px; }
.note-link h2 { flex: 1; min-width: 0; font-size: 16px; line-height: 1.7; font-weight: 500; margin: 0; overflow-wrap: anywhere; }
.note-link time { flex-shrink: 0; }
.delete-note { color: var(--text-mut); background: transparent; border: 0; font: inherit; font-size: 12px; padding: 10px; cursor: pointer; }
.delete-note:hover { color: var(--red); }
.delete-note:disabled { opacity: .5; cursor: default; }
.notes-empty { text-align: center; padding: 70px 12px; }
.notes-empty h2 { font-size: 20px; font-weight: 500; margin-bottom: 18px; }
.notes-empty p { color: var(--text-mut); line-height: 1.8; margin-bottom: 24px; }
.back-link { display: inline-block; margin-bottom: 24px; }
[role=alert] { color: var(--red); margin-bottom: 20px; line-height: 1.8; }
.el-pagination { margin-top: 24px; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 600px) {
  .notes-heading { align-items: flex-start; } h1 { font-size: 22px; }
  .note-row { gap: 4px; }
  .note-link { gap: 10px; flex-wrap: wrap; padding: 20px 4px; }
  .note-link h2 { flex-basis: calc(100% - 40px); }
  .note-link time { margin-left: 30px; }
  .delete-note { min-height: 44px; }
}
</style>
