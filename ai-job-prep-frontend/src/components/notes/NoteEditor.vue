<script setup lang="ts">
import { computed, reactive, ref, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notesApi, noteStatusLabels, type LearningNote, type NoteFields, type SourceMessage } from '@/api/learning-notes.api'
import { registerNoteLeaveGuard } from '@/utils/note-leave-guard'
import { sourceTranscript, retainSources } from '@/utils/note-sources'
import { renderMarkdown } from '@/utils/markdown'
import { createUuid } from '@/utils/uuid'
import NoteBody from './NoteBody.vue'
import { flattenNodes, newKnowledgeNode, readTree, treeFields } from '@/utils/knowledge-tree'

const props = defineProps<{ sessionId: number; messages: SourceMessage[]; selectedIds: number[]; note?: LearningNote }>()
const emit = defineEmits<{ close: []; saved: [note: LearningNote] }>()
const selected = ref([...props.selectedIds])
const chosen = computed(() => props.messages.filter(m => selected.value.includes(m.id)))
const initialRaw = sourceTranscript(chosen.value)
const form = reactive<NoteFields>({
  title: props.note?.title ?? (chosen.value.find(m => m.role === 'user')?.content ?? '学习笔记').slice(0, 120),
  confusion: props.note?.confusion ?? '', content: props.note?.content ?? (initialRaw.length <= 40000 ? initialRaw : ''), status: props.note?.status ?? 'PENDING',
  treeJson: props.note?.treeJson ?? null,
})
const bodyEditor = ref<InstanceType<typeof NoteBody>>()
const initialForm = ref(JSON.stringify(form))
const editingVersion = ref(props.note?.version)
const savedSources = ref<SourceMessage[]>(props.note?.sources ?? [])
const capturedSources = ref<SourceMessage[]>(props.note?.sources ?? (form.content ? [...chosen.value] : []))
const initialCaptured = ref(JSON.stringify(capturedSources.value.map(message => message.id)))
const requestId = createUuid()
const drafting = ref<'BODY' | 'MERGE' | null>(null)
const saving = ref(false)
const error = ref(!props.note && initialRaw.length > 40000 ? '所选原文超过正文上限，请减少选择后插入，或填写自己的笔记。' : '')
const conflict = ref(false)
const busy = computed(() => drafting.value !== null || saving.value)
const valid = computed(() => !!form.title.trim() && !!form.content.trim() && form.content.length <= 40000 &&
  (!!props.note || capturedSources.value.length > 0 || selected.value.length > 0))
