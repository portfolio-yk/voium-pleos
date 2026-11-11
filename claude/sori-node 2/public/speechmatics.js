import { parseTranscript } from "./transcript.js";

function abortError() {
  return new DOMException("연결이 취소되었습니다.", "AbortError");
}

function errorMessage(error) {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "SecurityError") return "마이크 사용 권한을 허용한 뒤 다시 시작해 주세요.";
    if (error.name === "NotFoundError") return "연결된 마이크를 찾을 수 없습니다.";
    if (error.name === "NotReadableError") return "마이크를 사용할 수 없습니다. 다른 앱에서 사용 중인지 확인해 주세요.";
    if (error.name === "OverconstrainedError") return "선택한 마이크를 사용할 수 없습니다. 다른 입력 장치를 선택해 주세요.";
  }
  return error instanceof Error ? error.message : "음성 인식 연결 중 오류가 발생했습니다.";
}

/**
 * A single microphone recording. Create a new instance for each recording.
 * token is a short-lived JWT or an async provider. Call start() from a click
 * handler so AudioContext.resume() runs within the browser's user activation.
 */
export class SpeechmaticsSession {
  constructor(options) {
    this.options = options;
    this.state = "stopped";
    this.disposed = false;
    this.cancelled = false;
    this.reportedError = false;
    this.expectedClose = false;
    this.started = false;
    this.endReceived = false;
    this.audioEnded = false;
    this.sentChunks = 0;
    this.acknowledgedChunks = 0;
    this.batch = 0;
    this.resolvedToken = "";
  }

  start() {
    if (this.startPromise) return this.startPromise;
    if (this.disposed || this.started) return Promise.reject(new Error("새 녹음을 위해 새 세션을 만들어 주세요."));
    this.started = true;
    this.startPromise = this.startInternal();
    return this.startPromise;
  }

