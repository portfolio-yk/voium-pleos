import test from "node:test";
import assert from "node:assert/strict";
import { ConversationTranscript } from "../public/conversation.js";

const segment = (id, speaker, text, start = 0, end = start + 1) => ({ id, speaker, text, start, end });

test("one speaker remains one paragraph across multiple recognition finals", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "다음 교차로에서", 1, 2)]);
  conversation.addFinal([segment("b", "S1", "오른쪽으로", 2, 3)]);
  conversation.addFinal([segment("c", "S1", "가면 돼요.", 3, 5)]);
  assert.deepEqual(conversation.getTurns(), [{
    id: "turn-0", speaker: "S1", text: "다음 교차로에서 오른쪽으로 가면 돼요.",
    finalText: "다음 교차로에서 오른쪽으로 가면 돼요.", partialText: "", start: 1, end: 5, isPartial: false,
  }]);
});

test("S1 to S2 to S1 creates three distinct turns, including within one batch", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([
    segment("a", "S1", "여기에서 내려요.", 0),
    segment("b", "S2", "조금 더 가도 돼요.", 1),
    segment("c", "S2", "입구가 저 앞이에요.", 2),
    segment("d", "S1", "네, 알겠어요.", 3),
  ]);
  assert.deepEqual(conversation.getTurns().map(({ id, speaker, text }) => [id, speaker, text]), [
    ["turn-0", "S1", "여기에서 내려요."],
    ["turn-1", "S2", "조금 더 가도 돼요. 입구가 저 앞이에요."],
    ["turn-2", "S1", "네, 알겠어요."],
  ]);
});

test("interim revisions replace words and final recognition never duplicates them", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "음악", 0)]);
  conversation.setPartial([segment("p", "S1", "소리", 1)]);
  assert.equal(conversation.getTurns()[0].text, "음악 소리");
  conversation.setPartial([segment("p", "S1", "소리를 조금 줄여", 1, 3)]);
  assert.equal(conversation.getTurns()[0].text, "음악 소리를 조금 줄여");
  conversation.addFinal([segment("b", "S1", "소리를 조금 줄여 주세요.", 1, 4)]);
  const [turn] = conversation.getTurns();
  assert.equal(turn.text, "음악 소리를 조금 줄여 주세요.");
  assert.equal(turn.partialText, "");
  assert.equal(turn.isPartial, false);
  assert.equal(turn.id, "turn-0");
});

test("a provisional speaker change can be retracted without committing an extra turn", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "창문을", 0)]);
  conversation.setPartial([segment("p", "S2", "닫을까요?", 1)]);
  assert.equal(conversation.getTurns().length, 2);
  conversation.setPartial([segment("p", "S1", "닫을까요?", 1)]);
  assert.equal(conversation.getTurns().length, 1);
  conversation.clearPartial();
  assert.equal(conversation.getTurns()[0].text, "창문을");
  conversation.addFinal([segment("b", "S1", "닫을까요?", 1)]);
  assert.equal(conversation.getTurns().length, 1);
});

test("new speaker IDs are stable from partial through final when order is retained", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "곧 도착해요.", 0)]);
  conversation.setPartial([segment("p", "S2", "네", 1)]);
  const provisionalId = conversation.getTurns()[1].id;
  conversation.setPartial([segment("p", "S2", "네, 고마워요", 1, 2)]);
  assert.equal(conversation.getTurns()[1].id, provisionalId);
  conversation.addFinal([segment("b", "S2", "네, 고마워요.", 1, 3)]);
  assert.equal(conversation.getTurns()[1].id, provisionalId);
});

test("each interim speaker split is projected without modifying completed turns", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "오늘은", 0)]);
  conversation.setPartial([segment("p1", "S1", "날씨가 좋아요.", 1), segment("p2", "S2", "산책할까요?", 2)]);
  assert.deepEqual(conversation.getTurns().map((turn) => [turn.finalText, turn.partialText, turn.isPartial]), [
    ["오늘은", "날씨가 좋아요.", true], ["", "산책할까요?", true],
  ]);
  conversation.setPartial([]);
  assert.deepEqual(conversation.getTurns().map((turn) => turn.text), ["오늘은"]);
});

test("duplicate final IDs are ignored while identical words with new IDs remain", () => {
  const conversation = new ConversationTranscript();
  const repeatedCallback = [segment("a", "S1", "네", 0)];
  conversation.addFinal(repeatedCallback);
  conversation.addFinal(repeatedCallback);
  conversation.addFinal([segment("b", "S1", "네", 1)]);
  assert.equal(conversation.getTurns()[0].text, "네 네");
});

test("punctuation delivered at batch boundaries attaches without artificial spaces", () => {
  const conversation = new ConversationTranscript();
  for (const [index, text] of ["반가워요", ",", "정말", "좋네요", ".", "“", "출발", "”"].entries()) {
    conversation.addFinal([segment(String(index), "S1", text, index)]);
  }
  assert.equal(conversation.getTurns()[0].text, "반가워요, 정말 좋네요. “출발”");
});

test("unknown speakers stay unidentified and malformed data cannot create empty turns", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([null, {}, segment("blank", "S1", "  "), segment("a", "S1", "네"), segment("b", undefined, "누구죠?", -1, Number.NaN)]);
  const turns = conversation.getTurns();
  assert.deepEqual(turns.map((turn) => turn.speaker), ["S1", "UU"]);
  assert.equal(turns[1].start, 0);
  assert.equal(turns[1].end, 0);
  turns[0].text = "external mutation";
  assert.equal(conversation.getTurns()[0].text, "네");
});

test("reset removes partials, final deduplication history and turn IDs", () => {
  const conversation = new ConversationTranscript();
  conversation.addFinal([segment("a", "S1", "이전 대화")]);
  conversation.setPartial([segment("p", "S2", "미완성")]);
  conversation.reset();
  assert.deepEqual(conversation.getTurns(), []);
  conversation.addFinal([segment("a", "S1", "새 대화")]);
  assert.equal(conversation.getTurns()[0].id, "turn-0");
  assert.equal(conversation.getTurns()[0].text, "새 대화");
});
