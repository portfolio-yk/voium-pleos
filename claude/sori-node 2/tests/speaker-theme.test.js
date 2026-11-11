import test from "node:test";
import assert from "node:assert/strict";
import { NEUTRAL_THEME, SPEAKER_THEMES, SpeakerThemeController } from "../public/speaker-theme.js";

function setup(options = {}) {
  let now = 0;
  let nextId = 0;
  const timers = new Map();
  const emitted = [];
  const controller = new SpeakerThemeController({
    onChange: (theme) => emitted.push(theme),
    setTimer: (callback, delay) => {
      const id = nextId++;
      timers.set(id, { callback, due: now + delay });
      return id;
    },
    clearTimer: (id) => timers.delete(id),
    ...options,
  });
  return {
    controller, emitted, timers,
    advance(ms) {
      const targetTime = now + ms;
      while (true) {
        const next = [...timers.entries()].filter(([, timer]) => timer.due <= targetTime)
          .sort((a, b) => a[1].due - b[1].due)[0];
        if (!next) break;
        now = next[1].due;
        timers.delete(next[0]);
        next[1].callback();
      }
      now = targetTime;
    },
    update(speakerClass, provisional = false, extra = {}) {
      controller.update({ speakerClass, provisional, hasContent: true, followLatest: true, ...extra });
    },
  };
}

test("the shared palette and each theme are immutable", () => {
  assert.equal(SPEAKER_THEMES.length, 6);
  assert.ok(Object.isFrozen(SPEAKER_THEMES));
  for (const theme of [NEUTRAL_THEME, ...SPEAKER_THEMES]) {
    assert.ok(Object.isFrozen(theme));
    assert.match(theme.background, /^#[0-9a-f]{6}$/);
    assert.match(theme.accent, /^#[0-9a-f]{6}$/);
  }
  assert.equal(new Set(SPEAKER_THEMES.map(({ className }) => className)).size, 6);
});

test("an empty conversation starts neutral and does not repeatedly emit", () => {
  const state = setup();
  state.controller.update();
  state.controller.update({ speakerClass: "speaker-one", hasContent: false });
  assert.deepEqual(state.emitted, [NEUTRAL_THEME]);
  assert.equal(state.timers.size, 0);
});

test("frequent partial words retain the original identity-change deadline", () => {
  const state = setup();
  state.update("speaker-one", true);
  state.advance(100);
  state.update("speaker-one", true);
  state.advance(79);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME]);
  assert.equal(state.timers.size, 1);
  state.advance(1);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME, SPEAKER_THEMES[0]]);
  state.update("speaker-one", true);
  state.update("speaker-one");
  state.advance(500);
  assert.equal(state.emitted.length, 2);
  assert.equal(state.timers.size, 0);
});

test("a retracted provisional identity cannot change the background later", () => {
  const state = setup();
  state.update("speaker-one");
  state.update("speaker-two", true);
  state.advance(100);
  state.update("speaker-one", true);
  state.advance(500);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME, SPEAKER_THEMES[0]]);
  assert.equal(state.timers.size, 0);
});

test("a different provisional speaker gets a fresh confirmation interval", () => {
  const state = setup();
  state.update("speaker-one", true);
  state.advance(100);
  state.update("speaker-two", true);
  state.advance(80);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME]);
  state.advance(100);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME, SPEAKER_THEMES[1]]);
});

test("final recognition bypasses the delay and cancels the previous partial", () => {
  const state = setup();
  state.update("speaker-one", true);
  state.advance(40);
  state.update("speaker-two");
  assert.deepEqual(state.emitted, [NEUTRAL_THEME, SPEAKER_THEMES[1]]);
  state.advance(500);
  assert.equal(state.emitted.length, 2);
  state.update("speaker-three", true);
  state.update("speaker-three");
  assert.equal(state.emitted.at(-1), SPEAKER_THEMES[2]);
  assert.equal(state.timers.size, 0);
});

