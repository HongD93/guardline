<script setup>
import { computed } from 'vue';

const props = defineProps({
  active: Boolean,
  guardianNotified: Boolean,
  notifiedAt: Object,
  speechBlocked: Boolean,
});

const emit = defineEmits(['replay']);

const notifiedTime = computed(() => {
  if (!props.notifiedAt) {
    return '';
  }
  return props.notifiedAt.toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
});
</script>

<template>
  <section v-if="props.active" class="intervention" role="alert">
    <header class="head">
      <span class="siren" aria-hidden="true">경고</span>
      <h2 class="title">통화를 즉시 중단하세요</h2>
      <button type="button" class="replay" @click="emit('replay')">경고 다시 듣기</button>
    </header>

    <p class="speech">
      &ldquo;보이스피싱이 의심됩니다. 지금 통화를 끊고 가족에게 확인하세요.&rdquo;
    </p>

    <p v-if="props.speechBlocked" class="blocked">
      이 브라우저에서 음성 경고를 재생할 수 없습니다. 화면 경고만 표시됩니다.
    </p>

    <ul class="actions">
      <li>
        <span class="dot done" aria-hidden="true" />
        음성 경고 재생
      </li>
      <li v-if="props.guardianNotified">
        <span class="dot done" aria-hidden="true" />
        보호자 알림 발송 <time v-if="notifiedTime">{{ notifiedTime }}</time>
        <em class="sim">시뮬레이션 — 실제 발송은 통신사 연동 필요</em>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.intervention {
  padding: 16px;
  margin-bottom: 16px;
  border: 2px solid var(--personal-color-red);
  border-radius: var(--personal-radius-card);
  background: var(--personal-color-red-soft);
  /* 데모에서 이 순간이 클라이맥스라 등장을 눈에 띄게 둔다 */
  animation: intervention-in 0.4s ease-out;
}

@keyframes intervention-in {
  from {
    opacity: 0;
    transform: translateY(-6px);
  }
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.siren {
  flex: none;
  padding: var(--personal-badge-padding);
  border-radius: var(--personal-radius);
  background: var(--personal-color-red);
  color: var(--personal-color-white);
  font-size: var(--personal-badge-font-size);
  font-weight: 700;
}

.title {
  margin: 0;
  color: var(--personal-color-red-dark);
  font-size: 1.05rem;
}

.replay {
  margin-left: auto;
  padding: 5px 12px;
  border: 1px solid var(--personal-color-red);
  border-radius: var(--personal-radius);
  background: var(--personal-color-white);
  color: var(--personal-color-red-dark);
  font-size: 0.8rem;
  cursor: pointer;
}

.speech {
  margin: 0 0 12px;
  color: var(--personal-color-red-dark);
  font-size: 0.95rem;
  line-height: 1.6;
}

.blocked {
  margin: 0 0 12px;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
}

.actions {
  margin: 0;
  padding: 0;
  list-style: none;
}

.actions li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  color: var(--personal-color-red-dark);
  font-size: 0.85rem;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--personal-color-red);
}

time {
  color: var(--personal-color-gray-600);
  font-variant-numeric: tabular-nums;
}

/* 시뮬레이션이라는 사실을 화면에서 감추지 않는다 */
.sim {
  color: var(--personal-color-gray-600);
  font-size: 0.75rem;
  font-style: normal;
}
</style>
