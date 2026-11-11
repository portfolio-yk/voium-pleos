/* Mono, little-endian signed PCM. The output remains silent; audio is sent only through the port. */
class SpeechmaticsAudioProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.samples = new Float32Array(2048);
    this.length = 0;
    this.active = true;
    this.port.onmessage = ({ data }) => {
      if (data?.type === "stop") {
        this.active = false;
        this.flush();
        this.port.postMessage({ type: "stopped" });
      }
    };
  }

  flush() {
    if (!this.length) return;
    const buffer = new ArrayBuffer(this.length * 2);
    const view = new DataView(buffer);
    let squareSum = 0;
    for (let index = 0; index < this.length; index += 1) {
      const sample = Math.max(-1, Math.min(1, this.samples[index]));
      view.setInt16(index * 2, sample < 0 ? sample * 32768 : sample * 32767, true);
      squareSum += sample * sample;
    }
    const rms = Math.sqrt(squareSum / this.length);
    this.port.postMessage({ type: "audio", buffer, level: Math.min(1, rms * 4) }, [buffer]);
    this.length = 0;
  }

  process(inputs, outputs) {
    for (const output of outputs) for (const channel of output) channel.fill(0);
    if (!this.active) return false;
    const channels = inputs[0];
    if (!channels?.length) return true;
    const frameLength = channels[0].length;
    for (let frame = 0; frame < frameLength; frame += 1) {
      let mono = 0;
      for (const channel of channels) mono += channel[frame] || 0;
      this.samples[this.length++] = mono / channels.length;
      if (this.length === this.samples.length) this.flush();
    }
    return true;
  }
}

registerProcessor("speechmatics-audio-processor", SpeechmaticsAudioProcessor);
