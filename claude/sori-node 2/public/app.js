import { SpeechmaticsSession } from './speechmatics.js';
import { ConversationTranscript } from './conversation.js';
import { SPEAKER_THEMES, SpeakerThemeController } from './speaker-theme.js';

const $ = (id) => document.getElementById(id);
const viewport = $('caption-window');
const conversation = $('conversation');
const query = new URLSearchParams(location.search);
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
const speakerClasses = SPEAKER_THEMES.map(theme => theme.className);
const rows = new Map();
const speakerNumbers = new Map();
let transcript = new ConversationTranscript();
let phase = 'initializing';
let mode = 'live';
let configured = false;
let alive = true;
let session;
let tokenController;
let generation = 0;
let followLatest = true;
let noticeTimer;
let previewTimer;
let previousTurnCount = 0;
let microphoneHasStarted = false;

const atmosphere = new SpeakerThemeController({
  onChange(theme) {
    // Every opaque surface shares this variable and transition duration.
    // Sticky labels must not become a differently coloured strip over the scene.
    document.documentElement.style.setProperty('--background', theme.background);
    document.documentElement.style.setProperty('--active-accent', theme.accent);
    document.body.dataset.speakerTheme = theme.key;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme.background);
  },
});

function updateAtmosphere(turns = transcript.getTurns()) {
  const latest = turns.at(-1);
  const displayingLiveCaption = (mode === 'preview' || phase === 'recording' || phase === 'stopping')
    && $('error-message').hidden;
  const knownSpeaker = latest && latest.speaker !== 'UU';
  atmosphere.update({
    speakerClass: latest ? speakerInfo(latest.speaker).className : null,
    provisional: latest?.isPartial === true,
    followLatest,
    hasContent: !!latest && displayingLiveCaption,
  });
  for (const [id, row] of rows) {
    row.currentIndicator.hidden = !(displayingLiveCaption && followLatest && knownSpeaker && id === latest.id);
  }
}

// Recognition batches and sentences do not define turns. Only a change of
// speaker starts a new paragraph; partial text is a replaceable projection.
function speakerInfo(speaker) {
  if (speaker === 'UU') return { label: '화자 확인 중', className: 'speaker-unknown' };
  if (!speakerNumbers.has(speaker)) {
    const canonical = /^S(\d+)$/.exec(speaker);
    speakerNumbers.set(speaker, canonical ? Number(canonical[1]) : speakerNumbers.size + 1);
  }
  const number = speakerNumbers.get(speaker);
  return { label: `화자 ${number}`, className: speakerClasses[(number - 1) % speakerClasses.length] };
}

function makeRow(turn) {
  const article = document.createElement('article');
  article.className = 'turn';
  const label = document.createElement('div');
  label.className = 'turn-label';
  const marker = document.createElement('span');
  marker.className = 'speaker-marker';
  marker.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'speaker-name';
  const currentIndicator = document.createElement('span');
  currentIndicator.className = 'current-indicator';
  currentIndicator.textContent = '현재';
  currentIndicator.setAttribute('aria-label', '현재 자막');
  currentIndicator.hidden = true;
  label.append(marker, name, currentIndicator);
  const paragraph = document.createElement('p');
  paragraph.className = 'turn-text';
  const finalText = document.createElement('span');
  finalText.className = 'final-text';
  const partialText = document.createElement('span');
  partialText.className = 'partial-text';
  paragraph.append(finalText, partialText);
  article.append(label, paragraph);
  conversation.append(article);
  const row = { article, name, currentIndicator, finalText, partialText, speaker: null };
  rows.set(turn.id, row);
  return row;
}

function setText(element, value) {
  if (element.textContent !== value) element.textContent = value;
}

function scrollToLatest() {
  followLatest = true;
  // No smooth animation per word: the reading position stays predictable.
  viewport.scrollTop = viewport.scrollHeight;
  $('latest-button').hidden = true;
}

