<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api/settings.api'
import type { RetrievalConfig, RetrievalTier } from '@/api/settings.api'

const loading = ref(false)
const saving = ref(false)
const config = ref<RetrievalConfig>({
  rankConstant: 60,
  numCandidatesFactor: 2,
  rankWindowFactor: 2,
  bm25Fields: 'content^3,heading_path',
  shortQueryMaxLen: 4,
  mediumQueryMaxLen: 12,
  shortQuery: { topK: 20, minScore: 0.25 },
  mediumQuery: { topK: 12, minScore: 0.28 },
  longQuery: { topK: 8, minScore: 0.28 },
  defaults: { topK: 3, minScore: 0.2 },
})

async function fetchConfig() {
  loading.value = true
  try {
    config.value = await settingsApi.getRetrievalConfig()
  } catch {
    ElMessage.error('加载配置失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await settingsApi.saveRetrievalConfig(config.value)
    ElMessage.success('配置已保存')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function reset() {
  try {
    await settingsApi.resetRetrievalConfig()
    await fetchConfig()
    ElMessage.success('已重置为默认配置')
  } catch {
    ElMessage.error('重置失败')
  }
}

onMounted(fetchConfig)
</script>

<template>
  <div class="page">
    <div class="page-hd">
      <h2>检索配置</h2>
      <div class="page-actions">
        <el-button @click="reset" :disabled="loading">重置默认</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存配置</el-button>
      </div>
    </div>

    <el-form v-if="config" v-loading="loading" class="card-grid">
      <!-- Hybrid Search — full width -->
      <el-card class="sec-card full-col">
        <template #header>混合检索参数</template>
        <div class="grid-2col">
          <el-form-item label="RRF rank_constant">
            <el-input-number v-model="config.rankConstant" :min="1" :max="1000" />
            <div class="hint">RRF 融合排序常数，越大越平均</div>
          </el-form-item>
          <el-form-item label="numCandidates 系数">
            <el-input-number v-model="config.numCandidatesFactor" :min="1" :max="10" :step="0.5" />
            <div class="hint">numCandidates = topK × 系数</div>
          </el-form-item>
          <el-form-item label="rankWindow 系数">
            <el-input-number v-model="config.rankWindowFactor" :min="1" :max="10" :step="0.5" />
            <div class="hint">rankWindowSize = topK × 系数</div>
          </el-form-item>
          <el-form-item label="BM25 检索字段">
            <el-input v-model="config.bm25Fields" />
            <div class="hint">multi_match 字段列表，权重用 ^ 标注</div>
          </el-form-item>
        </div>
      </el-card>

      <!-- Query Length Segments — full width -->
      <el-card class="sec-card full-col">
        <template #header>查询长度分段</template>
        <div class="grid-2col">
          <el-form-item label="短查询最大字符数">
            <el-input-number v-model="config.shortQueryMaxLen" :min="1" :max="50" />
          </el-form-item>
          <el-form-item label="中等查询最大字符数">
            <el-input-number v-model="config.mediumQueryMaxLen" :min="1" :max="100" />
          </el-form-item>
        </div>
      </el-card>

      <!-- Tier Configs — 2x2 grid -->
      <el-card class="sec-card">
        <template #header>短查询参数 <span class="tier-hint">≤ {{ config.shortQueryMaxLen }} 字</span></template>
        <el-form-item label="topK">
          <el-input-number v-model="config.shortQuery.topK" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="minScore">
          <el-input-number v-model="config.shortQuery.minScore" :min="0" :max="1" :step="0.01" />
        </el-form-item>
      </el-card>

      <el-card class="sec-card">
        <template #header>中等查询参数 <span class="tier-hint">≤ {{ config.mediumQueryMaxLen }} 字</span></template>
        <el-form-item label="topK">
          <el-input-number v-model="config.mediumQuery.topK" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="minScore">
          <el-input-number v-model="config.mediumQuery.minScore" :min="0" :max="1" :step="0.01" />
        </el-form-item>
      </el-card>

      <el-card class="sec-card">
        <template #header>长查询参数 <span class="tier-hint">&gt; {{ config.mediumQueryMaxLen }} 字</span></template>
        <el-form-item label="topK">
          <el-input-number v-model="config.longQuery.topK" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="minScore">
          <el-input-number v-model="config.longQuery.minScore" :min="0" :max="1" :step="0.01" />
        </el-form-item>
      </el-card>

      <el-card class="sec-card">
        <template #header>Agent 默认参数</template>
        <el-form-item label="topK">
          <el-input-number v-model="config.defaults.topK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="minScore">
          <el-input-number v-model="config.defaults.minScore" :min="0" :max="1" :step="0.01" />
        </el-form-item>
      </el-card>
    </el-form>
  </div>
</template>

<style scoped>
.page { padding: 24px; }

.page-hd {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
.page-hd h2 { margin: 0; font-size: 20px; font-weight: 700; }
.page-actions { display: flex; gap: 8px; }

/* 2-column grid for cards */
.card-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.card-grid .sec-card { margin-bottom: 0; }
.card-grid .full-col { grid-column: 1 / -1; }

.sec-card { border-radius: 8px; }
.sec-card :deep(.el-card__header) {
  padding: 14px 20px;
  font-weight: 600;
  font-size: 14px;
}
.sec-card :deep(.el-card__body) { padding: 20px; }
.sec-card :deep(.el-form-item) {
  display: flex;
  flex-direction: column;
  margin-bottom: 14px;
}
.sec-card :deep(.el-form-item:last-child) { margin-bottom: 0; }
.sec-card :deep(.el-form-item__label) {
  padding: 0 0 4px;
  font-weight: 500;
  line-height: 1.4;
  height: auto;
}
.sec-card :deep(.el-form-item__content) {
  flex-direction: column;
  align-items: stretch;
  flex-wrap: nowrap;
}
.sec-card :deep(.el-input-number) { width: 120px; }
.sec-card :deep(.el-input) { width: 100%; }
.sec-card :deep(.el-input__wrapper) { width: 100%; }

/* inner 2-col grid for form items */
.grid-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 24px;
}

.tier-hint {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary, #64748b);
  margin-left: 6px;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-placeholder, #94a3b8);
}

@media (max-width: 900px) {
  .page { padding: 16px; }
  .card-grid { grid-template-columns: 1fr; }
  .grid-2col { grid-template-columns: 1fr; }
}
</style>
