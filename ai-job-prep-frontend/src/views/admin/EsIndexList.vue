<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { esApi } from '@/api/es.api'
import type { EsIndexInfo } from '@/types'

const router = useRouter()
const indices = ref<EsIndexInfo[]>([])
const loading = ref(false)

const healthColor: Record<string, string> = {
  green: 'success',
  yellow: 'warning',
  red: 'danger',
}

const healthLabel: Record<string, string> = {
  green: '健康',
  yellow: '警告',
  red: '异常',
}

async function fetchData() {
  loading.value = true
  try {
    indices.value = await esApi.listIndices()
  } catch {
    indices.value = []
  } finally {
    loading.value = false
  }
}

function formatStorage(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function goDetail(index: string) {
  router.push(`/admin/es/${encodeURIComponent(index)}`)
}

async function handleDelete(row: EsIndexInfo) {
  try {
    await ElMessageBox.confirm(
      `确定要删除索引「${row.name}」吗？\n该操作将删除索引内所有数据（${row.docCount} 条文档），且不可恢复。`,
      '删除索引',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      },
    )
    await esApi.deleteIndex(row.name)
    ElMessage.success(`索引「${row.name}」已删除`)
    await fetchData()
  } catch {
    // cancelled
  }
}

onMounted(fetchData)
</script>

<template>
  <div class="admin">
    <div class="page-header">
      <h2>ES 索引管理</h2>
      <el-button @click="fetchData" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <el-table :data="indices" v-loading="loading" stripe style="width: 100%">
      <el-table-column label="健康状态" width="100">
        <template #default="{ row }">
          <el-tag :type="healthColor[row.health] ?? 'info'" size="small" effect="dark">
            {{ healthLabel[row.health] ?? row.health }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="索引名称" min-width="240">
        <template #default="{ row }">
          <el-button type="primary" link @click="goDetail(row.name)">
            {{ row.name }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="docCount" label="文档数" width="90" />
      <el-table-column label="存储大小" width="110">
        <template #default="{ row }">{{ formatStorage(row.storageSizeBytes) }}</template>
      </el-table-column>
      <el-table-column prop="numOfShards" label="主分片" width="80" />
      <el-table-column prop="numOfReplicas" label="副本数" width="80" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="primary" link @click="goDetail(row.name)">查看</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
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
