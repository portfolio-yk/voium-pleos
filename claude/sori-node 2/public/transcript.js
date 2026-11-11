/**
 * Speechmatics transcript parsing and export helpers. This module is shared by
 * the browser UI and Node's built-in test runner; it has no DOM dependencies.
 */

const finiteTime = (value, fallback = 0) =>
  typeof value === "number" && Number.isFinite(value) ? Math.max(0, value) : fallback;

/** One replaceable partial batch or one immutable final batch. */
export function parseTranscript(message, options = {}) {
  const { language = "ko", prefix = message.message === "AddPartialTranscript" ? "partial" : "final", defaultSpeaker = "UU" } = options;
  const wordDelimiter = options.wordDelimiter ?? (/^(ja|zh|cmn|yue)(-|$)/.test(language) ? "" : " ");
  const results = (Array.isArray(message.results) ? message.results : []).flatMap((result) => {
    if (!result || typeof result !== "object") return [];
    return result.alternatives?.some((alternative) => alternative?.content)
      ? [result]
      : Array.isArray(result.written_form) ? result.written_form : [];
  });
  const segments = [];
  let previousAttachesNext = false;

  for (let index = 0; index < results.length; index += 1) {
    const result = results[index];
    const alternative = result?.alternatives?.[0];
    const content = typeof alternative?.content === "string" ? alternative.content.trim() : "";
    if (!content) continue;
    const punctuation = result.type === "punctuation";
    const attachment = result.attaches_to ?? (punctuation
      ? (/^[([{“‘「『《〈]$/.test(content) ? "next" : "previous")
      : "none");
    const previous = segments[segments.length - 1];
    let speaker = alternative?.speaker;
    if (!speaker && punctuation && (attachment === "next" || attachment === "both")) {
      speaker = results.slice(index + 1).find((item) => item?.type !== "punctuation")?.alternatives?.[0]?.speaker;
    }
    if (!speaker && punctuation) speaker = previous?.speaker;
    speaker ||= defaultSpeaker;

    const start = finiteTime(result.start_time, finiteTime(message.metadata?.start_time));
    const end = Math.max(start, finiteTime(result.end_time, start));
    const joinsPrevious = attachment === "previous" || attachment === "both";
    if (previous?.speaker === speaker) {
      const delimiter = previousAttachesNext || joinsPrevious || /\s$/.test(previous.text) ? "" : wordDelimiter;
      previous.text += delimiter + content;
      previous.end = Math.max(previous.end, end);
    } else {
      segments.push({ id: `${prefix}-${segments.length}-${start}`, speaker, text: content, start, end });
    }
    previousAttachesNext = attachment === "next" || attachment === "both";
  }

  if (!segments.length && typeof message.metadata?.transcript === "string" && message.metadata.transcript.trim()) {
    const start = finiteTime(message.metadata.start_time);
    segments.push({
      id: `${prefix}-0-${start}`,
      speaker: defaultSpeaker,
      text: message.metadata.transcript.trim(),
      start,
      end: Math.max(start, finiteTime(message.metadata.end_time, start)),
    });
  }
  return segments;
}

export function formatTime(seconds) {
  const total = Math.floor(finiteTime(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remaining = total % 60;
  const clock = `${String(minutes).padStart(2, "0")}:${String(remaining).padStart(2, "0")}`;
  return hours ? `${String(hours).padStart(2, "0")}:${clock}` : clock;
}

export function speakerLabel(speaker, names = {}) {
  const customName = names[speaker]?.trim();
  if (customName) return customName;
  if (speaker === "UU") return "화자 미확인";
  const number = /^S(\d+)$/.exec(speaker)?.[1];
  return number ? `화자 ${number}` : speaker;
}

export function exportTxt(segments, names = {}) {
  return segments.filter((segment) => segment.text.trim()).map((segment) =>
    `[${formatTime(segment.start)}] ${speakerLabel(segment.speaker, names)}: ${segment.text.trim()}`,
  ).join("\n");
}

function srtTime(seconds) {
  const milliseconds = Math.round(finiteTime(seconds) * 1000);
  const hours = Math.floor(milliseconds / 3_600_000);
  const minutes = Math.floor((milliseconds % 3_600_000) / 60_000);
  const remaining = Math.floor((milliseconds % 60_000) / 1000);
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(remaining).padStart(2, "0")},${String(milliseconds % 1000).padStart(3, "0")}`;
}

export function exportSrt(segments, names = {}) {
  return segments.filter((segment) => segment.text.trim()).map((segment, index) => {
    const start = finiteTime(segment.start);
    const end = Math.max(start + 0.04, finiteTime(segment.end, start));
    // Angle brackets are reserved for markup in SRT readers.
    const text = `${speakerLabel(segment.speaker, names)}: ${segment.text.trim()}`
      .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
    return `${index + 1}\n${srtTime(start)} --> ${srtTime(end)}\n${text}`;
  }).join("\n\n");
}
