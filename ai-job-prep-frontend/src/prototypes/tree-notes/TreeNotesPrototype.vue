<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Plus, MoreFilled, RefreshLeft, RefreshRight, Search, Document, Download, Close, Check, Moon, Sunny, Connection, EditPen } from '@element-plus/icons-vue'
import { renderMarkdown } from '@/utils/markdown'
import { createUuid } from '@/utils/uuid'
import { exampleNote, flattenNodes, locateNode, moveNode, parseTreeNote, toMarkdown, type TreeNote } from './tree-note'

const storageKey = 'memorai-tree-note-prototype-v1'
const note = ref<TreeNote>(exampleNote())
const saved = ref('')
const hasSaved = ref(false)
const dark = ref(false)
const query = ref('')
const view = ref<'tree' | 'reading'>('tree')
const selected = ref('queue')
const collapsed = ref(new Set(['core', 'unbounded', 'max', 'parameters', 'rejection', 'review']))
const editing = ref<{ id: string | null; field: 'title' | 'body'; original: string } | null>(null)
const editText = ref('')
const menu = ref<{ id: string; x: number; y: number } | null>(null)
const past = ref<string[]>([])
const future = ref<string[]>([])
const sourceDialog = ref<HTMLDialogElement>()
const toast = ref<{ text: string; undo: boolean } | null>(null)
let toastTimer: ReturnType<typeof setTimeout>

function notify(text: string, undo = false) {
  clearTimeout(toastTimer)
  toast.value = { text, undo }
  toastTimer = setTimeout(() => { toast.value = null }, 6000)
}

try {
  const stored = localStorage.getItem(storageKey)
  if (stored) { note.value = parseTreeNote(stored); hasSaved.value = true }
} catch { notify('无法读取本地草稿，已载入示例笔记。') }
saved.value = JSON.stringify(note.value)
if (!locateNode(note.value.nodes, selected.value)) selected.value = note.value.nodes[0]?.id ?? ''
const all = computed(() => flattenNodes(note.value.nodes))
const matches = computed(() => {
  const term = query.value.trim().toLocaleLowerCase()
  return term ? all.value.filter(({ node }) => `${node.title}\n${node.body}`.toLocaleLowerCase().includes(term)) : []
})
const matchIds = computed(() => new Set(matches.value.map(row => row.node.id)))
const visible = computed(() => {
  if (query.value.trim()) {
    const paths = new Set(matches.value.flatMap(row => [row.node.id, ...row.ancestors]))
    return all.value.filter(row => paths.has(row.node.id))
  }
  return all.value.filter(row => row.ancestors.every(id => !collapsed.value.has(id)))
})
const dirty = computed(() => JSON.stringify(note.value) !== saved.value || !!(editing.value && editText.value !== editing.value.original))
const markdown = computed(() => toMarkdown(note.value))
const menuLocation = computed(() => menu.value ? locateNode(note.value.nodes, menu.value.id) : undefined)
const statusLabels = { PENDING: '待弄懂', REVIEW: '待复习', UNDERSTOOD: '已理解' }

function remember() {
  past.value.push(JSON.stringify({ note: note.value, selected: selected.value, collapsed: [...collapsed.value] }))
  future.value = []
}

function finishEdit(cancel = false) {
  const active = editing.value
  if (!active) return
  const value = active.field === 'title' ? (editText.value.trim() || (active.id === null ? '未命名笔记' : '未命名知识点')) : editText.value
  if (!cancel && value !== active.original) {
    remember()
    if (active.id === null) note.value.title = value
    else {
      const found = locateNode(note.value.nodes, active.id)
      if (found) found.node[active.field] = value
    }
  }
  editing.value = null
}

async function startEdit(id: string | null, field: 'title' | 'body') {
  finishEdit()
  const found = id === null ? null : locateNode(note.value.nodes, id)
  if (id !== null && !found) return
  if (id !== null) { selected.value = id; collapsed.value.delete(id) }
  const original = found ? found.node[field] : note.value.title
  editing.value = { id, field, original }
  editText.value = original
  menu.value = null
  await nextTick()
  const input = document.getElementById('tree-active-editor') as HTMLInputElement | HTMLTextAreaElement | null
  input?.focus()
  if (field === 'title') input?.select()
}

