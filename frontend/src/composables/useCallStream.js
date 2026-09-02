import { ref } from 'vue';
import workletUrl from '../util/audio/pcmWorklet.js?url';
import { CHUNK_SAMPLES, TARGET_SAMPLE_RATE, decodeToMono16k, floatToPcm16 } from '../util/audio/pcmEncoder.js';

const RELAY_PATH = '/ws/call';
const READY_TIMEOUT_MS = 15000;
const CHUNK_INTERVAL_MS = (CHUNK_SAMPLES / TARGET_SAMPLE_RATE) * 1000;

/** 업스트림이 허용하는 최소 청크 길이 50ms. 이보다 짧게 보내면 종료 코드 3007로 끊긴다. */
const MIN_CHUNK_SAMPLES = TARGET_SAMPLE_RATE * 0.05;

/**
 * 브라우저 오디오를 릴레이로 흘려보내고 전사 이벤트를 받는다.
 *
 * <p>마이크는 AudioContext를 16kHz로 열어 브라우저가 리샘플하게 하고, 파일은 OfflineAudioContext로
 * 미리 변환한 뒤 실제 통화 속도에 맞춰 청크를 내보낸다. 한 번에 몰아 보내면 업스트림이 턴 경계를
 * 제대로 못 잡아 실시간처럼 동작하지 않는다.
 */
