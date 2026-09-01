import { ref } from 'vue';
import workletUrl from '../util/audio/pcmWorklet.js?url';
import { CHUNK_SAMPLES, TARGET_SAMPLE_RATE, decodeToMono16k, floatToPcm16 } from '../util/audio/pcmEncoder.js';

const RELAY_PATH = '/ws/call';
const READY_TIMEOUT_MS = 15000;
const CHUNK_INTERVAL_MS = (CHUNK_SAMPLES / TARGET_SAMPLE_RATE) * 1000;

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

  let socket = null;
  let audioContext = null;
  let micStream = null;
  let audioElement = null;
  let readyResolve = null;
  let aborted = false;

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

  /** start를 보내고 업스트림 STT가 Begin을 돌려줄 때까지 기다린다. */
  const waitForReady = () => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('업스트림 STT 응답이 없습니다. API 키 설정을 확인하세요.')), READY_TIMEOUT_MS);
    readyResolve = () => {
      clearTimeout(timer);
      resolve();
    };
    socket.send(JSON.stringify({ type: 'start' }));
  });

  const sendChunk = (samples) => {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(floatToPcm16(samples));
    }
  };

  const prepare = async () => {
    reset();
    status.value = 'connecting';
    await openSocket();
    await waitForReady();
    if (status.value === 'error') {
      throw new Error(errorMessage.value);
    }
    status.value = 'streaming';
  };

  const startMic = async () => {
    try {
      await prepare();

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
   * 시나리오 음성 파일들을 순서대로 흘려보낸다.
   * @param urls 턴 단위 오디오 경로 배열
   */
  const playScenario = async (urls) => {
    try {
      await prepare();
      aborted = false;

      for (const url of urls) {
        if (aborted) {
          break;
        }
        const response = await fetch(url);
        const samples = await decodeToMono16k(await response.arrayBuffer());

        // 데모 영상용으로 실제 소리도 함께 들려준다
        audioElement = new Audio(url);
        audioElement.play().catch(() => {}); // 자동재생이 막혀도 스트리밍은 계속한다

        await streamAtRealtime(samples);
      }
      if (!aborted) {
        await stop();
      }
    } catch (e) {
      status.value = 'error';
      errorMessage.value = e.message;
      await stop();
    }
  };

  /** 벽시계 기준으로 다음 청크 시각을 잡아 누적 지연이 쌓이지 않게 한다. */
  const streamAtRealtime = async (samples) => {
    const startedAt = performance.now();

    for (let offset = 0, index = 0; offset < samples.length; offset += CHUNK_SAMPLES, index += 1) {
      if (aborted) {
        return;
      }
      sendChunk(samples.subarray(offset, offset + CHUNK_SAMPLES));

      const nextAt = startedAt + (index + 1) * CHUNK_INTERVAL_MS;
      const wait = nextAt - performance.now();
      if (wait > 0) {
        await new Promise((resolve) => setTimeout(resolve, wait));
      }
    }
  };

  const stop = async () => {
    aborted = true;

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
    audioElement?.pause();
    audioElement = null;
  };

  const reset = () => {
    finalTurns.value = [];
    partialText.value = '';
    errorMessage.value = '';
  };

  return { status, finalTurns, partialText, errorMessage, startMic, playScenario, stop };
}