const dirty = computed(() => JSON.stringify(form) !== initialForm.value || JSON.stringify(capturedSources.value.map(message => message.id)) !== initialCaptured.value)
function sourcesForOperation() {
  const sources = retainSources(capturedSources.value, chosen.value)
  if (sources.reduce((length, message) => length + message.content.length, 0) > 120000) {
    error.value = '原始问答累计超过 120000 字，请减少本次选择或另建笔记。'
    return null
  }
  return sources
}
async function mayLeave() {
  if (busy.value) { ElMessage.warning('笔记正在保存或整理，请等待完成后再离开。'); return false }
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('离开后将丢失尚未保存的修改。', '笔记尚未保存', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' })
    return true
  } catch { return false }
}
const unregisterLeave = registerNoteLeaveGuard(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { unregisterLeave(); window.removeEventListener('beforeunload', beforeUnload) })
async function close() { if (await mayLeave()) emit('close') }
function appendSource() {
  bodyEditor.value?.finish()
  if (busy.value || !chosen.value.length) return
  const sources = sourcesForOperation()
  if (!sources) return
  const prefix = form.content ? form.content + '\n\n' : ''
  const content = prefix + sourceTranscript(chosen.value)
  if (content.length > 40000) {
    error.value = '复制后正文超过 40000 字，请减少所选问答或精简当前正文。'
    return
  }
  if (form.treeJson) {
    const tree = readTree(form.treeJson)
    const fields = treeFields([...tree.nodes, newKnowledgeNode('补充问答', sourceTranscript(chosen.value))])
    if (fields.content.length > 40000 || flattenNodes(tree.nodes).length >= 1000) { error.value = '添加后超过笔记容量，请减少所选问答。'; return }
    Object.assign(form, fields)
  } else form.content = content
  capturedSources.value = sources
  if (!conflict.value) error.value = ''
}
async function replaceWithSource() {
  bodyEditor.value?.finish()
  if (busy.value || !chosen.value.length) return
  const sources = sourcesForOperation()
  if (!sources) return
  const content = sourceTranscript(chosen.value)
  if (content.length > 40000) { error.value = '所选原文超过 40000 字，请减少所选问答。'; return }
  if (form.content && form.content !== content) {
    try { await ElMessageBox.confirm(form.treeJson ? '此操作会将整个知识树替换为一个原文节点，标题和困惑保持不变。' : '此操作会替换当前正文，标题和困惑保持不变。', '替换笔记正文', { confirmButtonText: '替换正文', cancelButtonText: '保留当前正文' }) }
    catch { return }
  }
  if (form.treeJson) Object.assign(form, treeFields([newKnowledgeNode('原始问答', content)]))
  else form.content = content
  capturedSources.value = sources
  if (!conflict.value) error.value = ''
}
async function loadLatest() {
  if (!props.note || busy.value) return
  try { await ElMessageBox.confirm('载入后会替换当前尚未保存的标题、困惑和正文，请先复制需要保留的内容。', '载入最新笔记', { confirmButtonText: '载入最新版本', cancelButtonText: '继续编辑' }) }
  catch { return }
  saving.value = true
  try {
    const latest = await notesApi.get(props.note.id)
    Object.assign(form, { title: latest.title, confusion: latest.confusion, content: latest.content, status: latest.status, treeJson: latest.treeJson ?? null })
    savedSources.value = latest.sources
    capturedSources.value = [...latest.sources]
    editingVersion.value = latest.version
    initialForm.value = JSON.stringify(form); initialCaptured.value = JSON.stringify(capturedSources.value.map(message => message.id))
    conflict.value = false; error.value = ''
  } catch { error.value = '无法读取最新笔记，当前编辑内容已保留，请重试。' }
  finally { saving.value = false }
}
async function draft(mode: 'BODY' | 'MERGE') {
  bodyEditor.value?.finish()
  if (busy.value || (mode === 'BODY' ? !form.content.trim() : !chosen.value.length)) return
  const sources = mode === 'MERGE' ? sourcesForOperation() : capturedSources.value
  if (!sources) return
  drafting.value = mode; error.value = ''
  try {
    const currentDraft = { title: form.title, confusion: form.confusion, content: form.content }
    const result = await notesApi.draft(mode === 'BODY' ? { mode, noteId: props.note?.id, currentDraft }
      : { mode, noteId: props.note?.id, currentDraft, sourceSessionId: props.sessionId, sourceMessageIds: [...selected.value] })
    if (form.treeJson) {
      const tree = readTree(form.treeJson)
      const fields = treeFields([...tree.nodes, newKnowledgeNode('AI 整理结果', result.content)])
      if (fields.content.length > 40000 || flattenNodes(tree.nodes).length >= 1000) { error.value = '整理结果与现有知识树合计超过 40000 字或 1000 个节点，原有节点已保留。请精简后重试。'; return }
      Object.assign(form, fields)
    } else Object.assign(form, result)
    capturedSources.value = sources
  } catch (failure: any) {
    error.value = failure?.response?.status === 400 ? failure.response.data.message : '整理失败，原有内容已保留，可以直接编辑并保存。'
  }
  finally { drafting.value = null }
}
async function save() {
  bodyEditor.value?.finish()
  if (busy.value || !valid.value) return
  const sources = sourcesForOperation()
  if (!sources) return
  saving.value = true; error.value = ''; conflict.value = false
  // Saving explicitly commits the selected sources to this draft, even if the request fails.
  capturedSources.value = sources
  const savedIds = new Set(savedSources.value.map(message => message.id))
  const sourceMessageIds = sources.filter(message => !savedIds.has(message.id)).map(message => message.id)
  const body = { ...form, title: form.title.trim() }
  try {
    const note = props.note ? await notesApi.update(props.note.id, { ...body, expectedVersion: editingVersion.value!,
      ...(sourceMessageIds.length ? { sourceSessionId: props.sessionId, sourceMessageIds } : {}) })
      : await notesApi.create({ ...body, sourceSessionId: props.sessionId, sourceMessageIds, requestId })
    ElMessage.success(props.note ? '学习笔记已更新' : '已保存到学习笔记')
    emit('saved', note)
  } catch (failure: any) {
    conflict.value = failure?.response?.status === 409
    error.value = conflict.value
      ? (props.note ? '笔记已有新版本，当前修改已保留。请先查看最新笔记，避免覆盖其他修改。' : '这次保存已提交，但内容与当前编辑不同。请先查看已保存笔记；当前编辑内容已保留。')
      : '保存失败，编辑内容已保留，请重试。'
  }
  finally { saving.value = false }
}
</script>

