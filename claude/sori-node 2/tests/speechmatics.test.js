import test from "node:test";
import assert from "node:assert/strict";
import { setImmediate as nextTurn } from "node:timers/promises";
import { SpeechmaticsSession } from "../public/speechmatics.js";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function setup(t, overrides = {}) {
  const events = [];
  const sockets = [];
  const contexts = [];
  const worklets = [];
  const sessions = [];
  const track = { stopped: false, onended: null, stop() { this.stopped = true; events.push("microphone:stop"); } };
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
  class AudioContext {
    constructor() {
      this.sampleRate = 48000;
      this.destination = {};
      this.closed = false;
      this.audioWorklet = { addModule: async (url) => { events.push(`worklet:${url}`); } };
      contexts.push(this);
    }
    resume() { events.push("audio:resume"); return Promise.resolve(); }
    close() { this.closed = true; events.push("audio:close"); return Promise.resolve(); }
    createMediaStreamSource() {
      return {
        connect() { events.push("microphone:connect"); },
        disconnect() { events.push("microphone:disconnect"); },
      };
    }
    createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} }; }
  }
  class AudioWorkletNode {
    constructor() {
      this.port = {
        onmessage: null,
        closed: false,
        close() { this.closed = true; },
        postMessage: (message) => {
          if (message.type === "stop") {
            events.push("worklet:flush");
            this.port.onmessage?.({ data: { type: "audio", buffer: new ArrayBuffer(2), level: 0.1 } });
            this.port.onmessage?.({ data: { type: "stopped" } });
          }
        },
      };
      worklets.push(this);
    }
    connect() {}
    disconnect() {}
  }
  class Socket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 3;
    constructor(url) {
      this.url = url;
      this.readyState = Socket.CONNECTING;
      this.bufferedAmount = 0;
      this.sent = [];
      sockets.push(this);
      events.push("socket:create");
    }
    open() { this.readyState = Socket.OPEN; this.onopen?.(); }
    receive(message) { this.onmessage?.({ data: typeof message === "string" ? message : JSON.stringify(message) }); }
    send(value) { this.sent.push(value); events.push(value instanceof ArrayBuffer ? "socket:audio" : JSON.parse(value).message); }
    close() { this.readyState = Socket.CLOSED; events.push("socket:close"); this.onclose?.(); }
  }
  const globals = {
    window: { isSecureContext: true, AudioContext, AudioWorkletNode },
    navigator: { mediaDevices: { getUserMedia: async () => { events.push("microphone:request"); return stream; } } },
    WebSocket: Socket,
    ...overrides,
  };
  const originalDescriptors = new Map();
  for (const [name, value] of Object.entries(globals)) {
    originalDescriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
  }
  t.after(() => {
    for (const session of sessions) session.dispose();
    for (const [name, descriptor] of originalDescriptors) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  });

  function create(options = {}) {
    const states = [];
    const errors = [];
    const finals = [];
    const partials = [];
    const levels = [];
    const session = new SpeechmaticsSession({
      token: "short-lived-jwt",
      language: "ko",
      diarization: true,
      maxDelay: 1.5,
      onState: (state) => states.push(state),
      onError: (message) => errors.push(message),
      onFinal: (segments) => finals.push(segments),
      onPartial: (segments) => partials.push(segments),
      onLevel: (level) => levels.push(level),
      ...options,
    });
    sessions.push(session);
    return { session, states, errors, finals, partials, levels };
  }

  async function start(fixture) {
    const starting = fixture.session.start();
    await nextTurn();
    const socket = sockets.at(-1);
    assert.ok(socket, "connection is created after audio setup");
    socket.open();
    socket.receive({ message: "RecognitionStarted", language_pack_info: { word_delimiter: " " } });
    await starting;
    return socket;
  }
  return { create, start, events, sockets, contexts, worklets, track, stream, globals };
}

test("start resumes audio during the click and obtains JWT after microphone/worklet setup", async (t) => {
  const env = setup(t);
  const fixture = env.create({ token: async () => { env.events.push("token:request"); return "a+b/c"; } });
  const starting = fixture.session.start();
  assert.deepEqual(env.events, ["audio:resume"]);
  assert.equal(fixture.session.start(), starting);
  await nextTurn();
  assert.deepEqual(env.events.slice(0, 5), ["audio:resume", "microphone:request", "worklet:/audio-processor.js", "token:request", "socket:create"]);
  const socket = env.sockets[0];
  assert.equal(socket.url, "wss://global.rt.speechmatics.com/v2?jwt=a%2Bb%2Fc");
  socket.open();
  const config = JSON.parse(socket.sent[0]);
  assert.deepEqual(config.audio_format, { type: "raw", encoding: "pcm_s16le", sample_rate: 48000 });
  assert.equal(config.transcription_config.model, "enhanced");
  assert.equal(config.transcription_config.diarization, "speaker");
  assert.equal(config.transcription_config.enable_partials, true);
  assert.deepEqual(config.transcription_config.speaker_diarization_config, { prefer_current_speaker: true });
  assert.ok(!env.events.includes("microphone:connect"));
  socket.receive({ message: "RecognitionStarted" });
  await starting;
  assert.deepEqual(fixture.states, ["connecting", "recording"]);
  assert.ok(env.events.includes("microphone:connect"));
});