function renderTurns({ animate = true } = {}) {
  const turns = transcript.getTurns();
  const currentId = turns.at(-1)?.id;
  const appendedTurn = turns.length > previousTurnCount;
  const previousPositions = new Map();
  if (animate && appendedTurn && followLatest && !reducedMotion.matches) {
    for (const [id, row] of rows) previousPositions.set(id, row.article.getBoundingClientRect().top);
  }
  const visibleIds = new Set(turns.map(turn => turn.id));
  for (const [id, row] of rows) {
    if (!visibleIds.has(id)) { row.article.remove(); rows.delete(id); }
  }
  for (const turn of turns) {
    const existed = rows.has(turn.id);
    const row = rows.get(turn.id) || makeRow(turn);
    const speaker = speakerInfo(turn.speaker);
    if (row.speaker !== turn.speaker) {
      row.article.classList.remove(...speakerClasses, 'speaker-unknown');
      row.article.classList.add(speaker.className);
      row.article.dataset.speaker = turn.speaker;
      row.speaker = turn.speaker;
      setText(row.name, speaker.label);
      row.article.setAttribute('aria-label', speaker.label);
    }
    row.article.classList.toggle('is-current', turn.id === currentId);
    row.article.classList.toggle('is-provisional', !turn.finalText);
    if (turn.id === currentId) row.article.setAttribute('aria-current', 'true');
    else row.article.removeAttribute('aria-current');
    setText(row.finalText, turn.finalText);
    // Use the model's exact join (including spaces and punctuation) so a final
    // batch cannot create a duplicate word or a stray space before punctuation.
    setText(row.partialText, turn.text.slice(turn.finalText.length));
    if (!existed && animate && followLatest) {
      row.article.classList.add('is-new');
      row.article.addEventListener('animationend', () => row.article.classList.remove('is-new'), { once: true });
    }
  }
  $('empty-state').hidden = turns.length > 0;
  if (followLatest) scrollToLatest();
  else $('latest-button').hidden = !turns.length;
  // Only speaker changes animate the previous paragraphs upward. Same-speaker
  // words update the existing text nodes without replaying an entrance effect.
  if (animate && appendedTurn && followLatest && !reducedMotion.matches) {
    for (const [id, previousTop] of previousPositions) {
      const row = rows.get(id);
      if (!row) continue;
      const movement = previousTop - row.article.getBoundingClientRect().top;
      if (Math.abs(movement) > 1 && Math.abs(movement) < viewport.clientHeight && row.article.animate) {
        row.article.animate([{ transform: `translateY(${movement}px)` }, { transform: 'translateY(0)' }], { duration: 200, easing: 'ease-out' });
      }
    }
  }
  previousTurnCount = turns.length;
  updateAtmosphere(turns);
}

function renderStatus() {
  const button = $('status-button');
  button.classList.remove('is-preview', 'is-listening', 'is-connecting', 'is-error');
  button.disabled = phase === 'initializing' || phase === 'stopping';
  if (mode === 'preview') button.removeAttribute('aria-pressed');
  else button.setAttribute('aria-pressed', String(phase === 'recording'));
  let label;
  let action;
  if (mode === 'preview') {
    label = '미리보기';
    action = '예시 대화 다시 재생';
    button.classList.add('is-preview');
    document.body.dataset.state = 'preview';
  } else if (phase === 'recording') {
    label = '듣는 중';
    action = '마이크 듣기 중지';
    button.classList.add('is-listening');
    document.body.dataset.state = 'listening';
  } else if (phase === 'connecting') {
    label = '연결 중';
    action = '연결 취소';
    button.classList.add('is-connecting');
    document.body.dataset.state = 'connecting';
  } else if (phase === 'stopping') {
    label = '마무리 중'; action = '마지막 자막을 받고 있어요';
    document.body.dataset.state = 'stopping';
  } else if (phase === 'initializing') {
    label = '연결 확인 중'; action = label;
    document.body.dataset.state = 'idle';
  } else if (phase === 'error') {
    label = '다시 연결'; action = '마이크 다시 연결';
    button.classList.add('is-error'); document.body.dataset.state = 'error';
  } else {
    label = microphoneHasStarted ? '새 대화 시작' : '자막 시작';
    action = label; document.body.dataset.state = 'idle';
  }
  setText($('status-label'), label);
  button.setAttribute('aria-label', action);
  button.title = action;
  document.body.classList.toggle('demo-mode', mode === 'preview');
  if (!transcript.getTurns().length) {
    setText($('empty-title'), phase === 'recording' ? '이야기를 듣고 있어요.' : phase === 'connecting' ? '음성을 연결하고 있어요.' : '대화를 시작하면 자막이 나타나요.');
    setText($('empty-description'), phase === 'recording' ? '편하게 이야기해 주세요.' : phase === 'connecting' ? '마이크 사용 권한을 허용해 주세요.' : '왼쪽 위 버튼을 눌러 시작하세요.');
    $('empty-description').hidden = phase === 'initializing';
  }
  updateAtmosphere();
}

