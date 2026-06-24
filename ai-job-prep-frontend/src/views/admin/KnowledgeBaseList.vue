<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useKnowledgeBaseStore } from '@/stores/knowledge-base.store'
import type { FormInstance, FormRules } from 'element-plus'
import type { KnowledgeBase } from '@/types'

const router = useRouter()
const store = useKnowledgeBaseStore()

const dialogVisible = ref(false)
const dialogTitle = ref('新建知识库')
const formRef = ref<FormInstance>()
const form = ref({ name: '', description: '' })
const editingId = ref<string | null>(null)
const submitting = ref(false)

const rules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  dialogTitle.value = '新建知识库'
  form.value = { name: '', description: '' }
  dialogVisible.value = true
}

function openEdit(kb: KnowledgeBase) {
  editingId.value = kb.id
  dialogTitle.value = '编辑知识库'
  form.value = { name: kb.name, description: kb.description ?? '' }
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (editingId.value) {
      await store.updateKB(editingId.value, form.value)
    } else {
      await store.createKB(form.value)
    }
    dialogVisible.value = false
    await store.fetchKBList()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `确定要删除知识库「${kb.name}」吗？删除后知识库及其关联的所有文档切片将被清除。`,
      '删除确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await store.deleteKB(kb.id)
    await store.fetchKBList()
  } catch {
    // cancelled
  }
}

function goDetail(id: string) {
  router.push(`/admin/knowledge-bases/${id}`)
}

onMounted(() => {
  store.fetchKBList()
})
</script>

<template>
  <div class="admin">
    <div class="page-header">
      <h2>知识库管理</h2>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新建知识库
      </el-button>
    </div>

    <el-table :data="store.kbList" v-loading="store.loading" stripe style="width: 100%">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="200" />
      <el-table-column prop="documentCount" label="文档数" width="80" />
      <el-table-column prop="chunkCount" label="切片数" width="80" />
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button type="primary" link @click="goDetail(row.id)">管理</el-button>
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入知识库名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            placeholder="请输入知识库描述（选填）"
            maxlength="200"
            show-word-limit
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin {
  max-width: 1000px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
</style>
