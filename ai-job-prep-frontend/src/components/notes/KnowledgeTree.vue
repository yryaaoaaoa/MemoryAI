<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { ArrowRight, Plus, MoreFilled, Search, RefreshLeft, RefreshRight, Document, Connection, Download } from '@element-plus/icons-vue'
import { flattenNodes, locateNode, moveNode, newKnowledgeNode, treeFields, type KnowledgeNode } from '@/utils/knowledge-tree'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ modelValue: KnowledgeNode[]; disabled?: boolean; title?: string }>()
const emit = defineEmits<{ 'update:modelValue': [nodes: KnowledgeNode[]]; edit: [] }>()
const uid = useId()
const nodes = ref<KnowledgeNode[]>(JSON.parse(JSON.stringify(props.modelValue)))
const collapsed = ref(new Set<string>())
const selected = ref(nodes.value[0]?.id ?? '')
const query = ref('')
const view = ref('tree')
const active = ref<{ id: string; field: 'title' | 'body'; original: string } | null>(null)
const past = ref<string[]>([])
const future = ref<string[]>([])
const menuId = ref<string | null>(null)
const notice = ref('')
const all = computed(() => flattenNodes(nodes.value))
const matches = computed(() => all.value.filter(({ node }) => `${node.title}\n${node.body}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const visible = computed(() => {
  if (query.value.trim()) {
    const ids = new Set(matches.value.flatMap(row => [row.node.id, ...row.ancestors]))
    return all.value.filter(row => ids.has(row.node.id))
  }
  return all.value.filter(row => row.ancestors.every(id => !collapsed.value.has(id)))
})
const menu = computed(() => menuId.value ? locateNode(nodes.value, menuId.value) : undefined)
const content = computed(() => treeFields(nodes.value).content)
watch(() => props.modelValue, value => {
  if (JSON.stringify(value) === JSON.stringify(nodes.value)) return
  nodes.value = JSON.parse(JSON.stringify(value))
  past.value = []; future.value = []; active.value = null; menuId.value = null
  selected.value = nodes.value[0]?.id ?? ''
})
function publish() { emit('update:modelValue', JSON.parse(JSON.stringify(nodes.value))) }
function remember() { past.value.push(JSON.stringify(nodes.value)); future.value = []; notice.value = ''; emit('edit') }
function finish(cancel = false) {
  if (!active.value) return
  const node = locateNode(nodes.value, active.value.id)?.node
  if (node) {
    if (cancel) node[active.value.field] = active.value.original
    else if (active.value.field === 'title') node.title = node.title.trim() || '未命名知识点'
    publish()
  }
  active.value = null
}
function commitTitle(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  finish()
}
async function edit(id: string, field: 'title' | 'body') {
  if (props.disabled) return
  finish()
  const node = locateNode(nodes.value, id)?.node
  if (!node) return
  query.value = ''
  all.value.find(row => row.node.id === id)?.ancestors.forEach(parent => collapsed.value.delete(parent))
  remember(); selected.value = id; collapsed.value.delete(id); menuId.value = null
  active.value = { id, field, original: node[field] }
  await nextTick()
  const input = document.getElementById(`${uid}-editor`) as HTMLInputElement | HTMLTextAreaElement | null
  input?.focus()
  input?.scrollIntoView({ block: 'center' })
  if (field === 'title') input?.select()
}
function toggle(id: string) { finish(); selected.value = id; collapsed.value.has(id) ? collapsed.value.delete(id) : collapsed.value.add(id) }
function toggleAll(close: boolean) { finish(); query.value = ''; collapsed.value = close ? new Set(all.value.map(row => row.node.id)) : new Set() }
async function add(kind: 'root' | 'child' | 'sibling', id?: string) {
  if (props.disabled) return
  finish()
  if (all.value.length >= 1000) { notice.value = '一篇笔记最多 1000 个知识点。'; return }
  const found = id ? locateNode(nodes.value, id) : undefined
  if (kind !== 'root' && !found) return
  const row = all.value.find(row => row.node.id === id)
  if (kind === 'child' && row && row.ancestors.length >= 31) { notice.value = '知识点最多嵌套 32 层。'; return }
  remember()
  const node = newKnowledgeNode('新知识点')
  if (kind === 'root') nodes.value.push(node)
  else if (kind === 'child') { found!.node.children.push(node); collapsed.value.delete(id!) }
  else found!.siblings.splice(found!.index + 1, 0, node)
  query.value = ''; menuId.value = null; selected.value = node.id; publish()
  await edit(node.id, 'title')
}
function move(action: 'up' | 'down' | 'indent' | 'outdent') {
  if (!menuId.value || props.disabled) return
  finish(); remember()
  const id = menuId.value
  if (!moveNode(nodes.value, id, action) || flattenNodes(nodes.value).some(row => row.ancestors.length >= 32)) {
    nodes.value = JSON.parse(past.value.pop()!); notice.value = '无法移动到该层级。'
  }
  flattenNodes(nodes.value).find(row => row.node.id === id)?.ancestors.forEach(id => collapsed.value.delete(id))
  menuId.value = null; publish()
}
function remove() {
  if (!menu.value || props.disabled) return
  finish(); remember()
  const { node, siblings, index, parent } = menu.value
  siblings.splice(index, 1); selected.value = parent?.id ?? siblings[0]?.id ?? ''
  notice.value = `已删除「${node.title}」${node.children.length ? '及其子知识点' : ''}，可撤销恢复。`
  menuId.value = null; publish()
}
function undo(redo = false) {
  if (props.disabled) return
  finish()
  const from = redo ? future.value : past.value; const to = redo ? past.value : future.value
  const snapshot = from.pop()
  if (!snapshot) return
  to.push(JSON.stringify(nodes.value)); nodes.value = JSON.parse(snapshot)
  query.value = ''; menuId.value = null; notice.value = ''; emit('edit'); publish()
}
function key(event: KeyboardEvent, id: string) {
  if (event.target !== event.currentTarget) return
  const index = visible.value.findIndex(row => row.node.id === id)
  let next: string | undefined
  if (event.key === 'ArrowDown') next = visible.value[index + 1]?.node.id
  else if (event.key === 'ArrowUp') next = visible.value[index - 1]?.node.id
  else if (event.key === 'ArrowRight') { collapsed.value.delete(id); next = locateNode(nodes.value, id)?.node.children[0]?.id }
  else if (event.key === 'ArrowLeft') { if (!collapsed.value.has(id)) collapsed.value.add(id); else next = locateNode(nodes.value, id)?.parent?.id }
  else if (event.key === 'Enter') { event.preventDefault(); void edit(id, 'title'); return }
  else return
  event.preventDefault()
  if (next) { selected.value = next; void nextTick(() => document.getElementById(`${uid}-${next}`)?.focus()) }
}
function exportNote() {
  finish()
  const url = URL.createObjectURL(new Blob([`# ${props.title || '学习笔记'}\n\n${content.value}`], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `${(props.title || '学习笔记').replace(/[\\/:*?"<>|]/g, '-')}.md`; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
defineExpose({ finish })
</script>

<template>
  <section class="knowledge-editor" @keydown.esc.stop="menuId = null" @click="menuId = null">
    <div class="tree-toolbar">
      <div class="tree-tabs"><button type="button" :aria-pressed="view === 'tree'" @click="finish(); view = 'tree'"><Connection />知识树</button><button type="button" :aria-pressed="view === 'reading'" @click="finish(); view = 'reading'"><Document />全文</button></div>
      <div class="tree-toolbar-tools"><label v-if="view === 'tree'" class="tree-search"><Search /><input v-model="query" aria-label="搜索知识点" placeholder="搜索知识点" @focus="finish()" /></label><button type="button" aria-label="撤销" :disabled="disabled || !past.length" @click="undo()"><RefreshLeft /></button><button type="button" aria-label="重做" :disabled="disabled || !future.length" @click="undo(true)"><RefreshRight /></button><button type="button" aria-label="导出 Markdown" title="导出 Markdown" @click="exportNote"><Download /></button></div>
    </div>
    <p v-if="notice" class="tree-notice" role="status">{{ notice }}</p>
    <template v-if="view === 'tree'">
      <div class="tree-meta"><span>{{ query.trim() ? `找到 ${matches.length} 个知识点` : `${all.length} 个知识点` }}</span><div><button type="button" @click="toggleAll(false)">全部展开</button><button type="button" @click="toggleAll(true)">全部折叠</button></div></div>
      <div v-if="query.trim() && !visible.length" class="tree-empty">没有符合条件的知识点 <button type="button" @click="query = ''">清除搜索</button></div>
      <div role="tree" aria-label="笔记知识树">
        <article v-for="row in visible" :id="`${uid}-${row.node.id}`" :key="row.node.id" class="tree-node" :class="{ selected: selected === row.node.id, root: !row.ancestors.length }" :style="{ '--depth': row.ancestors.length }" role="treeitem" :aria-label="row.node.title" :aria-level="row.ancestors.length + 1" :aria-expanded="!!query.trim() || !collapsed.has(row.node.id)" :aria-selected="selected === row.node.id" :aria-posinset="row.index + 1" :aria-setsize="row.size" :tabindex="selected === row.node.id ? 0 : -1" @focus="selected = row.node.id" @keydown="key($event, row.node.id)">
          <div class="tree-node-heading">
            <button type="button" class="tree-fold" :class="{ open: query.trim() || !collapsed.has(row.node.id) }" :aria-label="`${collapsed.has(row.node.id) ? '展开' : '折叠'} ${row.node.title}`" :disabled="!!query.trim()" @click="toggle(row.node.id)"><ArrowRight /></button>
            <span v-if="!row.ancestors.length" class="tree-number">{{ String(row.index + 1).padStart(2, '0') }}</span>
            <input v-if="active?.id === row.node.id && active.field === 'title'" :id="`${uid}-editor`" v-model="row.node.title" class="tree-title-input" aria-label="知识点标题" maxlength="120" :disabled="disabled" @input="publish" @blur="finish()" @keydown.enter="commitTitle" @keydown.esc.stop="!$event.isComposing && finish(true)" />
            <button v-else type="button" class="tree-title" :disabled="disabled" @click="edit(row.node.id, 'title')">{{ row.node.title }}</button>
            <span v-if="collapsed.has(row.node.id) && row.node.children.length" class="tree-count">{{ flattenNodes(row.node.children).length }}</span>
            <div class="tree-node-actions"><button type="button" :aria-label="`为 ${row.node.title} 添加子知识点`" :disabled="disabled" @click="add('child', row.node.id)"><Plus /></button><button type="button" :aria-label="`${row.node.title} 的更多操作`" :aria-expanded="menuId === row.node.id" :disabled="disabled" @click.stop="finish(); menuId = menuId === row.node.id ? null : row.node.id"><MoreFilled /></button></div>
          </div>
          <div v-if="menuId === row.node.id && menu" class="tree-node-menu" role="group" aria-label="知识点操作" @click.stop>
            <button type="button" @click="add('child', row.node.id)">添加子知识点</button><button type="button" @click="add('sibling', row.node.id)">添加同级知识点</button><button type="button" :disabled="menu.index === 0" @click="move('up')">上移</button><button type="button" :disabled="menu.index === menu.siblings.length - 1" @click="move('down')">下移</button><button type="button" :disabled="menu.index === 0" @click="move('indent')">缩进为子知识点</button><button type="button" :disabled="!menu.parent" @click="move('outdent')">提升一级</button><button type="button" class="tree-delete" @click="remove">删除{{ row.node.children.length ? '整个分支' : '知识点' }}</button>
          </div>
          <div v-if="query.trim() || !collapsed.has(row.node.id)" class="tree-node-content">
            <div v-if="active?.id === row.node.id && active.field === 'body'" class="tree-body-editor"><textarea :id="`${uid}-editor`" v-model="row.node.body" aria-label="知识点正文" :rows="Math.min(18, Math.max(5, row.node.body.split('\n').length + 1))" maxlength="40000" :disabled="disabled" @input="publish" @blur="finish()" @keydown.esc.stop="!$event.isComposing && finish(true)"></textarea><div><span>支持 Markdown</span><button type="button" @mousedown.prevent @click="finish()">完成编辑</button></div></div>
            <div v-else-if="row.node.body" class="tree-markdown" role="button" tabindex="0" :aria-label="`编辑 ${row.node.title} 的正文`" @click="!($event.target as Element).closest('a') && edit(row.node.id, 'body')" @keydown.enter.prevent="edit(row.node.id, 'body')" v-html="renderMarkdown(row.node.body)"></div>
            <button v-else type="button" class="tree-empty-body" :disabled="disabled" @click="edit(row.node.id, 'body')">添加解释、例子或代码</button>
          </div>
        </article>
      </div>
      <button v-if="!query.trim()" type="button" class="tree-add-root" :disabled="disabled" @click="add('root')"><Plus />添加一级知识点</button>
    </template>
    <div v-else class="tree-markdown tree-reading" v-html="renderMarkdown(content)" />
    <p v-if="content.length > 40000" class="tree-limit" role="alert">正文超过 40000 字，请精简后保存。</p>
  </section>
</template>

<style scoped>
.knowledge-editor { --tree-selected: var(--accent-glow); --tree-indent: 26px; min-width: 0; width: 100%; color: var(--text); }
.knowledge-editor * { box-sizing: border-box; }
.knowledge-editor button { cursor: pointer; font: inherit; background: none; border: 0; color: inherit; padding: 6px; border-radius: 4px; }
.knowledge-editor button:hover:not(:disabled) { color: var(--accent); background: var(--bg-hover); }
.knowledge-editor button:disabled { cursor: default; opacity: .45; }
.knowledge-editor button:focus-visible, .tree-node:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.knowledge-editor svg { width: 17px; height: 17px; flex-shrink: 0; }
.tree-toolbar, .tree-toolbar-tools, .tree-tabs, .tree-meta, .tree-meta>div { display: flex; gap: 8px; align-items: center; }
.tree-toolbar { justify-content: space-between; border-block: 1px solid var(--border); padding: 12px 0; flex-wrap: wrap; }
.tree-tabs button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 10px; font-size: 13px; }
.tree-tabs button[aria-pressed=true] { color: var(--accent); background: var(--tree-selected); font-weight: 600; }
.tree-toolbar-tools>button { display: grid; place-items: center; }
.tree-search { display: flex; align-items: center; gap: 7px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-mut); }
.tree-search input { width: 140px; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: 12px; }
.tree-meta { justify-content: space-between; font-size: 12px; color: var(--text-mut); padding: 14px 0; }
.tree-node { position: relative; margin-left: calc(min(var(--depth), 6) * var(--tree-indent)); border-radius: 5px; margin-bottom: 6px; }
.tree-node:not(.root)::before { content: ''; position: absolute; top: 0; bottom: -6px; left: -12px; border-left: 1px solid var(--border); }
.tree-node:not(.root)::after { content: ''; position: absolute; top: 24px; left: -12px; width: 8px; border-top: 1px solid var(--border); }
.tree-node.root { margin-top: 18px; }
.tree-node.selected { background: var(--tree-selected); box-shadow: inset 2px 0 var(--accent); }
.tree-node-heading { display: flex; align-items: flex-start; gap: 5px; padding: 8px; min-height: 46px; }
.tree-node-heading>.tree-fold { flex-shrink: 0; display: grid; place-items: center; margin-top: 3px; }
.tree-fold svg { width: 12px; height: 12px; }
.tree-fold.open svg { transform: rotate(90deg); }
.tree-number { color: var(--accent); font: 11px/2.5 Consolas, monospace; margin-right: 4px; }
.tree-node-heading>.tree-title { flex: 1; min-width: 0; text-align: left; line-height: 1.65; overflow-wrap: anywhere; padding: 3px 0; font-size: 14px; }
.root>.tree-node-heading>.tree-title { font-size: 16px; font-weight: 600; }
.tree-title-input { flex: 1; min-width: 0; background: var(--bg-panel); color: var(--text); border: 1px solid var(--accent); border-radius: 4px; padding: 5px; font: inherit; }
.tree-node-actions { display: flex; flex-shrink: 0; opacity: 0; }
.tree-node-actions>button { display: grid; place-items: center; }
.tree-node:hover>.tree-node-heading .tree-node-actions, .tree-node:focus-within>.tree-node-heading .tree-node-actions, .tree-node.selected>.tree-node-heading .tree-node-actions { opacity: 1; }
.tree-count { color: var(--text-mut); font-size: 11px; padding: 6px; }
.tree-node-content { padding: 0 16px 15px 35px; }
.tree-markdown { font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; min-width: 0; }
.tree-markdown[role=button] { cursor: text; }
.tree-markdown :deep(p:first-child) { margin-top: 0; }
.tree-markdown :deep(p:last-child) { margin-bottom: 0; }
.tree-markdown :deep(pre) { max-width: 100%; overflow-x: auto; padding: 14px; border-radius: 5px; background: var(--bg-hover); }
.tree-markdown :deep(code) { font-size: 12px; }
.tree-markdown :deep(img) { max-width: 100%; }
.tree-markdown :deep(table) { display: block; overflow-x: auto; border-collapse: collapse; }
.tree-markdown :deep(td), .tree-markdown :deep(th) { border: 1px solid var(--border); padding: 7px 12px; }
.tree-markdown :deep(a) { color: var(--accent); }
.tree-body-editor { border: 1px solid var(--accent); border-radius: 5px; overflow: hidden; background: var(--bg-panel); }
.tree-body-editor textarea { width: 100%; display: block; padding: 12px; border: 0; outline: 0; background: transparent; color: var(--text); resize: vertical; font: 14px/1.8 Consolas, 'Microsoft YaHei', monospace; }
.tree-body-editor>div { display: flex; justify-content: space-between; align-items: center; padding: 5px 10px; border-top: 1px solid var(--border); font-size: 12px; color: var(--text-mut); }
.tree-empty-body { font-size: 13px !important; color: var(--text-mut) !important; }
.tree-node-menu { margin: 0 12px 10px 35px; padding: 6px; border: 1px solid var(--border); background: var(--bg-panel); border-radius: 6px; display: flex; flex-wrap: wrap; gap: 5px; font-size: 12px; }
.tree-delete, .tree-limit { color: var(--red) !important; }
.tree-add-root { display: flex; align-items: center; gap: 7px; width: 100%; border-top: 1px dashed var(--border) !important; margin-top: 20px; padding: 16px 8px !important; color: var(--accent) !important; font-size: 13px !important; }
.tree-notice { font-size: 13px; padding: 10px; background: var(--bg-hover); }
.tree-empty { padding: 32px 8px; text-align: center; }
.tree-reading { padding-top: 24px; }
@media (max-width: 600px) { .knowledge-editor { --tree-indent: 14px; } .tree-toolbar-tools { width: 100%; } .tree-search { flex: 1; min-width: 0; } .tree-search input { width: 100%; } .tree-node-actions { opacity: 1; } .tree-node-content { padding-left: 27px; padding-right: 8px; } .tree-node-heading { padding-inline: 4px; } }
</style>