function notice(message) {
  clearTimeout(noticeTimer);
  setText($('notice-text'), message);
  $('notice').hidden = false;
  noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 3200);
}

function setError(message = '') {
  $('error-message').hidden = !message;
  setText($('error-message'), message);
  updateAtmosphere();
}

function resetConversation() {
  atmosphere.reset();
  transcript.reset(); speakerNumbers.clear();
  for (const row of rows.values()) row.article.remove();
  rows.clear(); previousTurnCount = 0; followLatest = true;
  $('latest-button').hidden = true;
}

async function getToken() {
  const controller = new AbortController();
  tokenController = controller;
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch('/api/token', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}', signal: controller.signal,
    });
    const data = await response.json();
    if (!response.ok || typeof data.token !== 'string') throw new Error(data.error || '음성 연결을 준비하지 못했어요.');
    return data.token;
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('연결 응답이 늦어지고 있어요. 다시 연결해 주세요.');
    throw error;
  } finally {
    clearTimeout(timeout);
    if (tokenController === controller) tokenController = undefined;
  }
}

async function startLive() {
  if (['initializing', 'connecting', 'recording', 'stopping'].includes(phase)) return;
  if (!configured) { notice('아직 음성 연결이 준비되지 않았어요.'); return; }
  clearTimeout(previewTimer);
  mode = 'live'; phase = 'connecting'; setError('');
  $('notice').hidden = true;
  const currentGeneration = ++generation;
  const current = () => alive && currentGeneration === generation;
  renderStatus();
  const candidate = new SpeechmaticsSession({
    token: getToken, language: 'ko', diarization: true, maxDelay: 2,
    onFinal: segments => {
      if (!current()) return;
      transcript.addFinal(segments); renderTurns();
    },
    onPartial: segments => {
      if (!current()) return;
      transcript.setPartial(segments); renderTurns();
    },
    onLevel: () => {},
    onState: next => {
      if (!current()) return;
      phase = next === 'stopped' && !$('error-message').hidden ? 'error' : next;
      if (next === 'recording') {
        // A new recognition session assigns new speaker IDs. Keep previous
        // captions until connection succeeds, then begin a fresh conversation.
        resetConversation(); microphoneHasStarted = true;
      }
      if (next === 'stopped') transcript.clearPartial();
      renderTurns({ animate: false }); renderStatus();
    },
    onError: message => {
      if (!current()) return;
      setError(message); phase = 'error'; renderStatus();
    },
  });
  session = candidate;
  try { await candidate.start(); }
  catch (error) {
    if (current() && error?.name !== 'AbortError' && !$('error-message').textContent) setError('음성을 연결하지 못했어요. 다시 연결해 주세요.');
    candidate.dispose();
  }
}

async function stopLive() {
  if (phase === 'connecting') {
    // Cancellation is intentional. Ignore late permission/token callbacks from
    // this generation instead of showing a connection error after cancellation.
    generation++;
    const pending = session; session = undefined; phase = 'stopped';
    const cancelled = pending?.stop(); tokenController?.abort();
    renderStatus();
    await cancelled;
    return;
  }
  const stopping = session?.stop();
  tokenController?.abort();
  try { await stopping; }
  catch { if (alive) { phase = 'error'; setError('연결을 종료하지 못했어요.'); renderStatus(); } }
}

