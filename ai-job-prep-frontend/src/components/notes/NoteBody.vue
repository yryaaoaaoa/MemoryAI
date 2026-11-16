<script setup lang="ts">
import { ref, watch } from 'vue'
import KnowledgeTree from './KnowledgeTree.vue'
import { newKnowledgeNode, readTree, treeFields, type KnowledgeNode } from '@/utils/knowledge-tree'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string; treeJson?: string | null; editing: boolean; disabled?: boolean; title?: string }>()
const emit = defineEmits<{ change: [value: { content: string; treeJson: string | null }]; edit: [] }>()
const nodes = ref<KnowledgeNode[]>([])
const problem = ref('')
const editor = ref<InstanceType<typeof KnowledgeTree>>()
let ownJson: string | null = null
watch(() => props.treeJson, value => {
  if (value && value === ownJson) return
  ownJson = null
  problem.value = ''
  if (!value) { nodes.value = []; ownJson = null; return }
  try { nodes.value = readTree(value).nodes }
  catch { problem.value = '无法读取知识树，已显示完整正文，可以复制保留内容。' }
}, { immediate: true })
function update(value: KnowledgeNode[]) {
  nodes.value = value
  const fields = treeFields(value)
  ownJson = fields.treeJson
  emit('change', fields)
}
function convert() { emit('edit'); update([newKnowledgeNode('原笔记', props.content)]) }
defineExpose({ finish: () => editor.value?.finish() })
</script>

<template>
  <div class="note-body">
    <p v-if="problem" role="alert">{{ problem }}</p>
    <KnowledgeTree v-if="treeJson && !problem" ref="editor" :model-value="nodes" :disabled="disabled" :title="title" @update:model-value="update" @edit="emit('edit')" />
    <template v-else-if="editing && !problem">
      <div class="body-format"><span>笔记正文</span><el-button :disabled="disabled" @click="convert">转为知识树</el-button></div>
      <el-input :model-value="content" aria-label="笔记正文" type="textarea" :rows="14" maxlength="40000" :disabled="disabled" @update:model-value="emit('change', { content: $event, treeJson: null })" />
    </template>
    <template v-else>
      <div v-if="!problem" class="body-format"><el-button :disabled="disabled" @click="convert">转为知识树</el-button></div>
      <div class="note-markdown" v-html="renderMarkdown(content)" />
    </template>
  </div>
</template>

<style scoped>
.note-body { width: 100%; min-width: 0; }
.body-format { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-size: 14px; }
.body-format:has(>button:only-child) { justify-content: flex-end; }
.note-markdown { line-height: 1.85; overflow-wrap: anywhere; }
.note-markdown :deep(pre) { padding: 16px; border-radius: 8px; background: var(--bg-hover); overflow-x: auto; max-width: 100%; }
.note-markdown :deep(img) { max-width: 100%; }
.note-markdown :deep(table) { display: block; overflow-x: auto; }
</style>
