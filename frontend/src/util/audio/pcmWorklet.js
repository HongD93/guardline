// AudioWorklet 프로세서. 오디오 스레드에서 돌기 때문에 import를 쓰지 않는다.
// 128 프레임짜리 블록이 계속 들어오므로 모아서 청크 단위로 메인 스레드에 넘긴다.

const CHUNK_SAMPLES = 2048; // 16kHz 기준 128ms

class PcmCollector extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(CHUNK_SAMPLES);
    this.filled = 0;
  }

  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) {
      return true;
    }

    let offset = 0;
    while (offset < channel.length) {
      const room = CHUNK_SAMPLES - this.filled;
      const take = Math.min(room, channel.length - offset);

      this.buffer.set(channel.subarray(offset, offset + take), this.filled);
      this.filled += take;
      offset += take;

      if (this.filled === CHUNK_SAMPLES) {
        // 전송 후 버퍼가 detach되므로 매번 복사본을 넘긴다
        const chunk = this.buffer.slice();
        this.port.postMessage(chunk, [chunk.buffer]);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('pcm-collector', PcmCollector);
