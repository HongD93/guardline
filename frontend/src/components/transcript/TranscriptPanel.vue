<script setup>
import { nextTick, ref, watch } from 'vue';

const props = defineProps({
  finalTurns: Array,
  partialText: String,
});

const scroller = ref(null);

watch(
  () => [props.finalTurns.length, props.partialText],
  async () => {
    await nextTick();
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight;
    }
  },
);
</script>

<template>
  <div ref="scroller" class="transcript">
    <p v-if="!finalTurns.length && !partialText" class="placeholder">
      통화를 시작하면 여기에 실시간 자막이 표시됩니다.
    </p>

    <p v-for="turn in finalTurns" :key="turn.turnOrder" class="turn">
      <span class="turn-order">{{ turn.turnOrder ?? '-' }}</span>
      {{ turn.transcript }}
    </p>

    <p v-if="partialText" class="turn partial">
      <span class="turn-order">…</span>
      {{ partialText }}
    </p>
  </div>
</template>

<style scoped>
.transcript {
  height: 420px;
  overflow-y: auto;
  padding: 16px;
  border: 1px solid var(--personal-color-gray-300);
  border-radius: var(--personal-radius-card);
  background: var(--personal-color-white);
}

.placeholder {
  color: var(--personal-color-gray-600);
}

.turn {
  display: flex;
  gap: 10px;
  margin: 0 0 10px;
  line-height: 1.6;
}

.turn-order {
  flex: none;
  width: 28px;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
  text-align: right;
}

/* 확정 전 인식 결과는 계속 바뀌므로 시각적으로 구분한다 */
.partial {
  color: var(--personal-color-gray-600);
  font-style: italic;
}
</style>
