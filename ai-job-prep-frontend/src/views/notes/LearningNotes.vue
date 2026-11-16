<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notesApi, noteStatusLabels, type LearningNote, type NoteFields } from '@/api/learning-notes.api'
import { renderMarkdown } from '@/utils/markdown'
import { confirmNoteLeave, registerNoteLeaveGuard } from '@/utils/note-leave-guard'
import NoteBody from '@/components/notes/NoteBody.vue'

const route = useRoute()
const router = useRouter()
const records = ref<LearningNote[]>([])
const total = ref(0)
const query = ref('')
const status = ref('')
const page = ref(1)
const note = ref<LearningNote | null>(null)
const form = reactive<NoteFields>({ title: '', confusion: '', content: '', status: 'PENDING' })
const bodyEditor = ref<InstanceType<typeof NoteBody>>()
const editing = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
let requestVersion = 0
const detail = computed(() => route.params.id !== undefined)
const dirty = computed(() => editing.value && note.value && ['title', 'confusion', 'content', 'status', 'treeJson'].some(key => (form[key as keyof NoteFields] ?? '') !== (note.value![key as keyof NoteFields] ?? '')))
const sources = computed(() => note.value?.sources ?? [])
function fillForm() {
  if (note.value) {
    Object.assign(form, { title: note.value.title, confusion: note.value.confusion ?? '', content: note.value.content, status: note.value.status, treeJson: note.value.treeJson ?? null })
  }
}
async function load() {
  const version = ++requestVersion
  loading.value = true; error.value = ''; conflict.value = false; note.value = null; editing.value = false; busy.value = false
  try {
    if (detail.value) {
      const result = await notesApi.get(Number(route.params.id))
      if (version !== requestVersion) return
      note.value = result; fillForm()
    } else {
      const result = await notesApi.list({ query: query.value.trim() || undefined, status: status.value || undefined, page: page.value, size: 20 })
      if (version !== requestVersion) return
      records.value = result.records; total.value = result.total
    }
  } catch { if (version === requestVersion) error.value = detail.value ? '无法加载笔记，可能已被删除。' : '无法加载学习笔记，请重试。' }
  finally { if (version === requestVersion) loading.value = false }
}
watch(() => route.params.id, () => { void load() }, { immediate: true })
function search() { page.value = 1; void load() }
async function save() {
  bodyEditor.value?.finish()
  if (busy.value || !note.value || !form.title.trim() || !form.content.trim()) return
  if (form.content.length > 40000) { error.value = '正文超过 40000 字，请精简后保存。'; return }
  const id = note.value.id
  const version = requestVersion
  busy.value = true; error.value = ''
  try {
    const updated = await notesApi.update(id, { ...form, title: form.title.trim(), expectedVersion: note.value.version })
    if (version !== requestVersion) return
    note.value = updated
    fillForm(); editing.value = false; conflict.value = false; ElMessage.success('学习笔记已保存')
  } catch (failure: any) {
    if (version === requestVersion) {
      conflict.value = failure?.response?.status === 409
      error.value = conflict.value ? '笔记已有新版本，当前修改已保留。请先查看最新笔记，避免覆盖其他修改。' : '保存失败，编辑内容已保留，请重试。'
    }
  }
  finally { if (version === requestVersion) busy.value = false }
}
async function continueChat() {
  if (!note.value || busy.value) return
  const id = note.value.id
  const version = requestVersion
  busy.value = true
  try {
    const sessionId = await notesApi.continueChat(id)
    if (version === requestVersion) await router.push({ path: '/ask', query: { sessionId } })
  } catch { /* interceptor reports errors */ }
  finally { if (version === requestVersion) busy.value = false }
}
async function remove() {
  if (!note.value || busy.value) return
  const id = note.value.id
  const version = requestVersion
  try { await ElMessageBox.confirm('删除后无法恢复，原始聊天记录不受影响。', '删除学习笔记', { confirmButtonText: '删除笔记', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  if (version !== requestVersion) return
  busy.value = true
  try {
    await notesApi.remove(id)
    if (version !== requestVersion) return
    editing.value = false; await router.push('/notes'); ElMessage.success('学习笔记已删除')
  } catch { /* interceptor reports errors */ }
  finally { if (version === requestVersion) busy.value = false }
}
async function mayLeave() {
  if (busy.value && editing.value) { ElMessage.warning('笔记正在保存，请等待完成后再离开。'); return false }
  if (dirty.value) {
    try { await ElMessageBox.confirm('离开后将丢失尚未保存的修改。', '笔记尚未保存', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' }) }
    catch { return false }
  }
  return true
}
const unregisterLeave = registerNoteLeaveGuard(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || (busy.value && editing.value)) { event.preventDefault(); event.returnValue = '' }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { unregisterLeave(); window.removeEventListener('beforeunload', beforeUnload) })
async function beforeNavigation() {
  if (!await confirmNoteLeave()) return false
  ++requestVersion
  return true
}
onBeforeRouteLeave(beforeNavigation)
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id ? true : beforeNavigation())
async function loadLatest() {
  if (!note.value || busy.value) return
  try { await ElMessageBox.confirm('载入后会替换当前尚未保存的修改，请先复制需要保留的内容。', '载入最新笔记', { confirmButtonText: '载入最新版本', cancelButtonText: '继续编辑' }) }
  catch { return }
  const version = requestVersion
  busy.value = true
  try {
    const latest = await notesApi.get(note.value.id)
    if (version !== requestVersion) return
    note.value = latest; fillForm(); conflict.value = false; error.value = ''
  } catch { if (version === requestVersion) error.value = '无法读取最新笔记，当前编辑内容已保留，请重试。' }
  finally { if (version === requestVersion) busy.value = false }
}
</script>

<template>
  <section class="notes-page">
    <template v-if="!detail">
      <div class="notes-heading"><h1>学习笔记</h1><span>{{ total }} 篇</span></div>
      <form class="notes-filters" @submit.prevent="search">
        <el-input v-model="query" aria-label="搜索学习笔记" placeholder="搜索标题、困惑或正文" clearable @clear="search" />
        <el-select v-model="status" aria-label="筛选学习状态" @change="search">
          <el-option label="全部状态" value="" />
          <el-option v-for="(label, value) in noteStatusLabels" :key="value" :label="label" :value="value" />
        </el-select>
        <el-button native-type="submit">搜索</el-button>
      </form>
      <p v-if="loading" role="status">正在加载笔记…</p>
      <div v-else-if="error" role="alert"><p>{{ error }}</p><el-button @click="load">重试</el-button></div>
      <div v-else-if="!records.length" class="notes-empty">
        <template v-if="query || status"><h2>没有符合条件的笔记</h2><el-button @click="query = ''; status = ''; search()">清除筛选</el-button></template>
        <template v-else><h2>留下聊天里值得再学的内容</h2><p>在回答下选择“存为学习笔记”，记录困惑和解释。</p><el-button type="primary" @click="router.push('/ask')">开始提问</el-button></template>
      </div>
      <div v-else class="notes-list">
        <router-link v-for="item in records" :key="item.id" :to="`/notes/${item.id}`" class="note-row">
          <span class="note-state" :data-status="item.status">{{ noteStatusLabels[item.status] }}</span>
          <div class="note-row-body"><h2>{{ item.title }}</h2><p v-if="item.confusion">{{ item.confusion }}</p></div>
          <time>{{ item.updatedAt?.slice(0, 10) }}</time>
        </router-link>
      </div>
      <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
    </template>
    <template v-else>
      <router-link to="/notes" class="back-link">← 学习笔记</router-link>
      <p v-if="loading" role="status">正在加载笔记…</p>
      <div v-if="error" class="note-error" role="alert"><p>{{ error }}</p><el-button v-if="!note" @click="load">重试</el-button></div>
      <div v-if="conflict && note" class="note-conflict">
        <a :href="`/notes/${note.id}`" target="_blank" rel="noopener">在新标签页查看最新笔记</a>
        <el-button :disabled="busy" @click="loadLatest">载入最新版本</el-button>
      </div>
      <template v-if="note && !loading">
        <div class="note-detail-top">
          <span class="note-state" :data-status="note.status">{{ noteStatusLabels[note.status] }}</span>
          <div v-if="!editing" class="note-actions">
            <el-button :disabled="busy" @click="editing = true">编辑笔记</el-button>
            <el-button type="primary" :loading="busy" @click="continueChat">继续问</el-button>
            <el-button :disabled="busy" @click="remove">删除笔记</el-button>
          </div>
        </div>
        <el-form v-if="editing" label-position="top" @submit.prevent="save">
          <el-form-item label="标题" required><el-input v-model="form.title" maxlength="120" :disabled="busy" /></el-form-item>
          <el-form-item label="我的困惑（选填）"><el-input v-model="form.confusion" type="textarea" :rows="2" maxlength="4000" :disabled="busy" /></el-form-item>
          <el-form-item label="学习状态"><el-select v-model="form.status" :disabled="busy"><el-option v-for="(label, value) in noteStatusLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        </el-form>
        <article v-else>
          <h1>{{ note.title }}</h1>
          <div v-if="note.confusion" class="note-confusion"><h2>我的困惑</h2><p>{{ note.confusion }}</p></div>
        </article>
        <NoteBody :key="note.id" ref="bodyEditor" :content="form.content" :tree-json="form.treeJson" :title="form.title" :editing="editing" :disabled="busy" @edit="editing = true" @change="Object.assign(form, $event); editing = true" />
        <div v-if="editing" class="note-save-bar">
          <span>{{ dirty ? '有未保存的修改' : '编辑笔记' }}</span>
          <el-button :disabled="busy" @click="fillForm(); editing = false; error = ''; conflict = false">取消编辑</el-button>
          <el-button type="primary" :loading="busy" :disabled="busy || !form.title.trim() || !form.content.trim() || form.content.length > 40000" @mousedown.prevent @click="save">保存修改</el-button>
        </div>
        <details class="note-sources">
          <summary>保存时的原始问答（{{ sources.length }} 条）</summary>
          <div v-for="(source, index) in sources" :key="index" class="note-source">
            <div class="source-heading"><strong>{{ source.role === 'user' ? '问题' : '回答' }}</strong><router-link :to="{ path: '/ask', query: { sessionId: source.sessionId || note.sourceSessionId, messageId: source.id } }">查看来源会话</router-link></div>
            <div class="note-markdown" v-html="renderMarkdown(source.content)" />
          </div>
        </details>
      </template>
    </template>
  </section>
</template>

<style scoped>
.notes-page { max-width: 980px; margin: 0 auto; padding-bottom: 40px; }
.notes-heading, .note-detail-top, .source-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h1 { font-size: 26px; line-height: 1.4; margin: 20px 0; overflow-wrap: anywhere; }
.notes-heading span, time { color: var(--text-mut); font-size: 13px; }
.notes-filters { display: flex; gap: 12px; margin: 16px 0 28px; }
.notes-filters .el-select { width: 150px; flex-shrink: 0; }
.notes-list { border-top: 1px solid var(--border); }
.note-row { display: flex; gap: 20px; align-items: flex-start; padding: 24px 4px; border-bottom: 1px solid var(--border); color: var(--text); text-decoration: none; }
.note-row:hover { background: var(--bg-hover); }
.note-row-body { flex: 1; min-width: 0; }
.note-row h2 { font-size: 17px; margin: 0 0 8px; overflow-wrap: anywhere; }
.note-row p { margin: 0; color: var(--text-mut); overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow-wrap: anywhere; }
.note-state { font-size: 12px; padding: 4px 8px; border-radius: 6px; background: var(--bg-hover); color: var(--text); flex-shrink: 0; }
.note-state[data-status='PENDING'] { color: var(--accent); background: var(--accent-glow); }
.note-state[data-status='UNDERSTOOD'] { color: var(--green); }
.notes-empty { text-align: center; padding: 64px 12px; }
.notes-empty h2 { font-size: 20px; }
.notes-empty p { color: var(--text-mut); margin: 16px 0 24px; }
.back-link, .source-heading a { color: var(--accent); text-decoration: none; }
.back-link { display: inline-block; margin-bottom: 24px; }
.note-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.note-actions .el-button + .el-button { margin-left: 0; }
.note-confusion { border-left: 3px solid var(--accent); padding: 2px 0 2px 20px; margin: 24px 0; }
.note-confusion h2 { font-size: 14px; color: var(--text-mut); }
.note-confusion p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.note-markdown { line-height: 1.85; overflow-wrap: anywhere; }
.note-markdown :deep(pre) { padding: 16px; border-radius: 10px; background: var(--bg-hover); overflow-x: auto; max-width: 100%; }
.note-markdown :deep(img) { max-width: 100%; }
.note-markdown :deep(table) { display: block; overflow-x: auto; }
.note-sources { border-top: 1px solid var(--border); margin-top: 36px; padding-top: 20px; }
.note-sources summary { cursor: pointer; color: var(--text-mut); }
.note-source { padding: 24px 0; border-bottom: 1px solid var(--border); }
.note-error { color: var(--red); }
.note-conflict { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
.note-conflict a { color: var(--accent); }
.note-save-bar { position: sticky; bottom: 0; z-index: 5; display: flex; align-items: center; gap: 8px; background: var(--bg-panel); padding: 14px 0; border-top: 1px solid var(--border); margin-top: 20px; }
.note-save-bar>span { flex: 1; color: var(--text-mut); font-size: 12px; }
.el-pagination { margin-top: 24px; }
@media (max-width: 600px) {
  .notes-filters { flex-wrap: wrap; }
  .notes-filters > .el-input { flex-basis: 100%; }
  .note-row { gap: 10px; flex-wrap: wrap; }
  .note-row time { width: 100%; text-align: right; }
  .note-detail-top { align-items: flex-start; flex-direction: column; }
  .source-heading { flex-wrap: wrap; }
  h1 { font-size: 22px; }
}
</style>
