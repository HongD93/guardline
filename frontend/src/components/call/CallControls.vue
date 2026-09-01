<script setup>
const props = defineProps({
  scenarios: Array,
  selectedId: String,
  status: String,
  errorMessage: String,
});

const emit = defineEmits(['update:selectedId', 'play', 'mic', 'stop']);

const STATUS_LABEL = {
  idle: '대기',
  connecting: '연결 중',
  ready: '업스트림 준비됨',
  streaming: '스트리밍 중',
  closed: '종료됨',
  error: '오류',
};
</script>

<template>
  <div class="controls">
    <div class="row">
      <label class="label" for="scenario">검증 시나리오</label>
      <select
        id="scenario"
        class="select"
        :value="props.selectedId"
        :disabled="props.status === 'streaming' || props.status === 'connecting'"
        @change="emit('update:selectedId', $event.target.value)"
      >
        <option v-for="scenario in props.scenarios" :key="scenario.id" :value="scenario.id">
          {{ scenario.kind === 'fraud' ? '[사기]' : '[정상]' }} {{ scenario.title }}
        </option>
      </select>
    </div>

    <div class="row">
      <button
        type="button"
        class="button primary"
        :disabled="props.status === 'streaming' || props.status === 'connecting'"
        @click="emit('play')"
      >
        시나리오 재생
      </button>
      <button
        type="button"
        class="button"
        :disabled="props.status === 'streaming' || props.status === 'connecting'"
        @click="emit('mic')"
      >
        마이크로 말하기
      </button>
      <button
        type="button"
        class="button"
        :disabled="props.status !== 'streaming' && props.status !== 'ready'"
        @click="emit('stop')"
      >
        정지
      </button>

      <span class="status" :class="props.status">{{ STATUS_LABEL[props.status] ?? props.status }}</span>
    </div>

    <p v-if="props.errorMessage" class="error" role="alert">{{ props.errorMessage }}</p>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.label {
  flex: none;
  color: var(--personal-color-gray-600);
  font-size: 0.85rem;
}

.select {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid var(--personal-color-gray-300);
  border-radius: var(--personal-radius);
  background: var(--personal-color-white);
  font-size: 0.9rem;
}

.button {
  padding: 8px 16px;
  border: 1px solid var(--personal-color-gray-300);
  border-radius: var(--personal-radius);
  background: var(--personal-color-white);
  font-size: 0.9rem;
  cursor: pointer;
}

.button:disabled {
  color: var(--personal-color-gray-600);
  cursor: not-allowed;
  opacity: 0.5;
}

.primary {
  border-color: var(--personal-color-red);
  background: var(--personal-color-red);
  color: var(--personal-color-white);
}

.status {
  margin-left: auto;
  color: var(--personal-color-gray-600);
  font-size: 0.85rem;
}

.status.streaming {
  color: var(--personal-color-red);
  font-weight: 600;
}

.status.error {
  color: var(--personal-color-red-dark);
  font-weight: 600;
}

.error {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--personal-radius);
  background: var(--personal-color-red-soft);
  color: var(--personal-color-red-dark);
  font-size: 0.85rem;
}
</style>