function toggle(id: string) {
  finishEdit()
  selected.value = id
  if (collapsed.value.has(id)) collapsed.value.delete(id)
  else collapsed.value.add(id)
}

function toggleAll(close: boolean) {
  finishEdit()
  query.value = ''
  collapsed.value = close ? new Set(all.value.map(row => row.node.id)) : new Set()
}

async function navigate(id: string) {
  finishEdit()
  query.value = ''
  view.value = 'tree'
  selected.value = id
  const row = all.value.find(item => item.node.id === id)
  row?.ancestors.forEach(parent => collapsed.value.delete(parent))
  collapsed.value.delete(id)
  await nextTick()
  document.getElementById(`node-${id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function openMenu(id: string, event: MouseEvent) {
  finishEdit()
  selected.value = id
  if (menu.value?.id === id) { menu.value = null; return }
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  menu.value = { id, x: Math.max(12, Math.min(rect.right - 208, window.innerWidth - 220)), y: Math.max(12, Math.min(rect.bottom + 7, window.innerHeight - 332)) }
}

async function addNode(kind: 'root' | 'child' | 'sibling', id?: string) {
  finishEdit()
  const found = id ? locateNode(note.value.nodes, id) : undefined
  if (kind !== 'root' && !found) return
  remember()
  const node = { id: createUuid(), title: '新知识点', body: '', children: [] }
  if (kind === 'root') note.value.nodes.push(node)
  else if (kind === 'child') { found!.node.children.push(node); collapsed.value.delete(id!) }
  else found!.siblings.splice(found!.index + 1, 0, node)
  query.value = ''
  view.value = 'tree'
  menu.value = null
  await startEdit(node.id, 'title')
}

function restructure(action: 'up' | 'down' | 'indent' | 'outdent') {
  const id = menu.value?.id
  if (!id) return
  finishEdit()
  remember()
  if (!moveNode(note.value.nodes, id, action)) past.value.pop()
  const row = flattenNodes(note.value.nodes).find(item => item.node.id === id)
  row?.ancestors.forEach(parent => collapsed.value.delete(parent))
  menu.value = null
}

function removeNode() {
  const found = menuLocation.value
  if (!found) return
  finishEdit()
  remember()
  const children = flattenNodes(found.node.children).length
  found.siblings.splice(found.index, 1)
  selected.value = found.parent?.id ?? found.siblings[found.index]?.id ?? found.siblings[found.index - 1]?.id ?? ''
  menu.value = null
  notify(`已删除「${found.node.title}」${children ? `及 ${children} 个子知识点` : ''}`, true)
}

function restore(direction: 'undo' | 'redo') {
  finishEdit()
  const from = direction === 'undo' ? past.value : future.value
  const to = direction === 'undo' ? future.value : past.value
  const snapshot = from.pop()
  if (!snapshot) return
  to.push(JSON.stringify({ note: note.value, selected: selected.value, collapsed: [...collapsed.value] }))
  const state = JSON.parse(snapshot)
  note.value = state.note
  selected.value = state.selected
  collapsed.value = new Set(state.collapsed)
  query.value = ''
  menu.value = null
  toast.value = null
}

function save() {
  finishEdit()
  try {
    const serialized = JSON.stringify(note.value)
    localStorage.setItem(storageKey, serialized)
    saved.value = serialized
    hasSaved.value = true
    notify('草稿已保存到此浏览器')
  } catch { notify('无法保存草稿，请先导出 Markdown 保留内容。') }
}

function exportNote() {
  finishEdit()
  const url = URL.createObjectURL(new Blob([markdown.value], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${note.value.title.replace(/[\\/:*?"<>|]/g, '-')}.md`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function loadExample() {
  finishEdit()
  remember()
  note.value = exampleNote()
  collapsed.value = new Set(['core', 'unbounded', 'max', 'parameters', 'rejection', 'review'])
  selected.value = 'queue'
  query.value = ''
  view.value = 'tree'
  notify('已载入示例，可撤销这次操作', true)
}

function setStatus(event: Event) {
  finishEdit()
  remember()
  note.value.status = (event.target as HTMLSelectElement).value as TreeNote['status']
}

function switchView(value: 'tree' | 'reading') { finishEdit(); view.value = value; menu.value = null }

function editBody(event: MouseEvent | KeyboardEvent, id: string) {
  if ((event.target as Element).closest('a')) return
  if (event instanceof KeyboardEvent) event.preventDefault()
  void startEdit(id, 'body')
}

function nodeKeys(event: KeyboardEvent, id: string) {
  if (event.target !== event.currentTarget) return
  const index = visible.value.findIndex(row => row.node.id === id)
  let next: string | undefined
  if (event.key === 'ArrowDown') next = visible.value[index + 1]?.node.id
  else if (event.key === 'ArrowUp') next = visible.value[index - 1]?.node.id
  else if (event.key === 'ArrowRight') { collapsed.value.delete(id); next = locateNode(note.value.nodes, id)?.node.children[0]?.id }
  else if (event.key === 'ArrowLeft') {
    if (!collapsed.value.has(id)) collapsed.value.add(id)
    else next = locateNode(note.value.nodes, id)?.parent?.id
  } else if (event.key === 'Enter') { event.preventDefault(); void startEdit(id, 'title'); return }
  else return
  event.preventDefault()
  if (next) { selected.value = next; void nextTick(() => document.getElementById(`node-${next}`)?.focus()) }
}

function shortcuts(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save() }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !editing.value && !(event.target instanceof HTMLInputElement) && !(event.target instanceof HTMLTextAreaElement)) {
    event.preventDefault(); restore(event.shiftKey ? 'redo' : 'undo')
  }
  if (event.key === 'Escape') menu.value = null
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
function closeMenu() { menu.value = null }
onMounted(() => { window.addEventListener('keydown', shortcuts); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('click', closeMenu) })
onBeforeUnmount(() => { clearTimeout(toastTimer); window.removeEventListener('keydown', shortcuts); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('click', closeMenu) })
</script>

<template>
  <div class="tree-app" :class="{ 'is-dark': dark }">
    <aside class="notebook-sidebar">
      <div class="brand"><span class="brand-icon"><Connection /></span>MemorAI<span class="brand-period">.</span></div>
      <div class="sidebar-section-label">学习笔记</div>
      <div class="current-notebook"><Document /><span>线程池与并发</span><span class="notebook-count">01</span></div>
      <div class="outline-heading">本篇目录<span>{{ note.nodes.length }}</span></div>
      <nav class="note-outline" aria-label="本篇目录">
        <button v-for="(node, index) in note.nodes" :key="node.id" :class="{ active: selected === node.id || all.find(row => row.node.id === selected)?.ancestors.includes(node.id) }" @click="navigate(node.id)">
          <span class="outline-number">{{ String(index + 1).padStart(2, '0') }}</span><span>{{ node.title }}</span>
        </button>
      </nav>
      <button class="sidebar-add" @click="addNode('root')"><Plus />添加一级知识点</button>
      <div class="sidebar-bottom">
        <button @click="sourceDialog?.showModal()"><Document />查看示例问答<ArrowRight /></button>
        <button @click="loadExample"><RefreshLeft />载入示例</button>
        <div class="local-note"><span></span>草稿仅保存在此浏览器</div>
      </div>
    </aside>

    <div class="notebook-main">
      <header class="workspace-header">
        <div class="breadcrumbs"><span class="mobile-brand">MemorAI / </span><span>学习笔记</span><ArrowRight /><span>树状编辑</span><span class="prototype-tag">交互原型</span></div>
        <div class="header-actions">
          <span class="save-state" :class="{ unsaved: dirty }"><span v-if="dirty" class="unsaved-dot"></span><Check v-else-if="hasSaved" />{{ dirty ? '未保存' : hasSaved ? '已保存' : '示例草稿' }}</span>
          <button class="icon-button theme-button" :aria-label="dark ? '切换浅色主题' : '切换深色主题'" @click="dark = !dark"><Sunny v-if="dark" /><Moon v-else /></button>
          <button class="primary-button" @click="save"><Check />保存草稿</button>
        </div>
      </header>

      <main class="note-canvas">
        <div class="note-meta"><span class="topic-tag">JAVA · 并发编程</span><span class="meta-separator"></span><select aria-label="笔记学习状态" :value="note.status" @change="setStatus"><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select></div>
        <input v-if="editing?.id === null" id="tree-active-editor" v-model="editText" class="note-title-input" aria-label="笔记标题" @blur="finishEdit()" @keydown.enter.prevent="finishEdit()" @keydown.esc.stop="finishEdit(true)" />
        <h1 v-else><button @click="startEdit(null, 'title')">{{ note.title }}<EditPen /></button></h1>
        <div class="document-meta"><span>{{ all.length }} 个知识点</span><span>示例问答整理</span><button @click="exportNote"><Download />导出 Markdown</button></div>

        <section class="editor-toolbar" aria-label="笔记工具栏">
          <div class="view-switch"><button :class="{ active: view === 'tree' }" :aria-pressed="view === 'tree'" @click="switchView('tree')"><Connection />知识树</button><button :class="{ active: view === 'reading' }" :aria-pressed="view === 'reading'" @click="switchView('reading')"><Document />全文</button></div>
          <div class="toolbar-right">
            <div v-if="view === 'tree'" class="search-field"><Search /><input v-model="query" aria-label="搜索知识点" placeholder="搜索知识点" @focus="finishEdit()" /><button v-if="query" aria-label="清除搜索" @click="query = ''"><Close /></button></div>
            <div class="history-actions"><button class="icon-button" aria-label="撤销" title="撤销 Ctrl+Z" :disabled="!past.length && !editing" @click="restore('undo')"><RefreshLeft /></button><button class="icon-button" aria-label="重做" title="重做 Ctrl+Shift+Z" :disabled="!future.length" @click="restore('redo')"><RefreshRight /></button></div>
          </div>
        </section>

        <template v-if="view === 'tree'">
          <div class="tree-controls"><span v-if="query" role="status">找到 {{ matches.length }} 个知识点</span><span v-else class="edit-hint"><EditPen />点击标题或正文编辑</span><div><button @click="toggleAll(false)">全部展开</button><span>·</span><button @click="toggleAll(true)">全部折叠</button></div></div>
          <div v-if="query && !visible.length" class="empty-state"><Search /><h2>没有找到“{{ query }}”</h2><button @click="query = ''">清除搜索</button></div>
          <div v-else-if="!note.nodes.length" class="empty-state"><Connection /><h2>写下第一个知识点</h2><button class="primary-button" @click="addNode('root')"><Plus />添加知识点</button></div>
          <div v-else class="knowledge-tree" role="tree" aria-label="笔记知识树">
            <article v-for="row in visible" :id="`node-${row.node.id}`" :key="row.node.id" class="knowledge-node" :class="{ selected: selected === row.node.id, 'root-node': !row.ancestors.length, 'search-match': query && matchIds.has(row.node.id), 'is-open': query || !collapsed.has(row.node.id) }" :style="{ '--depth': row.ancestors.length }" role="treeitem" :aria-level="row.ancestors.length + 1" :aria-posinset="row.index + 1" :aria-setsize="row.size" :aria-expanded="!!query || !collapsed.has(row.node.id)" :aria-selected="selected === row.node.id" :aria-label="row.node.title" :tabindex="selected === row.node.id ? 0 : -1" @focus="selected = row.node.id" @keydown="nodeKeys($event, row.node.id)">
              <div class="node-heading">
                <button class="collapse-button" :aria-label="`${collapsed.has(row.node.id) ? '展开' : '折叠'} ${row.node.title}`" :disabled="!!query.trim()" @click="toggle(row.node.id)"><ArrowRight /></button>
                <span v-if="!row.ancestors.length" class="root-number">{{ String(row.index + 1).padStart(2, '0') }}</span>
                <input v-if="editing?.id === row.node.id && editing.field === 'title'" id="tree-active-editor" v-model="editText" class="node-title-input" aria-label="知识点标题" @blur="finishEdit()" @keydown.enter.prevent="finishEdit()" @keydown.esc.stop="finishEdit(true)" />
                <button v-else class="node-title" @click="startEdit(row.node.id, 'title')">{{ row.node.title }}</button>
                <span v-if="collapsed.has(row.node.id) && row.node.children.length && !query" class="child-count">{{ flattenNodes(row.node.children).length }}</span>
                <div class="node-actions">
                  <button class="icon-button" :aria-label="`为 ${row.node.title} 添加子知识点`" title="添加子知识点" @click="addNode('child', row.node.id)"><Plus /></button>
                  <button class="icon-button" :aria-label="`${row.node.title} 的更多操作`" title="更多操作" :aria-expanded="menu?.id === row.node.id" @click.stop="openMenu(row.node.id, $event)"><MoreFilled /></button>
                </div>
              </div>
              <div v-if="query || !collapsed.has(row.node.id)" class="node-content">
                <div v-if="editing?.id === row.node.id && editing.field === 'body'" class="body-editor">
                  <textarea id="tree-active-editor" v-model="editText" aria-label="知识点正文" :rows="Math.min(18, Math.max(5, editText.split('\n').length + 1))" placeholder="写下解释、例子或代码…" @blur="finishEdit()" @keydown.esc.stop="finishEdit(true)"></textarea>
                  <div class="body-editor-footer"><span>支持 Markdown</span><button @mousedown.prevent @click="finishEdit()"><Check />完成编辑</button></div>
                </div>
                <div v-else-if="row.node.body" class="node-body markdown-content" role="button" tabindex="0" :aria-label="`编辑 ${row.node.title} 的正文`" @click="editBody($event, row.node.id)" @keydown.enter="editBody($event, row.node.id)" v-html="renderMarkdown(row.node.body)"></div>
                <button v-else class="empty-body" @click="startEdit(row.node.id, 'body')"><EditPen />添加解释、例子或代码</button>
              </div>
            </article>
          </div>
          <button v-if="!query && note.nodes.length" class="add-root-button" @click="addNode('root')"><Plus />添加一级知识点</button>
        </template>
        <article v-else class="reading-view markdown-content" v-html="renderMarkdown(markdown)"></article>
        <footer class="canvas-footer"><Connection /><span>方向键浏览节点，Enter 编辑标题</span><span class="footer-shortcut"><kbd>Ctrl</kbd> + <kbd>S</kbd> 保存</span></footer>
      </main>
    </div>

    <div v-if="menu && menuLocation" class="node-menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }" role="group" aria-label="知识点操作" @click.stop>
      <button @click="addNode('child', menu.id)"><span aria-hidden="true">＋</span>添加子知识点</button>
      <button @click="addNode('sibling', menu.id)"><span aria-hidden="true">↳</span>添加同级知识点</button>
      <div class="menu-divider"></div>
      <button :disabled="menuLocation.index === 0" @click="restructure('up')"><span aria-hidden="true">↑</span>上移</button>
      <button :disabled="menuLocation.index === menuLocation.siblings.length - 1" @click="restructure('down')"><span aria-hidden="true">↓</span>下移</button>
      <button :disabled="menuLocation.index === 0" @click="restructure('indent')"><span aria-hidden="true">→</span>缩进为子知识点</button>
      <button :disabled="!menuLocation.parent" @click="restructure('outdent')"><span aria-hidden="true">←</span>提升一级</button>
      <div class="menu-divider"></div>
      <button class="delete-action" @click="removeNode"><span aria-hidden="true">×</span>删除{{ menuLocation.node.children.length ? '整个分支' : '知识点' }}</button>
    </div>

    <div v-if="toast" class="toast" role="status"><Check /><span>{{ toast.text }}</span><button v-if="toast.undo" @click="restore('undo')">撤销</button><button class="toast-close" aria-label="关闭提示" @click="toast = null"><Close /></button></div>

    <dialog ref="sourceDialog" class="source-dialog" @click="($event.target === sourceDialog) && sourceDialog?.close()">
      <div class="source-dialog-inner"><header><span>示例问答</span><button class="icon-button" aria-label="关闭示例问答" @click="sourceDialog?.close()"><Close /></button></header><h2>线程池总是先开到最大线程数，再排队吗？</h2><div class="source-speaker">AI 回答</div><p>不是。线程数达到核心线程数后，通常会先尝试把任务放进队列。只有入队失败，才尝试增加线程，直到达到最大线程数。</p><p>例如核心线程数为 2、最大线程数为 4、队列容量为 2，在任务均未完成的前提下，第 3、4 个任务进入队列，第 5、6 个任务可以由新建线程直接执行。因此，后提交的任务可能先开始执行。</p><button class="primary-button" @click="sourceDialog?.close()">返回笔记</button></div>
    </dialog>
  </div>
</template>