// A clearly labelled local preview shows the actual streaming UX, including
// several final API-sized batches from one speaker and replaceable interim text.
const previewSteps = [
  [350, 'partial', 'S1', '오늘은 어디로 갈까요?'],
  [1400, 'final', 'S1', '오늘은 어디로 갈까요?'],
  [2100, 'partial', 'S1', '날씨가 좋아서'],
  [2800, 'partial', 'S1', '날씨가 좋아서 조금 멀리 가도 좋을 것 같아요.'],
  [4000, 'final', 'S1', '날씨가 좋아서 조금 멀리 가도 좋을 것 같아요.'],
  [5400, 'partial', 'S2', '한강 쪽으로 가면 어때요?'],
  [6500, 'final', 'S2', '한강 쪽으로 가면 어때요?'],
  [7300, 'partial', 'S2', '도착하면 잠깐 걷고'],
  [8200, 'partial', 'S2', '도착하면 잠깐 걷고 커피도 한 잔 마셔요.'],
  [9500, 'final', 'S2', '도착하면 잠깐 걷고 커피도 한 잔 마셔요.'],
  [11200, 'partial', 'S1', '좋아요. 그럼 가까운 주차장을'],
  [12100, 'partial', 'S1', '좋아요. 그럼 가까운 주차장을 찾아볼게요.'],
  [13300, 'final', 'S1', '좋아요. 그럼 가까운 주차장을 찾아볼게요.'],
  [14400, 'partial', 'S1', '길이 막히면 다른 길로 가도 괜찮아요.'],
  [15700, 'final', 'S1', '길이 막히면 다른 길로 가도 괜찮아요.'],
];

function startPreview() {
  if (mode === 'live' && ['connecting', 'recording', 'stopping'].includes(phase)) return;
  generation++; clearTimeout(previewTimer);
  resetConversation(); setError(''); mode = 'preview'; phase = 'preview';
  renderStatus(); renderTurns({ animate: false });
  const previewGeneration = generation;
  let index = 0;
  const startedAt = performance.now();
  const advance = () => {
    if (!alive || mode !== 'preview' || previewGeneration !== generation || index >= previewSteps.length) return;
    const [at, type, speaker, text] = previewSteps[index];
    const segment = { id: `preview-${index}`, speaker, text, start: at / 1000, end: at / 1000 + 1 };
    if (type === 'final') transcript.addFinal([segment]);
    else transcript.setPartial([segment]);
    renderTurns(); index++;
    if (index < previewSteps.length) previewTimer = setTimeout(advance, Math.max(0, previewSteps[index][0] - (performance.now() - startedAt)));
  };
  previewTimer = setTimeout(advance, previewSteps[0][0]);
}

$('status-button').addEventListener('click', () => {
  if (mode === 'preview') startPreview();
  else if (phase === 'recording' || phase === 'connecting') void stopLive();
  else void startLive();
});
$('settings-button').addEventListener('click', () => {
  // The vehicle host can intercept navigation without mounting settings UI here.
  const event = new CustomEvent('sori:open-settings', { cancelable: true, detail: { path: '/settings' } });
  if (window.dispatchEvent(event)) notice('설정 화면은 추후 연결됩니다.');
});
$('latest-button').addEventListener('click', () => {
  scrollToLatest();
  updateAtmosphere();
});
viewport.addEventListener('scroll', () => {
  const wasFollowing = followLatest;
  followLatest = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop < 36;
  $('latest-button').hidden = followLatest || !rows.size;
  if (wasFollowing !== followLatest) updateAtmosphere();
}, { passive: true });
window.addEventListener('resize', () => { if (followLatest) scrollToLatest(); });
window.addEventListener('pagehide', () => {
  atmosphere.reset();
  alive = false; generation++;
  session?.dispose(); tokenController?.abort();
  clearTimeout(previewTimer); clearTimeout(noticeTimer);
});
window.addEventListener('pageshow', event => {
  if (!event.persisted) return;
  alive = true; phase = mode === 'preview' ? 'preview' : 'stopped';
  transcript.clearPartial(); renderTurns({ animate: false }); renderStatus();
});

async function initialize() {
  try {
    const response = await fetch('/api/token', { cache: 'no-store' });
    if (!response.ok) throw new Error('configuration');
    const data = await response.json(); configured = data.configured === true;
  } catch { configured = false; }
  if (!alive) return;
  phase = 'stopped';
  if (query.get('mode') === 'preview' || (!configured && query.get('mode') !== 'live')) startPreview();
  else {
    renderStatus(); renderTurns({ animate: false });
    if (!configured) notice('음성 연결을 설정한 뒤 시작할 수 있어요.');
  }
}

renderStatus();
void initialize();
