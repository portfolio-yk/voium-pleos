import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { parseTranscript, exportTxt, exportSrt, formatTime, speakerLabel } from "../public/transcript.js";

const word = (content, speaker, start, end = start + 0.3) => ({
  type: "word", start_time: start, end_time: end, alternatives: [{ content, speaker }],
});
const punctuation = (content, time, attaches_to = "previous") => ({
  type: "punctuation", start_time: time, end_time: time, attaches_to, alternatives: [{ content }],
});

test("Korean spacing and punctuation remain attached to the correct speaker", () => {
  const segments = parseTranscript({
    message: "AddTranscript",
    results: [word("안녕하세요", "S1", 0), punctuation(",", 0.3), word("반갑습니다", "S1", 0.4), punctuation(".", 0.7), word("네", "S2", 1), punctuation(",", 1.3), word("저도요", "S2", 1.4), punctuation("!", 1.7)],
  });
  assert.deepEqual(segments.map(({ speaker, text, start, end }) => ({ speaker, text, start, end })), [
    { speaker: "S1", text: "안녕하세요, 반갑습니다.", start: 0, end: 0.7 },
    { speaker: "S2", text: "네, 저도요!", start: 1, end: 1.7 },
  ]);
});

test("missing speaker labels remain unidentified instead of guessing identity", () => {
  const segments = parseTranscript({ results: [word("확인", "S1", 0), word("불확실", "UU", 1), word("알수없음", undefined, 2), word("응답", "S2", 3)] });
  assert.deepEqual(segments.map(({ speaker, text }) => [speaker, text]), [["S1", "확인"], ["UU", "불확실 알수없음"], ["S2", "응답"]]);
  assert.equal(speakerLabel("UU"), "화자 미확인");
  assert.equal(speakerLabel("S2"), "화자 2");
});

test("opening punctuation follows the next speaker", () => {
  const segments = parseTranscript({ results: [word("말했어요", "S1", 0), punctuation(".", 0.3), punctuation("“", 1, "next"), word("정말", "S2", 1), word("좋아요", "S2", 1.4), punctuation("”", 1.7)] });
  assert.equal(segments[1].text, "“정말 좋아요”");
  assert.equal(segments[1].speaker, "S2");
});

test("language defaults and service-provided word delimiters are respected", () => {
  const message = { results: [word("今日", "S1", 0), word("晴れ", "S1", 1)] };
  assert.equal(parseTranscript(message, { language: "ja" })[0].text, "今日晴れ");
  assert.equal(parseTranscript(message, { language: "ja", wordDelimiter: " " })[0].text, "今日 晴れ");
});

test("partials replace previous batches with stable IDs; final IDs are unique", () => {
  const first = parseTranscript({ message: "AddPartialTranscript", results: [word("회의", "S1", 0)] });
  const revision = parseTranscript({ message: "AddPartialTranscript", results: [word("회의를", "S1", 0), word("시작합니다", "S1", 1)] });
  assert.equal(first[0].id, revision[0].id);
  assert.equal(revision[0].text, "회의를 시작합니다");
  assert.notEqual(parseTranscript({ results: [word("끝", "S1", 0)] }, { prefix: "final-1" })[0].id, first[0].id);
  assert.deepEqual(parseTranscript({ message: "AddPartialTranscript", results: [] }), []);
});

test("metadata-only and written form transcripts are visible, invalid times sanitized", () => {
  const segments = parseTranscript({ metadata: { transcript: "  완료했습니다.  ", start_time: -1, end_time: Number.NaN } });
  assert.deepEqual(segments[0], { id: "final-0-0", speaker: "UU", text: "완료했습니다.", start: 0, end: 0 });
  assert.equal(parseTranscript({ results: [{ written_form: [word("42", "S1", 1)] }] })[0].text, "42");
  assert.deepEqual(parseTranscript({ results: [null, {}, { alternatives: [{}] }] }), []);
});

test("TXT and SRT preserve Korean, custom names, exact timing, and literal markup", () => {
  const segments = [{ id: "a", speaker: "S1", text: "회의를 시작합니다.", start: 0.125, end: 2.789 }, { id: "b", speaker: "UU", text: "<네>", start: 3600, end: 3600 }];
  assert.equal(exportTxt(segments, { S1: "진행자" }), "[00:00] 진행자: 회의를 시작합니다.\n[01:00:00] 화자 미확인: <네>");
  assert.equal(exportSrt(segments, { S1: "진행자" }), "1\n00:00:00,125 --> 00:00:02,789\n진행자: 회의를 시작합니다.\n\n2\n01:00:00,000 --> 01:00:00,040\n화자 미확인: &lt;네&gt;");
  assert.equal(formatTime(65), "01:05");
  assert.equal(formatTime(Number.NaN), "00:00");
});

test("AudioWorklet downmixes PCM16LE, silences output, and flushes tail audio on stop", () => {
  const messages = [];
  let Processor;
  runInNewContext(readFileSync(new URL("../public/audio-processor.js", import.meta.url), "utf8"), {
    AudioWorkletProcessor: class { port = { onmessage: null, postMessage: (value) => messages.push(value) }; },
    registerProcessor: (_name, implementation) => { Processor = implementation; },
  });
  const processor = new Processor();
  const output = new Float32Array([1, 1, 1]);
  processor.process([[new Float32Array([1, -1, 0.5]), new Float32Array([1, -1, -0.5])]], [[output]]);
  assert.deepEqual([...output], [0, 0, 0]);
  assert.equal(messages.length, 0);
  processor.port.onmessage({ data: { type: "stop" } });
  assert.equal(messages[0].type, "audio");
  const view = new DataView(messages[0].buffer);
  assert.deepEqual([view.getInt16(0, true), view.getInt16(2, true), view.getInt16(4, true)], [32767, -32768, 0]);
  assert.equal(messages[1].type, "stopped");
  assert.equal(processor.process([], [[output]]), false);
});
