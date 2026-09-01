// 백엔드 application.yaml의 guardline.assemblyai.sample-rate와 반드시 같아야 한다.
export const TARGET_SAMPLE_RATE = 16000;

/** 16kHz 기준 128ms. 업스트림이 권장하는 청크 크기(4KB 안팎)와 맞춘다. */
export const CHUNK_SAMPLES = 2048;

/**
 * Float32 PCM(-1.0 ~ 1.0)을 16bit little-endian PCM으로 바꾼다.
 * 범위를 벗어난 샘플은 잘라내야(clamp) 반대 부호로 감기는 왜곡이 생기지 않는다.
 */
export function floatToPcm16(samples) {
  const buffer = new ArrayBuffer(samples.length * 2);
  const view = new DataView(buffer);

  for (let i = 0; i < samples.length; i += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(i * 2, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
  }
  return buffer;
}

/**
 * 임의 샘플레이트의 오디오 파일을 16kHz 모노 Float32로 변환한다.
 * OfflineAudioContext가 리샘플링을 대신 해주므로 보간을 직접 구현하지 않는다.
 */
export async function decodeToMono16k(arrayBuffer) {
  const decodeContext = new AudioContext();
  let decoded;
  try {
    decoded = await decodeContext.decodeAudioData(arrayBuffer);
  } finally {
    await decodeContext.close();
  }

  const frames = Math.ceil(decoded.duration * TARGET_SAMPLE_RATE);
  const offline = new OfflineAudioContext(1, frames, TARGET_SAMPLE_RATE);
  const source = offline.createBufferSource();

  source.buffer = decoded;
  source.connect(offline.destination);
  source.start();

  const rendered = await offline.startRendering();
  return rendered.getChannelData(0);
}
