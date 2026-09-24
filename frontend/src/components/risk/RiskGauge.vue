<script setup>
import { computed } from 'vue';

const props = defineProps({
  risk: Object,
});

const LEVEL_CLASS = {
  안전: 'safe',
  주의: 'caution',
  경고: 'warning',
  위험: 'danger',
};

const score = computed(() => props.risk?.score ?? 0);
const level = computed(() => props.risk?.level ?? '판정 대기');
const levelClass = computed(() => LEVEL_CLASS[level.value] ?? 'safe');
const guidance = computed(() => {
  if (!props.risk) return '아직 판정 결과가 없습니다.';
  if (level.value === '안전') return '현재 탐지된 위험 신호가 적습니다. 상대방의 신원이 확인된 것은 아닙니다.';
  if (level.value === '주의') return '확인이 필요한 요청이 있습니다. 송금 전 알고 있던 연락처로 상대방에게 직접 확인하세요.';
  return '송금·설치·정보 제공을 멈추고, 통화를 종료한 뒤 알고 있던 연락처로 확인하세요.';
});

// 임계치 눈금. 게이지에 근거를 같이 보여줘야 왜 이 등급인지 화면만으로 설명된다.
const MARKS = [
  { at: 30, label: '주의' },
  { at: 55, label: '경고' },
  { at: 80, label: '위험' },
];
</script>

<template>
  <section class="gauge" :class="levelClass">
    <header class="head">
      <span class="label">위험도</span>
      <span class="value">{{ props.risk ? score : '—' }}<span v-if="props.risk" class="unit">점</span></span>
      <span class="badge">{{ level }}</span>
    </header>

    <div class="track">
      <div class="fill" :style="{ width: `${score}%` }" />
      <div v-for="mark in MARKS" :key="mark.at" class="mark" :style="{ left: `${mark.at}%` }" />
    </div>

    <!-- 눈금 라벨은 track 밖에 둔다. 안에 두면 overflow:hidden에 잘린다. -->
    <div class="scale">
      <span v-for="mark in MARKS" :key="mark.at" class="scale-label" :style="{ left: `${mark.at}%` }">
        {{ mark.label }}
      </span>
    </div>

    <p class="guidance">{{ guidance }}</p>
    <p v-if="props.risk?.isolationFloor" class="note">
      격리 유도가 확인되어 위험 등급이 강제 적용됐습니다.
    </p>

    <dl v-if="props.risk" class="breakdown">
      <div>
        <dt>체인</dt>
        <dd>{{ props.risk.chain }}단계 &times;{{ props.risk.multiplier }}</dd>
      </div>
      <div>
        <dt>감점</dt>
        <dd>{{ props.risk.penalty }}{{ props.risk.negatives.length ? ` (${props.risk.negatives.join(', ')})` : '' }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.gauge {
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid var(--personal-color-gray-300);
  border-radius: var(--personal-radius-card);
  background: var(--personal-color-white);
}

.head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.label {
  color: var(--personal-color-gray-600);
  font-size: 0.85rem;
}

.value {
  font-size: 1.6rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.unit {
  margin-left: 2px;
  font-size: 0.9rem;
  font-weight: 400;
}

.badge {
  margin-left: auto;
  padding: var(--personal-badge-padding);
  border-radius: var(--personal-radius);
  font-size: var(--personal-badge-font-size);
  font-weight: 600;
}

.track {
  position: relative;
  height: 14px;
  border-radius: 7px;
  background: var(--personal-color-gray-100);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 7px;
  /* 점수가 튀는 순간이 데모의 클라이맥스라 전환을 눈에 보이게 둔다 */
  transition: width 0.6s ease-out, background-color 0.3s;
}

.mark {
  position: absolute;
  top: 0;
  width: 1px;
  height: 100%;
  background: var(--personal-color-gray-300);
}

.scale {
  position: relative;
  height: 14px;
  margin-top: 4px;
}

.scale-label {
  position: absolute;
  top: 0;
  color: var(--personal-color-gray-600);
  font-size: 0.65rem;
  white-space: nowrap;
  transform: translateX(-50%);
}

.note {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: var(--personal-radius);
  background: var(--personal-color-red-soft);
  color: var(--personal-color-red-dark);
  font-size: 0.8rem;
}

.guidance {
  margin: 12px 0 0;
  color: var(--personal-color-gray-600);
  font-size: 0.85rem;
  line-height: 1.5;
}

.breakdown {
  display: flex;
  gap: 20px;
  margin: 8px 0 0;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
}

.breakdown div {
  display: flex;
  gap: 6px;
}

.breakdown dt::after {
  content: ':';
}

.breakdown dd {
  margin: 0;
  color: var(--personal-color-black);
}

/* 등급별 색. 안전은 조용히, 위험은 즉시 눈에 띄게 */
.safe .fill { background: var(--personal-color-gray-300); }
.safe .badge { background: var(--personal-color-gray-100); color: var(--personal-color-gray-600); }

.caution .fill { background: var(--personal-color-amber); }
.caution .badge { background: var(--personal-color-amber-soft); color: var(--personal-color-amber-dark); }

.warning .fill { background: var(--personal-color-orange); }
.warning .badge { background: var(--personal-color-orange-soft); color: var(--personal-color-orange-dark); }

.danger .fill { background: var(--personal-color-red); }
.danger .badge { background: var(--personal-color-red); color: var(--personal-color-white); }
.danger .value { color: var(--personal-color-red-dark); }
</style>
