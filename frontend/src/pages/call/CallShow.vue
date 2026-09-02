<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import CallControls from '../../components/call/CallControls.vue';
import RiskGauge from '../../components/risk/RiskGauge.vue';
import SignalPanel from '../../components/risk/SignalPanel.vue';
import TranscriptPanel from '../../components/transcript/TranscriptPanel.vue';
import { useCallStream } from '../../composables/useCallStream.js';

const { status, finalTurns, partialText, errorMessage, risk, startMic, playScenario, stop } = useCallStream();

const scenarios = ref([]);
const audioByScenario = ref({});
const selectedId = ref('');
const loadError = ref('');

const selected = computed(() => scenarios.value.find((s) => s.id === selectedId.value));

const displayError = computed(() => loadError.value || errorMessage.value);

const loadScenarios = async () => {
  try {
    const [specResponse, manifestResponse] = await Promise.all([
      fetch('/scenarios/scenarios.json'),
      fetch('/scenarios/manifest.json'),
    ]);
    const spec = await specResponse.json();
    const manifest = await manifestResponse.json();

    scenarios.value = spec.scenarios.map(({ id, title, kind, inbound, expected }) => ({
      id,
      title,
      kind,
      inbound,
      expected,
    }));
    selectedId.value = scenarios.value[0]?.id ?? '';

    // manifest의 경로는 guardline 루트 기준이라 개발 서버가 서빙하는 /scenarios 경로로 바꾼다
    audioByScenario.value = manifest.reduce((grouped, entry) => {
      (grouped[entry.scenario] ??= []).push(entry.audio.replace(/^scenarios\//, '/scenarios/'));
      return grouped;
    }, {});
  } catch (e) {
    loadError.value = `시나리오를 불러오지 못했습니다: ${e.message}`;
  }
};

const handlePlay = () => {
  const urls = audioByScenario.value[selectedId.value];
  if (urls?.length) {
    playScenario(urls, selected.value?.inbound ?? true);
  }
};

onMounted(loadScenarios);
onUnmounted(stop);
</script>

<template>
  <main class="page">
    <header class="header">
      <h1 class="title">Guard Line</h1>
      <p class="subtitle">보이스피싱 실시간 탐지</p>
    </header>

    <CallControls
      :scenarios="scenarios"
      :selected-id="selectedId"
      :status="status"
      :error-message="displayError"
      @update:selected-id="selectedId = $event"
      @play="handlePlay"
      @mic="startMic"
      @stop="stop"
    />

    <RiskGauge :risk="risk" />

    <p v-if="selected" class="expected">
      이 시나리오의 기대 판정 · <strong>{{ selected.expected.level }}</strong> ({{ selected.expected.score }}점)
    </p>

    <div class="split">
      <TranscriptPanel :final-turns="finalTurns" :partial-text="partialText" />
      <SignalPanel :evidences="risk?.evidences ?? []" />
    </div>
  </main>
</template>

<style scoped>
.page {
  max-width: 1040px;
  margin: 0 auto;
  padding: 32px 20px;
}

.header {
  margin-bottom: 20px;
}

.title {
  margin: 0;
  font-size: 1.5rem;
}

.subtitle {
  margin: 4px 0 0;
  color: var(--personal-color-gray-600);
  font-size: 0.9rem;
}

.expected {
  margin: 0 0 12px;
  color: var(--personal-color-gray-600);
  font-size: 0.8rem;
}

.split {
  display: grid;
  grid-template-columns: 1fr 380px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