test("reading history is immediately neutral and resuming restores the current speaker", () => {
  const state = setup();
  state.update("speaker-one");
  state.update("speaker-two", true);
  state.update("speaker-two", true, { followLatest: false });
  assert.equal(state.emitted.at(-1), NEUTRAL_THEME);
  state.advance(500);
  state.update("speaker-two", false, { followLatest: false });
  assert.equal(state.emitted.length, 3);
  state.update("speaker-two");
  assert.equal(state.emitted.at(-1), SPEAKER_THEMES[1]);
});

test("unknown, null and unrecognized speakers never borrow the previous identity", () => {
  const state = setup();
  for (const speakerClass of ["speaker-unknown", null, "unexpected-identity"]) {
    state.update("speaker-one");
    state.update("speaker-two", true);
    state.update(speakerClass, true);
    assert.equal(state.emitted.at(-1), NEUTRAL_THEME);
    state.advance(500);
    assert.equal(state.emitted.at(-1), NEUTRAL_THEME);
  }
  state.update("speaker-one");
  state.update("speaker-unknown");
  assert.equal(state.emitted.at(-1), NEUTRAL_THEME);
});

test("reset cancels pending changes, restores neutral and permits a new conversation", () => {
  const state = setup();
  state.update("speaker-one");
  state.update("speaker-two", true);
  state.controller.reset();
  state.advance(500);
  assert.deepEqual(state.emitted, [NEUTRAL_THEME, SPEAKER_THEMES[0], NEUTRAL_THEME]);
  state.controller.reset();
  assert.equal(state.emitted.length, 3);
  state.update("speaker-three");
  assert.equal(state.emitted.at(-1), SPEAKER_THEMES[2]);
});

test("dispose cancels timers and ignores future updates and resets", () => {
  const state = setup();
  state.update("speaker-one", true);
  state.controller.dispose();
  state.advance(500);
  state.update("speaker-two");
  state.controller.reset();
  assert.deepEqual(state.emitted, [NEUTRAL_THEME]);
  assert.equal(state.timers.size, 0);
});

test("even a callback already queued before cancellation is ignored", () => {
  const callbacks = [];
  const emitted = [];
  const controller = new SpeakerThemeController({
    onChange: (theme) => emitted.push(theme),
    setTimer: (callback) => { callbacks.push(callback); return callbacks.length; },
    clearTimer: () => {},
  });
  controller.update({ speakerClass: "speaker-one", provisional: true, hasContent: true });
  controller.update({ speakerClass: "speaker-two", hasContent: true });
  callbacks[0]();
  assert.deepEqual(emitted, [NEUTRAL_THEME, SPEAKER_THEMES[1]]);
  controller.update({ speakerClass: "speaker-three", provisional: true, hasContent: true });
  controller.dispose();
  callbacks[1]();
  assert.equal(emitted.length, 2);
});

test("default timers retain their browser global receiver for scheduling and cancellation", () => {
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const scheduled = new Map();
  const emitted = [];
  let nextId = 0;
  let cancelled = 0;
  try {
    globalThis.setTimeout = function (callback, delay) {
      assert.equal(this, globalThis, "setTimeout must receive the browser global object");
      assert.equal(delay, 180);
      const id = nextId++;
      scheduled.set(id, callback);
      return id;
    };
    globalThis.clearTimeout = function (id) {
      assert.equal(this, globalThis, "clearTimeout must receive the browser global object");
      cancelled += 1;
      scheduled.delete(id);
    };
    const controller = new SpeakerThemeController({ onChange: (theme) => emitted.push(theme) });
    controller.update({ speakerClass: "speaker-one", provisional: true, hasContent: true });
    assert.equal(scheduled.size, 1);
    controller.update({ speakerClass: "speaker-two", hasContent: true });
    assert.equal(cancelled, 1);
    assert.equal(scheduled.size, 0);
    assert.equal(emitted.at(-1), SPEAKER_THEMES[1]);
    controller.update({ speakerClass: "speaker-three", provisional: true, hasContent: true });
    const callback = [...scheduled.values()][0];
    scheduled.clear();
    callback();
    assert.equal(emitted.at(-1), SPEAKER_THEMES[2]);
    controller.update({ speakerClass: "speaker-four", provisional: true, hasContent: true });
    controller.dispose();
    assert.equal(cancelled, 2);
    assert.equal(scheduled.size, 0);
  } finally {
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
  }
});
