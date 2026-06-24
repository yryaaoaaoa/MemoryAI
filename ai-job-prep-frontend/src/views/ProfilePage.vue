<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { authApi, type UserProfile, type SkillProfile } from '@/api/auth.api'

const profile = ref<UserProfile | null>(null)
const loading = ref(true)
const activeSkill = ref<string | null>(null)

onMounted(async () => {
  try {
    profile.value = await authApi.getProfile()
    if (profile.value?.skillProfiles?.length) {
      activeSkill.value = profile.value.skillProfiles[0].skillId
    }
  } catch { /* ok */ }
  finally { loading.value = false }
})

const activeProfile = computed<SkillProfile | undefined>(() =>
  profile.value?.skillProfiles?.find(s => s.skillId === activeSkill.value)
)

function levelBadgeClass(level: string) {
  return level === 'strong' ? 'l-strong'
    : level === 'adequate' ? 'l-adequate'
    : level === 'weak' ? 'l-weak'
    : 'l-untouched'
}

function levelLabel(level: string) {
  return level === 'strong' ? '熟练'
    : level === 'adequate' ? '掌握'
    : level === 'weak' ? '薄弱'
    : '未覆盖'
}
</script>

<template>
  <div class="pf">
    <div class="pf-hd">
      <h2>用户画像</h2>
      <p class="pf-sub">技能画像与刷题统计</p>
    </div>

    <div v-if="loading" class="pf-empty">加载中...</div>

    <template v-if="profile">
      <!-- Stats row -->
      <div class="pf-stats">
        <div class="pf-stat">
          <span class="pf-stat-val">{{ profile.totalQuizzes }}</span>
          <span class="pf-stat-lbl">总答题数</span>
        </div>
        <div class="pf-stat">
          <span class="pf-stat-val">{{ profile.correctRate }}%</span>
          <span class="pf-stat-lbl">正确率</span>
        </div>
        <div class="pf-stat">
          <span class="pf-stat-val">{{ profile.streak }}</span>
          <span class="pf-stat-lbl">最长连续正确</span>
        </div>
        <div class="pf-stat">
          <span class="pf-stat-val">{{ profile.wrongQuestionCount }}</span>
          <span class="pf-stat-lbl">待复习</span>
        </div>
      </div>

      <!-- Tech stack -->
      <section class="pf-sec">
        <h3 class="pf-sec-hd">技术栈</h3>
        <div v-if="!profile.techStack.length" class="pf-sec-mut">暂无技术标签，刷题后自动生成</div>
        <div v-else class="pf-tags">
          <span v-for="t in profile.techStack" :key="t" class="pf-tag">{{ t }}</span>
        </div>
      </section>

      <!-- Skill profile tabs -->
      <section v-if="profile.skillProfiles.length" class="pf-sec">
        <h3 class="pf-sec-hd">技能画像</h3>

        <!-- Tab bar -->
        <div class="pf-tabs">
          <button
            v-for="sp in profile.skillProfiles"
            :key="sp.skillId"
            :class="['pf-tab', { active: activeSkill === sp.skillId }]"
            @click="activeSkill = sp.skillId"
          >
            <span class="pf-tab-dot" :style="{ background: sp.gradient }" />
            {{ sp.skillName }}
            <span class="pf-tab-cnt">{{ sp.interviewCount }}次</span>
          </button>
        </div>

        <!-- Active profile content -->
        <div v-if="activeProfile" class="pf-profile">
          <!-- Summary -->
          <div v-if="activeProfile.profileJson?.summary" class="pf-summary">
            {{ activeProfile.profileJson.summary }}
          </div>

          <!-- Categories -->
          <div
            v-for="cat in activeProfile.profileJson?.categories ?? []"
            :key="cat.category"
            class="pf-cat-card"
          >
            <div class="pf-cat-hd">
              <span class="pf-cat-label">{{ cat.category }}</span>
              <span :class="['pf-cat-badge', levelBadgeClass(cat.level)]">{{ levelLabel(cat.level) }}</span>
            </div>
            <p class="pf-cat-text">{{ cat.text }}</p>
          </div>

          <!-- Next advice -->
          <div v-if="activeProfile.profileJson?.nextAdvice" class="pf-advice">
            <span class="pf-advice-icon">💡</span>
            {{ activeProfile.profileJson.nextAdvice }}
          </div>

          <!-- Raw profile fallback if JSON parse fails -->
          <div v-if="!activeProfile.profileJson" class="pf-sec-mut">
            画像数据格式异常
          </div>
        </div>
      </section>

      <!-- Empty state -->
      <section v-else class="pf-sec">
        <h3 class="pf-sec-hd">技能画像</h3>
        <p class="pf-sec-mut">暂无面试记录，完成模拟面试后将自动生成 LLM 画像</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.pf {
  max-width: 640px; margin: 0 auto; padding: 32px 24px;
}
.pf-hd { margin-bottom: 24px; }
.pf-hd h2 { font-size: 20px; font-weight: 700; margin: 0; }
.pf-sub { font-size: 13px; color: var(--text-dim); margin: 4px 0 0; }
.pf-empty { padding: 48px 0; text-align: center; color: var(--text-dim); font-size: 14px; }

