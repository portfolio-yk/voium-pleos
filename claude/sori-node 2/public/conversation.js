/**
 * A conversation is a sequence of speaker turns, not recognition API batches.
 * Interim recognition is a replaceable projection over the committed turns.
 */

const finiteTime = (value, fallback = 0) =>
  typeof value === "number" && Number.isFinite(value) ? Math.max(0, value) : fallback;

function normalize(segment) {
  if (!segment || typeof segment.text !== "string") return null;
  const text = segment.text.replace(/\s+/g, " ").trim();
  if (!text) return null;
  const start = finiteTime(segment.start);
  return {
    sourceId: typeof segment.id === "string" && segment.id ? segment.id : null,
    speaker: typeof segment.speaker === "string" && segment.speaker ? segment.speaker : "UU",
    text,
    start,
    end: Math.max(start, finiteTime(segment.end, start)),
  };
}

function joinText(previous, next, delimiter) {
  if (!previous) return next;
  if (!next) return previous;
  // Recognition can deliver punctuation separately at a final-batch boundary.
  const attachesPrevious = /^[,.;:!?…。，、！？：；%‰°℃℉)\]}’”」』》〉]/u.test(next);
  const attachesNext = /[([{“‘「『《〈]$/u.test(previous);
  return previous + (attachesPrevious || attachesNext ? "" : delimiter) + next;
}

export class ConversationTranscript {
  #turns = [];
  #partials = [];
  #finalIds = new Set();
  #delimiter;

  constructor({ wordDelimiter = " " } = {}) {
    this.#delimiter = wordDelimiter;
  }

  /** Append immutable recognition, merging only adjacent identical speakers. */
  addFinal(segments) {
    this.clearPartial();
    for (const source of Array.isArray(segments) ? segments : []) {
      const segment = normalize(source);
      if (!segment || (segment.sourceId && this.#finalIds.has(segment.sourceId))) continue;
      if (segment.sourceId) this.#finalIds.add(segment.sourceId);
      this.#append(this.#turns, segment, false);
    }
  }

  /** Replace the entire interim batch, including any provisional speaker split. */
  setPartial(segments) {
    this.#partials = (Array.isArray(segments) ? segments : []).map(normalize).filter(Boolean);
  }

  clearPartial() {
    this.#partials = [];
  }

  /** Fresh objects prevent rendering or exports from mutating recognition state. */
  getTurns() {
    const projection = this.#turns.map((turn) => ({ ...turn }));
    for (const segment of this.#partials) this.#append(projection, segment, true);
    return projection;
  }

  reset() {
    this.#turns = [];
    this.#partials = [];
    this.#finalIds.clear();
  }

  #append(turns, segment, partial) {
    let turn = turns.at(-1);
    if (!turn || turn.speaker !== segment.speaker) {
      turn = {
        id: `turn-${turns.length}`,
        speaker: segment.speaker,
        text: "",
        finalText: "",
        partialText: "",
        start: segment.start,
        end: segment.end,
        isPartial: false,
      };
      turns.push(turn);
    }
    const field = partial ? "partialText" : "finalText";
    turn[field] = joinText(turn[field], segment.text, this.#delimiter);
    turn.text = joinText(turn.finalText, turn.partialText, this.#delimiter);
    turn.start = Math.min(turn.start, segment.start);
    turn.end = Math.max(turn.end, segment.end);
    turn.isPartial = Boolean(turn.partialText);
  }
}
