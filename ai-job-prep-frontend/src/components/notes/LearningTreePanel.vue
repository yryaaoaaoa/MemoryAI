<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { LearningNote, LearningTreeState } from '@/api/learning-notes.api'
import { flattenNodes, readTree, type KnowledgeNode } from '@/utils/knowledge-tree'

const props = defineProps<{
  note: LearningNote | null
  status: LearningTreeState['status']
  updatedNodeId: string | null
  error: string | null
  automatic: boolean
  busy: boolean
  questionDisabled: boolean
  selectedNodeId: string | null
  suggestions: string[]
  suggestionsLoading: boolean
  suggestionsError: string
  suggestionDisabledReason: string
}>()
const emit = defineEmits<{
  close: []
  retry: []
  continue: [node: KnowledgeNode]
  select: [node: KnowledgeNode | null]
  suggest: [question: string, node: KnowledgeNode]
  retrySuggestions: []
}>()
const collapsed = ref<string[]>([])
const nodes = computed(() => props.note?.treeJson ? readTree(props.note.treeJson).nodes : [])
const entries = computed(() => flattenNodes(nodes.value))
const visible = computed(() => entries.value.filter(entry => !entry.ancestors.some(id => collapsed.value.includes(id))))
const selected = computed(() => entries.value.find(entry => entry.node.id === props.selectedNodeId)?.node)

watch(entries, all => {
  if (props.selectedNodeId && !all.some(entry => entry.node.id === props.selectedNodeId)) emit('select', null)
  collapsed.value = collapsed.value.filter(id => all.some(entry => entry.node.id === id))
}, { immediate: true })

function toggle(id: string) {
  collapsed.value = collapsed.value.includes(id) ? collapsed.value.filter(value => value !== id) : [...collapsed.value, id]
}
</script>

<template>
  <aside class="learning-tree-panel" aria-label="问题目录">
    <header class="tree-heading">
      <h2>问题目录</h2>
      <span v-if="entries.length" class="tree-count">{{ entries.length }} 个问题</span>
      <button class="tree-close" aria-label="收起问题目录" @click="emit('close')">×</button>
    </header>

    <div class="tree-reading">
      <ul v-if="entries.length" class="tree-list" aria-label="已聊过的问题">
        <li v-for="entry in visible" :key="entry.node.id" :style="{ '--node-depth': Math.min(entry.ancestors.length, 5) }">
          <div :class="['tree-row', { selected: selectedNodeId === entry.node.id }]">
            <button v-if="entry.node.children.length" class="tree-fold" :aria-label="`${collapsed.includes(entry.node.id) ? '展开' : '收起'}${entry.node.title}`"
              :aria-expanded="!collapsed.includes(entry.node.id)" @click="toggle(entry.node.id)">{{ collapsed.includes(entry.node.id) ? '›' : '⌄' }}</button>
            <span v-else class="tree-leaf" aria-hidden="true">·</span>
            <button class="tree-node" :aria-pressed="selectedNodeId === entry.node.id" @click="emit('select', entry.node)">
              <span>{{ entry.node.title }}</span>
              <span v-if="entry.node.id === updatedNodeId && status === 'updated'" class="tree-updated">已更新</span>
            </button>
          </div>
        </li>
      </ul>

      <section v-if="selected" class="node-suggestions" aria-label="接着问">
        <h3>接着问</h3>
        <p v-if="suggestionsLoading" class="suggestion-notice" role="status">正在找还没聊过的问题…</p>
        <div v-else-if="suggestionsError" class="suggestion-failure" role="status">
          <p>{{ suggestionsError }}</p>
          <button class="tree-action" @click="emit('retrySuggestions')">重新推荐</button>
        </div>
        <template v-else>
          <button v-for="question in suggestions" :key="question" class="suggested-question" :disabled="!!suggestionDisabledReason"
            @click="emit('suggest', question, selected)"><span>{{ question }}</span><span aria-hidden="true">↗</span></button>
          <p v-if="!suggestions.length" class="suggestion-notice">{{ suggestionDisabledReason || '暂时没有新的推荐，可以继续提问。' }}</p>
        </template>
        <p v-if="suggestionDisabledReason && suggestions.length" class="suggestion-notice">{{ suggestionDisabledReason }}</p>
        <button class="tree-action continue-node" :disabled="questionDisabled" @click="emit('continue', selected)">自己提问 <span aria-hidden="true">↗</span></button>
      </section>

      <p v-else-if="entries.length" class="tree-browse-hint">选择一个问题，回看当时的问答。</p>
      <div v-else class="tree-empty">
        <span class="tree-empty-mark" aria-hidden="true">⌘</span>
        <p>{{ note ? '这份历史笔记还没有问题目录，继续聊天后会逐步整理。' : status === 'pending' ? '正在整理第一轮讨论…' : automatic ? '聊过的问题会自动整理到这里。' : '开启“自动整理”后，新问答会归入目录。' }}</p>
      </div>
    </div>

    <footer class="tree-footer">
      <div class="tree-status" :class="{ failed: status === 'failed' }" aria-live="polite">
        <span v-if="status !== 'failed'" class="status-dot" :class="{ pending: status === 'pending' }" aria-hidden="true"></span>
        <span>{{ status === 'failed' ? error || '这轮还没整理完成，问答已保留。' : status === 'pending' ? '正在整理，可继续聊天' : status === 'unchanged' ? '这轮没有新增问题' : automatic ? '随对话自动整理' : '自动整理已关闭' }}</span>
        <button v-if="status === 'failed'" class="tree-action" :disabled="busy" @click="emit('retry')">{{ busy ? '重试中…' : '重试' }}</button>
      </div>
      <div class="tree-footer-actions">
        <router-link to="/notes">全部学习树</router-link>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.learning-tree-panel { width: 300px; min-width: 0; flex: 0 0 300px; display: flex; flex-direction: column; background: var(--bg-panel); border-left: 1px solid var(--border); padding: 26px 20px 20px; }