export function useCallStream() {
  // 1. State
  const status = ref('idle'); // idle | connecting | ready | streaming | closed | error
  const finalTurns = ref([]);
  const partialText = ref('');
  const errorMessage = ref('');
  const risk = ref(null);

  let socket = null;
  let audioContext = null;
  let playbackContext = null;
  let micStream = null;
  let readyResolve = null;

  // 재생 세대(generation). stop()이나 새 재생이 시작되면 증가하고, 진행 중이던 루프는
  // 자기 세대가 아님을 확인하는 즉시 빠져나온다. fetch/디코딩 await 사이에 stop이 끼어들어
  // 이미 멈춘 뒤에 다음 턴 오디오가 새로 재생되는 문제를 막는다.
  let generation = 0;

  // 2. Getters
  // (없음 - 표시용 파생값은 컴포넌트에서 계산한다)

  // 3. Actions
  const handleEvent = (event) => {
    switch (event.type) {
      case 'ready':
        status.value = 'ready';
        readyResolve?.();
        break;
      case 'partial':
        partialText.value = event.transcript;
        break;
      case 'final':
        finalTurns.value.push({ transcript: event.transcript, turnOrder: event.turnOrder });
        partialText.value = '';
        break;
      case 'risk':
        risk.value = event.risk;
        break;
      case 'closed':
        status.value = 'closed';
        break;
      case 'error':
        status.value = 'error';
        errorMessage.value = event.message;
        readyResolve?.();
        break;
      default:
        break;
    }
  };

  const openSocket = () => new Promise((resolve, reject) => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    socket = new WebSocket(`${scheme}://${window.location.host}${RELAY_PATH}`);
    socket.binaryType = 'arraybuffer';

    socket.onopen = resolve;
    socket.onerror = () => reject(new Error('릴레이 서버에 연결하지 못했습니다. 백엔드가 떠 있는지 확인하세요.'));
    socket.onmessage = (message) => handleEvent(JSON.parse(message.data));
    socket.onclose = () => {
      if (status.value !== 'error') {
        status.value = 'closed';
      }
    };
  });

  /**
   * start를 보내고 업스트림 STT가 Begin을 돌려줄 때까지 기다린다.
   * inbound는 텍스트가 아닌 통화 메타데이터로, 사용자가 건 전화면 감점 신호 N3이 붙는다.
   */
  const waitForReady = (inbound) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('업스트림 STT 응답이 없습니다. API 키 설정을 확인하세요.')), READY_TIMEOUT_MS);
    readyResolve = () => {
      clearTimeout(timer);
      resolve();
    };
    socket.send(JSON.stringify({ type: 'start', inbound }));
  });

  const sendChunk = (samples) => {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(floatToPcm16(samples));
    }
  };

  const prepare = async (inbound) => {
    reset();
    status.value = 'connecting';
    await openSocket();
    await waitForReady(inbound);
    if (status.value === 'error') {
      throw new Error(errorMessage.value);
    }
    status.value = 'streaming';
  };

  const startMic = async (inbound = true) => {
    try {
      await prepare(inbound);

      // 16kHz로 컨텍스트를 열면 브라우저가 마이크 원본(보통 48kHz)을 알아서 리샘플해준다.
      audioContext = new AudioContext({ sampleRate: TARGET_SAMPLE_RATE });
      await audioContext.audioWorklet.addModule(workletUrl);

      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });

      const collector = new AudioWorkletNode(audioContext, 'pcm-collector');
      collector.port.onmessage = (message) => sendChunk(message.data);

      audioContext.createMediaStreamSource(micStream).connect(collector);
      collector.connect(audioContext.destination);
    } catch (e) {
      status.value = 'error';
      errorMessage.value = e.message;
      await stop();
    }
  };

  /**
   * 시나리오 음성을 흘려보낸다.
   *
   * <p>턴 파일을 하나씩 보내면 파일마다 50ms 미만의 자투리 청크가 생기고 페이싱 시계도 리셋되어
   * 업스트림이 종료 코드 3007(청크 범위 이탈 또는 실시간 초과)로 세션을 끊는다. 전 구간을 미리
   * 디코딩해 하나로 이은 뒤 균일하게 잘라 보내면 자투리는 마지막 한 번뿐이고, 대화가 끊기지 않아
   * 턴 경계 판정도 실제 통화에 가까워진다.
   *
   * @param urls 턴 단위 오디오 경로 배열
   */
  const playScenario = async (urls, inbound = true) => {
    await stop(); // 이전 재생이 남아 있으면 먼저 끊는다
    const run = ++generation;

    try {
      await prepare(inbound);
      if (run !== generation) {
        return;
      }

      const parts = [];
      for (const url of urls) {
        const response = await fetch(url);
        parts.push(await decodeToMono16k(await response.arrayBuffer()));
        if (run !== generation) {
          return;
        }
      }

      const samples = concat(parts);
      playAloud(samples); // 데모 영상용. 스트리밍과 같은 샘플이라 화면 자막과 어긋나지 않는다

      await streamAtRealtime(samples, run);
      if (run === generation) {
        await stop();
      }
    } catch (e) {
      if (run === generation) {
        status.value = 'error';
        errorMessage.value = e.message;
        await stop();
      }
    }
  };

  const concat = (parts) => {
    const merged = new Float32Array(parts.reduce((total, part) => total + part.length, 0));
    let offset = 0;
    for (const part of parts) {
      merged.set(part, offset);
      offset += part.length;
    }
    return merged;
  };

  /** 스트리밍과 동일한 샘플을 스피커로도 내보낸다. 자동재생이 막히면 조용히 넘어간다. */
  const playAloud = (samples) => {
    try {
      playbackContext = new AudioContext({ sampleRate: TARGET_SAMPLE_RATE });
      const buffer = playbackContext.createBuffer(1, samples.length, TARGET_SAMPLE_RATE);

      buffer.copyToChannel(samples, 0);
      const source = playbackContext.createBufferSource();
      source.buffer = buffer;
      source.connect(playbackContext.destination);
      source.start();
    } catch {
      playbackContext = null;
    }
  };

  /**
   * 벽시계 기준으로 다음 청크 시각을 잡아 누적 지연이 쌓이지 않게 한다.
   * 마지막 자투리는 50ms 미만이면 무음으로 채워 보낸다. 그대로 보내면 3007로 끊긴다.
   */
  const streamAtRealtime = async (samples, run) => {
    const startedAt = performance.now();

    for (let offset = 0, index = 0; offset < samples.length; offset += CHUNK_SAMPLES, index += 1) {
      if (run !== generation) {
        return;
      }

      // 실시간보다 앞서 보내면 3007이므로 보내기 전에 해당 청크 시각까지 기다린다
      const dueAt = startedAt + index * CHUNK_INTERVAL_MS;
      const wait = dueAt - performance.now();
      if (wait > 0) {
        await new Promise((resolve) => setTimeout(resolve, wait));
      }
      if (run !== generation) {
        return;
      }

      const chunk = samples.subarray(offset, offset + CHUNK_SAMPLES);
      if (chunk.length >= MIN_CHUNK_SAMPLES) {
        sendChunk(chunk);
      } else {
        const padded = new Float32Array(MIN_CHUNK_SAMPLES);
        padded.set(chunk);
        sendChunk(padded);
      }
    }
  };

  const stop = async () => {
    generation += 1; // 진행 중인 재생 루프를 전부 무효화한다

    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'stop' }));
      socket.close();
    }
    socket = null;

    micStream?.getTracks().forEach((track) => track.stop());
    micStream = null;

    if (audioContext) {
      await audioContext.close();
      audioContext = null;
    }

    // 스피커로 나가던 재생을 끊는다. 컨텍스트를 닫으면 연결된 소스도 함께 멈춘다.
    if (playbackContext) {
      await playbackContext.close();
      playbackContext = null;
    }
  };

  const reset = () => {
    finalTurns.value = [];
    partialText.value = '';
    errorMessage.value = '';
    risk.value = null;
  };

  return { status, finalTurns, partialText, errorMessage, risk, startMic, playScenario, stop };
}
