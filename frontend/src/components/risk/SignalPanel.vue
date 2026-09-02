<script setup>
import { computed } from 'vue';

const props = defineProps({
  evidences: Array,
});

const SIGNAL_LABEL = {
  S1: '권위 확립',
  S2: '공포 주입',
  S3: '격리 유도',
  S4: '원격제어·정보탈취',
  S5: '자산 이동',
  N1: '제3자 상의 권유',
  N2: '공식 채널 재확인 유도',
  N3: '사용자 발신 통화',
  N4: '정보 안내로 종결',
};

const STAGE_ORDER = ['S1', 'S2', 'S3', 'S4', 'S5'];

const stages = computed(() =>
  STAGE_ORDER.map((id) => ({
    id,
    label: SIGNAL_LABEL[id],
    detected: props.evidences?.find((item) => item.id === id) ?? null,
  })),
);

const negatives = computed(() =>
  (props.evidences ?? [])
    .filter((item) => item.id.startsWith('N'))
    .map((item) => ({ ...item, label: SIGNAL_LABEL[item.id] })),
);
</script>

<template>
  <section class="panel">
    <h2 class="title">단계 신호</h2>
    <ol class="stages">
      <li v-for="stage in stages" :key="stage.id" class="stage" :class="{ on: stage.detected }">
        <span class="chip">{{ stage.id }}</span>
        <div class="body">
          <p class="name">
            {{ stage.label }}
            <span v-if="stage.detected" class="confidence">{{ stage.detected.confidence.toFixed(2) }}</span>
          </p>
          <p v-if="stage.detected?.evidence" class="evidence">&ldquo;{{ stage.detected.evidence }}&rdquo;</p>
        </div>
      </li>
    </ol>

    <template v-if="negatives.length">
      <h2 class="title">감점 신호</h2>
      <ul class="negatives">
        <li v-for="item in negatives" :key="item.id">
          <span class="chip negative">{{ item.id }}</span>
          <div class="body">
            <p class="name">{{ item.label }}</p>
            <p v-if="item.evidence" class="evidence">&ldquo;{{ item.evidence }}&rdquo;</p>
          </div>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.panel {
  padding: 16px;
  border: 1px solid var(--personal-color-gray-300);
  border-radius: var(--personal-radius-card);
  background: var(--personal-color-white);
}

.title {
  margin: 0 0 12px;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
  font-weight: 600;
}

.stages,
.negatives {
  margin: 0 0 4px;
  padding: 0;
  list-style: none;
}

.stage,
.negatives li {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--personal-color-gray-100);
}

.stage:first-child {
  border-top: none;
}

.chip {
  flex: none;
  width: 30px;
  height: 22px;
  border-radius: var(--personal-radius);
  background: var(--personal-color-gray-100);
  color: var(--personal-color-gray-600);
  font-size: 0.7rem;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
}

/* 점등된 단계만 색이 들어와야 진행 상황이 한눈에 보인다 */
.stage.on .chip {
  background: var(--personal-color-red);
  color: var(--personal-color-white);
}

.chip.negative {
  background: var(--personal-color-gray-600);
  color: var(--personal-color-white);
}

.body {
  min-width: 0;
}

.name {
  margin: 2px 0 0;
  color: var(--personal-color-gray-600);
  font-size: 0.85rem;
}

.stage.on .name,
.negatives .name {
  color: var(--personal-color-black);
  font-weight: 600;
}

.confidence {
  margin-left: 6px;
  color: var(--personal-color-gray-600);
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  font-weight: 400;
}

.evidence {
  margin: 4px 0 0;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
  line-height: 1.5;
}
</style>