<template>
  <el-drawer class="note-editor-drawer" :model-value="true" :title="note ? '更新学习笔记' : '存为学习笔记'" size="min(820px, 100vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close">
    <el-form label-position="top" @submit.prevent="save">
      <details class="source-picker" open>
        <summary>本次使用的问答（{{ selected.length }}/40）</summary>
        <el-checkbox-group v-model="selected" :max="40" :disabled="busy" class="source-options">
          <el-checkbox v-for="message in messages" :key="message.id" :value="message.id">
            <span class="source-role">{{ message.role === 'user' ? '问题' : '回答' }}</span>
            {{ message.content.slice(0, 100) }}{{ message.content.length > 100 ? '…' : '' }}
          </el-checkbox>
        </el-checkbox-group>
      </details>
      <div class="note-tools">
        <el-button :loading="drafting === 'BODY'" :disabled="busy || !form.content.trim()" @click="draft('BODY')">整理正文</el-button>
        <el-button :loading="drafting === 'MERGE'" :disabled="busy || !selected.length" @click="draft('MERGE')">结合所选问答整理</el-button>
        <el-button :disabled="busy || !selected.length" @click="appendSource">复制所选原文到正文</el-button>
        <el-button :disabled="busy || !selected.length" @click="replaceWithSource">将所选原文填入正文</el-button>
      </div>
      <p class="note-operation-help">整理正文只使用当前文字；结合问答会同时使用本次勾选的原文。复制会插入一份文字，重复点击会再次插入。</p>
      <p v-if="form.treeJson" class="note-operation-help">AI 整理结果会添加为新知识点，保留已有分支，保存前可编辑或删除。</p>
      <p v-if="error" class="note-error" role="alert">{{ error }}</p>
      <a v-if="conflict" :href="note ? `/notes/${note.id}` : '/notes'" target="_blank" rel="noopener" class="note-latest">{{ note ? '在新标签页查看最新笔记' : '在新标签页查看已保存笔记' }}</a>
      <el-button v-if="conflict && note" :disabled="busy" @click="loadLatest">载入最新版本</el-button>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="120" :disabled="busy" />
      </el-form-item>
      <el-form-item label="我的困惑（选填）">
        <el-input v-model="form.confusion" type="textarea" :rows="2" maxlength="4000" :disabled="busy" placeholder="记录具体还没懂的地方" />
      </el-form-item>
      <NoteBody ref="bodyEditor" :content="form.content" :tree-json="form.treeJson" :title="form.title" :editing="true" :disabled="busy" @change="Object.assign(form, $event)" />
      <details v-if="capturedSources.length" class="retained-sources">
        <summary>保留的原始问答（{{ capturedSources.length }} 条）</summary>
        <p>原始问答独立于正文保存，取消勾选或修改正文不会删除它们。</p>
        <article v-for="source in capturedSources" :key="source.id">
          <strong>{{ source.role === 'user' ? '问题' : '回答' }}</strong>
          <div v-html="renderMarkdown(source.content)" />
        </article>
      </details>
      <el-form-item label="学习状态">
        <el-select v-model="form.status" :disabled="busy">
          <el-option v-for="(label, value) in noteStatusLabels" :key="value" :value="value" :label="label" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="busy || !valid" @mousedown.prevent @click="save">{{ note ? '保存更新' : '保存笔记' }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
:global(.note-editor-drawer) { --el-drawer-bg-color: var(--bg-panel); background: var(--bg-panel); }
.source-picker { margin-bottom: 20px; }
.source-picker summary { cursor: pointer; font-weight: 600; color: var(--text); }
.source-options { display: flex; flex-direction: column; max-height: 220px; overflow-y: auto; gap: 8px; margin-top: 12px; }
.source-options :deep(.el-checkbox) { height: auto; margin: 0; align-items: flex-start; }
.source-options :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; line-height: 1.6; }
.source-options :deep(.el-checkbox__input) { margin-top: 5px; }
.source-role { color: var(--text-mut); margin-right: 6px; }
.note-tools { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.note-tools .el-button + .el-button { margin-left: 0; }
.note-error { color: var(--red); line-height: 1.6; margin-bottom: 16px; }
.note-operation-help { line-height: 1.6; margin-bottom: 16px; color: var(--text); }
.retained-sources { margin-top: 16px; }
.retained-sources summary { cursor: pointer; font-weight: 600; }
.retained-sources p { line-height: 1.6; margin: 12px 0; }
.retained-sources article { border-top: 1px solid var(--border); padding: 16px 0; overflow-wrap: anywhere; }
.retained-sources :deep(pre) { overflow-x: auto; white-space: pre; }
.note-latest { display: inline-block; color: var(--accent); margin-bottom: 16px; }
</style>