.pf-stats { display: flex; gap: 8px; margin-bottom: 28px; }
.pf-stat {
  flex: 1; text-align: center; padding: 14px 8px;
  background: var(--bg-glass); border: 1px solid var(--border); border-radius: 10px;
}
.pf-stat-val { display: block; font-size: 22px; font-weight: 800; color: var(--accent); }
.pf-stat-lbl { display: block; font-size: 11px; color: var(--text-dim); margin-top: 4px; }

.pf-sec { margin-bottom: 24px; }
.pf-sec-hd { font-size: 14px; font-weight: 600; margin: 0 0 12px; }
.pf-sec-mut { font-size: 12px; color: var(--text-dim); padding: 12px 0; }

.pf-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.pf-tag {
  padding: 4px 12px; border-radius: 20px; font-size: 12px;
  background: var(--accent-glow); color: var(--accent); border: 1px solid rgba(56,189,248,0.15);
  font-weight: 500;
}

/* Tabs */
.pf-tabs {
  display: flex; gap: 8px; margin-bottom: 16px; overflow-x: auto;
  padding-bottom: 4px;
}
.pf-tab {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600;
  background: var(--bg-glass); border: 1px solid var(--border);
  color: var(--text-dim); cursor: pointer; white-space: nowrap;
  transition: all 0.2s; flex-shrink: 0;
}
.pf-tab:hover { color: var(--text); border-color: var(--accent-dim); }
.pf-tab.active { color: var(--accent); border-color: var(--accent); background: var(--accent-glow); }
.pf-tab-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
}
.pf-tab-cnt { font-size: 10px; font-weight: 400; color: var(--text-dim); }

/* Profile content */
.pf-profile { display: flex; flex-direction: column; gap: 12px; }

.pf-summary {
  font-size: 14px; font-weight: 600; line-height: 1.5; color: var(--text);
  padding: 12px 14px; border-radius: 8px;
  background: var(--bg-glass); border: 1px solid var(--border);
}

.pf-cat-card {
  padding: 12px 14px; border-radius: 8px;
  background: var(--bg-glass); border: 1px solid var(--border);
}
.pf-cat-hd {
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
}
.pf-cat-label { font-size: 13px; font-weight: 700; color: var(--text); }
.pf-cat-badge {
  font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 10px;
}
.pf-cat-badge.l-strong { color: var(--green); background: rgba(34,197,94,0.1); border: 1px solid rgba(34,197,94,0.2); }
.pf-cat-badge.l-adequate { color: var(--accent); background: var(--accent-glow); border: 1px solid rgba(56,189,248,0.2); }
.pf-cat-badge.l-weak { color: var(--amber); background: rgba(245,158,11,0.1); border: 1px solid rgba(245,158,11,0.2); }
.pf-cat-badge.l-untouched { color: var(--text-dim); background: rgba(148,163,184,0.1); border: 1px solid rgba(148,163,184,0.15); }

.pf-cat-text { font-size: 12px; line-height: 1.6; color: var(--text-dim); margin: 0; }

.pf-advice {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 12px 14px; border-radius: 8px; font-size: 13px; line-height: 1.5;
  background: rgba(245,158,11,0.08); border: 1px solid rgba(245,158,11,0.12);
  color: var(--amber);
}
.pf-advice-icon { flex-shrink: 0; font-size: 14px; }
</style>