  async startInternal() {
    this.setState("connecting");
    try {
      if (typeof this.options.token === "string" && !this.options.token.trim()) throw new Error("Speechmatics 임시 토큰이 필요합니다.");
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) throw new Error("마이크를 사용하려면 HTTPS 또는 localhost에서 열어 주세요.");
      if (!window.AudioContext || !window.AudioWorkletNode) throw new Error("이 브라우저는 실시간 오디오를 지원하지 않습니다. 최신 Chrome, Edge 또는 Safari를 사용해 주세요.");

      // Resume before any token fetch, worklet import, or permission prompt.
      const context = new window.AudioContext({ latencyHint: "interactive" });
      this.context = context;
      let resumeTimer;
      try {
        await Promise.race([
          context.resume(),
          new Promise((_, reject) => {
            resumeTimer = setTimeout(() => reject(new Error("브라우저가 마이크 오디오를 시작하지 못했습니다. 이 페이지를 클릭한 뒤 다시 시작해 주세요.")), 10_000);
          }),
        ]);
      } finally {
        if (resumeTimer) clearTimeout(resumeTimer);
      }
      this.checkActive();
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          ...(this.options.deviceId ? { deviceId: { exact: this.options.deviceId } } : {}),
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      });
      if (this.cancelled || this.disposed) {
        stream.getTracks().forEach((track) => track.stop());
        throw abortError();
      }
      this.stream = stream;
      for (const track of stream.getAudioTracks()) {
        track.onended = () => {
          if (this.state === "connecting") {
            this.fail("마이크 연결이 끊어졌습니다. 입력 장치를 확인한 뒤 다시 시작해 주세요.");
          } else if (this.state === "recording") {
            this.reportError("마이크 연결이 끊어졌습니다. 입력 장치를 확인한 뒤 다시 시작해 주세요.");
            void this.stop();
          }
        };
      }
      await context.audioWorklet.addModule("/audio-processor.js");
      this.checkActive();
      this.source = context.createMediaStreamSource(stream);
      this.processor = new window.AudioWorkletNode(context, "speechmatics-audio-processor", {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
      });
      this.silentGain = context.createGain();
      this.silentGain.gain.value = 0;
      this.processor.connect(this.silentGain);
      this.silentGain.connect(context.destination);
      this.processor.port.onmessage = ({ data }) => {
        if (data?.type === "stopped") {
          this.flushResolve?.();
          return;
        }
        if (data?.type !== "audio" || this.audioEnded || this.cancelled || this.disposed) return;
        if (this.state !== "recording" && this.state !== "stopping") return;
        const socket = this.socket;
        if (!socket || socket.readyState !== WebSocket.OPEN) return;
        const buffer = data.buffer;
        if (!(buffer instanceof ArrayBuffer) || !buffer.byteLength) return;
        const maxBufferedBytes = context.sampleRate * 2 * 5;
        if (socket.bufferedAmount + buffer.byteLength > maxBufferedBytes || this.sentChunks - this.acknowledgedChunks > 256) {
          this.fail("네트워크가 느려 음성을 실시간으로 전송할 수 없습니다. 연결을 확인한 뒤 다시 시작해 주세요.");
          return;
        }
        try {
          socket.send(buffer);
          this.sentChunks += 1;
          this.options.onLevel(Math.max(0, Math.min(1, Number(data.level) || 0)));
        } catch {
          this.fail("마이크 음성을 전송하지 못했습니다. 네트워크 연결을 확인해 주세요.");
        }
      };

      // Obtain the JWT after local audio is ready so a permission prompt cannot
      // use up its short validity period.
      const token = typeof this.options.token === "function" ? await this.options.token() : this.options.token;
      this.checkActive();
      if (typeof token !== "string" || !token.trim()) throw new Error("Speechmatics 임시 토큰을 발급하지 못했습니다.");
      this.resolvedToken = token.trim();
      await this.openRecognition(context.sampleRate);
      this.checkActive();
      this.setState("recording");
      // Do not capture or send audio before RecognitionStarted.
      this.source.connect(this.processor);
    } catch (error) {
      if (!this.cancelled && !this.disposed) this.reportError(errorMessage(error));
      this.cleanup();
      this.setState("stopped");
      throw error;
    }
  }

  openRecognition(sampleRate) {
    return new Promise((resolve, reject) => {
      this.recognitionReject = reject;
      this.recognitionTimer = setTimeout(() => this.fail("음성 인식 서버의 응답이 없습니다. 토큰과 네트워크 연결을 확인해 주세요."), 20_000);
      const socket = new WebSocket(`wss://global.rt.speechmatics.com/v2?jwt=${encodeURIComponent(this.resolvedToken)}`);
      this.socket = socket;
      socket.onopen = () => {
        if (this.cancelled || this.disposed) return;
        const requestedDelay = Number(this.options.maxDelay);
        const maxDelay = Number.isFinite(requestedDelay) ? Math.min(4, Math.max(0.7, requestedDelay)) : 1.5;
        try {
          socket.send(JSON.stringify({
            message: "StartRecognition",
            audio_format: { type: "raw", encoding: "pcm_s16le", sample_rate: sampleRate },
            transcription_config: {
              language: this.options.language,
              model: "enhanced",
              diarization: this.options.diarization ? "speaker" : "none",
              enable_partials: true,
              max_delay: maxDelay,
              ...(this.options.diarization ? { speaker_diarization_config: { prefer_current_speaker: true } } : {}),
            },
          }));
        } catch {
          this.fail("음성 인식 설정을 전송하지 못했습니다. 다시 시작해 주세요.");
        }
      };
      socket.onmessage = ({ data }) => {
        if (typeof data !== "string" || this.cancelled || this.disposed) return;
        let message;
        try {
          message = JSON.parse(data);
          if (!message || typeof message !== "object" || Array.isArray(message) || typeof message.message !== "string") throw new Error("Invalid server message");
        } catch {
          this.fail("음성 인식 서버에서 올바르지 않은 응답을 받았습니다.");
          return;
        }
        switch (message.message) {
          case "RecognitionStarted":
            this.wordDelimiter = typeof message.language_pack_info?.word_delimiter === "string" ? message.language_pack_info.word_delimiter : undefined;
            this.clearRecognitionWait();
            resolve();
            break;
          case "AudioAdded":
            if (Number.isFinite(message.seq_no)) this.acknowledgedChunks = Math.max(this.acknowledgedChunks, message.seq_no);
            break;
          case "AddTranscript": {
            const segments = parseTranscript(message, {
              language: this.options.language,
              wordDelimiter: this.wordDelimiter,
              prefix: `final-${this.batch++}`,
              defaultSpeaker: this.options.diarization ? "UU" : "S1",
            });
            this.options.onPartial([]);
            if (segments.length) this.options.onFinal(segments);
            break;
          }
          case "AddPartialTranscript":
            this.options.onPartial(parseTranscript(message, {
              language: this.options.language,
              wordDelimiter: this.wordDelimiter,
              prefix: "partial",
              defaultSpeaker: this.options.diarization ? "UU" : "S1",
            }));
            break;
          case "EndOfTranscript":
            this.endReceived = true;
            this.endResolve?.();
            if (this.state !== "stopping") this.fail("음성 인식 세션이 종료되었습니다. 다시 시작해 주세요.");
            break;
          case "Error":
            this.fail(`Speechmatics ${message.type || "오류"}: ${message.reason || "음성 인식을 완료하지 못했습니다."}`);
            break;
        }
      };
      socket.onerror = () => this.fail("Speechmatics에 연결하지 못했습니다. 임시 토큰의 유효 기간과 네트워크를 확인해 주세요.");
      socket.onclose = () => {
        if (this.expectedClose || this.cancelled || this.disposed) return;
        if (this.endReceived) {
          this.endResolve?.();
          return;
        }
        this.fail("음성 인식 서버와의 연결이 끊어졌습니다. 확정된 자막은 보관되어 있습니다.");
      };
    });
  }

  stop() {
    if (this.stopPromise) return this.stopPromise;
    this.stopPromise = this.stopInternal();
    return this.stopPromise;
  }

  async stopInternal() {
    if (this.state === "stopped") return;
    if (this.state === "connecting") {
      this.cancelled = true;
      this.recognitionReject?.(abortError());
      this.cleanup();
      this.setState("stopped");
      return;
    }
    this.setState("stopping");
    try {
      if (this.processor) {
        await new Promise((resolve) => {
          const timer = setTimeout(resolve, 500);
          this.flushResolve = () => { clearTimeout(timer); resolve(); };
          this.processor?.port.postMessage({ type: "stop" });
        });
      }
      this.audioEnded = true;
      this.stopMicrophone();
      const socket = this.socket;
      if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const end = new Promise((resolve) => { this.endResolve = resolve; });
      // Binary frames and EndOfStream are ordered on the same WebSocket.
      // last_seq_no is the total number of binary audio frames sent.
      socket.send(JSON.stringify({ message: "EndOfStream", last_seq_no: this.sentChunks }));
      let endTimer;
      await Promise.race([
        end,
        new Promise((resolve) => {
          endTimer = setTimeout(() => {
            this.reportError("최종 자막 수신 시간이 초과되었습니다. 마지막 발화 일부가 누락되었을 수 있습니다.");
            resolve();
          }, 12_000);
        }),
      ]);
      if (endTimer) clearTimeout(endTimer);
    } catch (error) {
      this.reportError(errorMessage(error));
    } finally {
      this.cleanup();
      this.setState("stopped");
    }
  }

  /** Immediate navigation cancellation. stop() waits for remaining final words. */
  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.cancelled = true;
    this.recognitionReject?.(abortError());
    this.endResolve?.();
    this.flushResolve?.();
    this.cleanup();
    this.setState("stopped");
  }

  checkActive() {
    if (this.cancelled || this.disposed) throw abortError();
  }

  setState(state) {
    if (this.state === state) return;
    this.state = state;
    this.options.onState(state);
  }

  reportError(message) {
    if (this.reportedError || this.disposed || this.cancelled) return;
    this.reportedError = true;
    this.options.onError(message);
  }

  fail(message) {
    this.reportError(message);
    this.cancelled = true;
    this.recognitionReject?.(new Error(message));
    this.endResolve?.();
    this.flushResolve?.();
    this.cleanup();
    this.setState("stopped");
  }

  clearRecognitionWait() {
    if (this.recognitionTimer) clearTimeout(this.recognitionTimer);
    this.recognitionTimer = undefined;
    this.recognitionReject = undefined;
  }

  stopMicrophone() {
    for (const track of this.stream?.getTracks() ?? []) {
      track.onended = null;
      track.stop();
    }
    this.stream = undefined;
    this.source?.disconnect();
    this.source = undefined;
    this.options.onLevel(0);
  }

  cleanup() {
    this.audioEnded = true;
    this.resolvedToken = "";
    this.clearRecognitionWait();
    this.flushResolve?.();
    this.flushResolve = undefined;
    this.stopMicrophone();
    if (this.processor) {
      this.processor.port.onmessage = null;
      this.processor.port.close();
      this.processor.disconnect();
      this.processor = undefined;
    }
    this.silentGain?.disconnect();
    this.silentGain = undefined;
    if (this.context) {
      void this.context.close().catch(() => {});
      this.context = undefined;
    }
    const socket = this.socket;
    this.socket = undefined;
    this.expectedClose = true;
    if (socket) {
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) socket.close();
    }
    this.endResolve = undefined;
    this.options.onPartial([]);
  }
}
