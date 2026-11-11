/** A subdued cabin palette. Caption text keeps its own high-contrast color. */
export const NEUTRAL_THEME = Object.freeze({
  key: "neutral",
  className: "speaker-unknown",
  background: "#101216",
  accent: "#909aa6",
});

export const SPEAKER_THEMES = Object.freeze([
  { key: "one", className: "speaker-one", background: "#101f2d", accent: "#9cc9ee" },
  { key: "two", className: "speaker-two", background: "#2b1d18", accent: "#e7bd97" },
  { key: "three", className: "speaker-three", background: "#211b2d", accent: "#c6b5ec" },
  { key: "four", className: "speaker-four", background: "#142820", accent: "#a7ccba" },
  { key: "five", className: "speaker-five", background: "#292614", accent: "#d9cf97" },
  { key: "six", className: "speaker-six", background: "#2d1b27", accent: "#e1aec5" },
].map(Object.freeze));

const themeByClass = new Map(SPEAKER_THEMES.map((theme) => [theme.className, theme]));

/**
 * Delays tentative identity changes without delaying committed recognition.
 * Repeated partial words retain the original deadline, so a long utterance
 * cannot indefinitely postpone its speaker color.
 */
export class SpeakerThemeController {
  #onChange;
  #delay;
  #setTimer;
  #clearTimer;
  #current = NEUTRAL_THEME;
  #pending = null;
  #timer = null;
  #generation = 0;
  #disposed = false;

  constructor({
    onChange = () => {},
    delay = 180,
    // Browser timer methods require their global receiver, including in WKWebView.
    setTimer = (callback, ms) => globalThis.setTimeout(callback, ms),
    clearTimer = (id) => globalThis.clearTimeout(id),
  } = {}) {
    this.#onChange = onChange;
    this.#delay = Math.max(0, Number.isFinite(delay) ? delay : 180);
    this.#setTimer = setTimer;
    this.#clearTimer = clearTimer;
    this.#onChange(NEUTRAL_THEME);
  }

  update({ speakerClass = null, provisional = false, followLatest = true, hasContent = false } = {}) {
    if (this.#disposed) return;
    const target = hasContent && followLatest
      ? themeByClass.get(speakerClass) ?? NEUTRAL_THEME
      : NEUTRAL_THEME;

    if (target === this.#current) {
      this.#cancelPending();
      return;
    }

    // Neutral is immediate: an unidentified speaker must never inherit a known
    // speaker's identity, and a reader browsing history needs a neutral canvas.
    if (target === NEUTRAL_THEME || !provisional || this.#delay === 0) {
      this.#cancelPending();
      this.#commit(target);
      return;
    }

    if (target === this.#pending) return;
    this.#cancelPending();
    this.#pending = target;
    const generation = this.#generation;
    this.#timer = this.#setTimer(() => {
      if (this.#disposed || generation !== this.#generation) return;
      this.#pending = null;
      this.#timer = null;
      this.#commit(target);
    }, this.#delay);
  }

  reset() {
    if (this.#disposed) return;
    this.#cancelPending();
    this.#commit(NEUTRAL_THEME);
  }

  dispose() {
    this.#cancelPending();
    this.#disposed = true;
  }

  #cancelPending() {
    if (this.#timer !== null) this.#clearTimer(this.#timer);
    this.#timer = null;
    this.#pending = null;
    this.#generation += 1;
  }

  #commit(theme) {
    if (theme === this.#current) return;
    this.#current = theme;
    this.#onChange(theme);
  }
}