test("stop sends tail audio before EndOfStream and waits for final transcript then EOT", async (t) => {
  const env = setup(t);
  const fixture = env.create();
  const socket = await env.start(fixture);
  env.worklets[0].port.onmessage({ data: { type: "audio", buffer: new ArrayBuffer(4), level: 0.5 } });
  const stopping = fixture.session.stop();
  assert.equal(fixture.session.stop(), stopping);
  await nextTurn();
  assert.ok(env.track.stopped, "microphone is released while remaining final results arrive");
  assert.equal(socket.readyState, WebSocket.OPEN);
  assert.deepEqual(JSON.parse(socket.sent.at(-1)), { message: "EndOfStream", last_seq_no: 2 });
  assert.ok(env.events.indexOf("worklet:flush") < env.events.indexOf("EndOfStream"));
  socket.receive({ message: "AddTranscript", results: [{ type: "word", start_time: 0, end_time: 1, alternatives: [{ content: "마지막", speaker: "S2" }] }] });
  assert.equal(fixture.finals.at(-1)[0].text, "마지막");
  assert.equal(fixture.finals.at(-1)[0].speaker, "S2");
  assert.equal(fixture.states.at(-1), "stopping");
  socket.receive({ message: "EndOfTranscript" });
  await stopping;
  assert.equal(fixture.states.at(-1), "stopped");
  assert.equal(socket.readyState, WebSocket.CLOSED);
  assert.ok(env.contexts[0].closed);
  assert.ok(env.worklets[0].port.closed);
  assert.deepEqual(fixture.errors, []);
});

test("partial results replace earlier batches and finals clear the partial callback", async (t) => {
  const env = setup(t);
  const fixture = env.create({ diarization: false });
  const socket = await env.start(fixture);
  socket.receive({ message: "AddPartialTranscript", metadata: { transcript: "반가" } });
  socket.receive({ message: "AddPartialTranscript", metadata: { transcript: "반갑습니다" } });
  assert.equal(fixture.partials.length, 2);
  assert.equal(fixture.partials.at(-1)[0].text, "반갑습니다");
  assert.equal(fixture.partials.at(-1)[0].speaker, "S1");
  socket.receive({ message: "AddTranscript", metadata: { transcript: "반갑습니다." } });
  assert.deepEqual(fixture.partials.at(-1), []);
  assert.equal(fixture.finals.at(-1)[0].text, "반갑습니다.");
  assert.equal(JSON.parse(socket.sent[0]).transcription_config.diarization, "none");
});

test("cancel while the microphone prompt is pending releases a late microphone stream", async (t) => {
  const microphone = deferred();
  const env = setup(t, { navigator: { mediaDevices: { getUserMedia: () => microphone.promise } } });
  const fixture = env.create();
  const starting = fixture.session.start();
  const rejected = assert.rejects(starting, { name: "AbortError" });
  await nextTurn();
  await fixture.session.stop();
  assert.ok(env.contexts[0].closed);
  microphone.resolve(env.stream);
  await rejected;
  assert.ok(env.track.stopped);
  assert.equal(env.sockets.length, 0);
  assert.deepEqual(fixture.errors, []);
});

test("microphone failure during JWT fetch cannot reopen a cleaned-up session", async (t) => {
  const token = deferred();
  const env = setup(t);
  const fixture = env.create({ token: () => token.promise });
  const starting = fixture.session.start();
  const rejected = assert.rejects(starting, { name: "AbortError" });
  await nextTurn();
  env.track.onended();
  assert.equal(fixture.states.at(-1), "stopped");
  token.resolve("jwt");
  await rejected;
  assert.equal(env.sockets.length, 0);
  assert.equal(fixture.errors.length, 1);
  assert.ok(env.track.stopped);
});

test("invalid server message fails promptly and cleans all audio resources", async (t) => {
  const env = setup(t);
  const fixture = env.create();
  const starting = fixture.session.start();
  const rejected = assert.rejects(starting, /올바르지 않은 응답/);
  await nextTurn();
  env.sockets[0].open();
  env.sockets[0].receive("null");
  await rejected;
  assert.equal(fixture.states.at(-1), "stopped");
  assert.equal(fixture.errors.length, 1);
  assert.ok(env.track.stopped);
  assert.ok(env.contexts[0].closed);
});

test("EndOfTranscript during connection rejects start instead of leaving it pending", async (t) => {
  const env = setup(t);
  const fixture = env.create();
  const starting = fixture.session.start();
  const rejected = assert.rejects(starting, /세션이 종료/);
  await nextTurn();
  env.sockets[0].open();
  env.sockets[0].receive({ message: "EndOfTranscript" });
  await rejected;
  assert.equal(fixture.states.at(-1), "stopped");
});

test("invalid max delay is normalized to a valid provider setting", async (t) => {
  const env = setup(t);
  const fixture = env.create({ maxDelay: Number.NaN });
  const socket = await env.start(fixture);
  assert.equal(JSON.parse(socket.sent[0]).transcription_config.max_delay, 1.5);
});