.tree-heading { display: flex; align-items: center; gap: 10px; margin-bottom: 22px; }
.tree-heading h2 { font: 600 14px var(--font); margin: 0; }
.tree-count { color: var(--text-mut); font-size: 11px; margin-left: auto; }
.tree-close { display: grid; place-items: center; width: 26px; height: 26px; border: 0; border-radius: 5px; background: transparent; color: var(--text-mut); font: 20px var(--font); cursor: pointer; }
.tree-close:hover { background: var(--bg-hover); color: var(--text); }
.tree-reading { flex: 1; min-height: 0; overflow-y: auto; overflow-x: hidden; }
.tree-list { list-style: none; margin: 0; padding: 0; }
.tree-list li { padding-left: calc(var(--node-depth) * 13px); }
.tree-row { display: flex; align-items: flex-start; border-radius: 7px; margin: 3px 0; }
.tree-row.selected { background: var(--accent-glow); }
.tree-row:hover { background: var(--bg-hover); }
.tree-row.selected:hover { background: var(--accent-glow); }
.tree-fold, .tree-leaf { flex: 0 0 20px; width: 20px; padding: 8px 0; text-align: center; border: 0; background: none; color: var(--text-mut); font: 14px/1.65 var(--font); }
.tree-fold { cursor: pointer; }
.tree-node { flex: 1; min-width: 0; display: flex; align-items: baseline; justify-content: space-between; gap: 5px; padding: 9px 7px 9px 0; border: 0; background: none; color: var(--text); font: 12px/1.7 var(--font); text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.tree-node[aria-pressed=true] { color: var(--accent); }
.tree-updated { flex: none; color: var(--accent); font-size: 10px; }
.node-suggestions { margin-top: 25px; padding-top: 20px; border-top: 1px solid var(--border); }
.node-suggestions h3 { font: 500 12px/1.7 var(--font); color: var(--text-mut); margin: 0 0 12px; }
.suggested-question { width: 100%; display: flex; justify-content: space-between; gap: 12px; margin: 7px 0; padding: 11px 12px; border: 1px solid var(--border); border-radius: 8px; background: transparent; color: var(--text); text-align: left; font: 13px/1.7 var(--font); cursor: pointer; overflow-wrap: anywhere; }
.suggested-question:hover:not(:disabled) { background: var(--accent-glow); border-color: var(--accent); }
.suggested-question>span:last-child { color: var(--accent); flex: none; }
.suggested-question:disabled { color: var(--text-mut); cursor: default; opacity: .65; }
.suggestion-notice, .tree-browse-hint { color: var(--text-mut); font-size: 12px; line-height: 1.8; margin: 10px 0; }
.tree-browse-hint { margin-top: 20px; }
.suggestion-failure { font-size: 12px; line-height: 1.8; color: var(--text-mut); }
.tree-action { background: none; border: 0; padding: 2px 0; color: var(--accent); font: 12px var(--font); cursor: pointer; }
.tree-action:disabled { color: var(--text-dim); cursor: default; }
.continue-node { margin-top: 12px; }
.continue-node span { margin-left: 8px; }
.tree-empty { color: var(--text-mut); font-size: 13px; line-height: 1.8; padding: 32px 6px; }
.tree-empty-mark { display: block; font-size: 24px; color: var(--accent); margin-bottom: 12px; }
.tree-footer { padding-top: 22px; }
.tree-status { display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; color: var(--text-mut); font-size: 11px; line-height: 1.7; }
.tree-status.failed { color: var(--red); }
.status-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--accent); flex: none; align-self: center; }
.status-dot.pending { animation: tree-pulse 1.5s ease-in-out infinite; }
.tree-footer-actions { display: flex; justify-content: space-between; gap: 12px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border); }
.tree-footer-actions a { color: var(--text-mut); font-size: 12px; text-decoration: none; }
.tree-footer-actions a:hover { color: var(--accent); }
button:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 4px; }
@keyframes tree-pulse { 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .status-dot.pending { animation: none; } }
@media (max-width: 1100px) { .learning-tree-panel { width: 265px; flex-basis: 265px; padding: 20px 14px 16px; } }
@media (max-width: 900px) {
  .learning-tree-panel { width: 100%; flex-basis: auto; min-height: 230px; max-height: 70vh; padding: 20px 18px; border-left: 0; border-top: 1px solid var(--border); }
  .tree-node, .tree-fold { min-height: 44px; }
  .tree-close { width: 32px; height: 32px; }
}
</style>
