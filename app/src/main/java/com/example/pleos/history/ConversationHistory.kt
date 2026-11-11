package com.example.pleos.history

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 대화 기록의 한 줄: 언제, 누가(화면에 보인 이름과 색), 무엇을 말했는지 */
data class RecordLine(
    val at: Long,
    val speaker: String,
    /** 등록된 사람의 프로필 색 키. 등록하지 않은 사람("화자 1")은 null */
    val colorKey: String?,
    val text: String,
)

enum class SummaryState { PENDING, DONE, FAILED }

/**
 * 대화 한 건 (Figma 153:1976 대화 기록). "대화 시작"부터 "대화종료"까지 확정된 자막을 텍스트로 저장합니다.
 * 원본 음성은 저장하지 않습니다.
 */
data class ConversationRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val lines: List<RecordLine>,
    /** AI 요약 한 줄(목록에 보임) */
    val headline: String? = null,
    /** AI 요약 요점(상세에 보임) */
    val summary: List<String> = emptyList(),
    val summaryState: SummaryState = SummaryState.PENDING,
) {
    /** 말한 사람들(처음 말한 순서) */
    val participants: List<Pair<String, String?>>
        get() = lines.map { it.speaker to it.colorKey }.distinctBy { it.first }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 대화 기록을 앱 전용 저장 공간의 JSON 파일에 보관합니다(이 차량 안에만, 백업 제외).
 * 최근 MAX_RECORDS건까지 남깁니다. 바뀌면 등록된 화면에 알립니다(메인 스레드).
 */
class ConversationHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun load(): List<ConversationRecord> =
        if (file.isFile) fromJson(file.readText()) else emptyList()

    fun find(id: String) = load().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(record: ConversationRecord) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records.add(0, record)
        save(records.sortedByDescending { it.startedAt }.take(MAX_RECORDS))
    }

    @Synchronized
    fun delete(id: String) = save(load().filterNot { it.id == id })

    @Synchronized
    private fun save(records: List<ConversationRecord>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(toJson(records))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        notifyChanged()
    }

    companion object {
        const val FILE_NAME = "conversations.json"
        const val MAX_RECORDS = 100

        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val listeners = LinkedHashSet<() -> Unit>()

        fun addListener(listener: () -> Unit) { listeners += listener }
        fun removeListener(listener: () -> Unit) { listeners -= listener }
        private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

        fun toJson(records: List<ConversationRecord>): String = JSONArray(records.map { record ->
            JSONObject()
                .put("id", record.id)
                .put("startedAt", record.startedAt)
                .put("endedAt", record.endedAt)
                .put("headline", record.headline)
                .put("summary", JSONArray(record.summary))
                .put("summaryState", record.summaryState.name)
                .put("lines", JSONArray(record.lines.map { line ->
                    JSONObject()
                        .put("at", line.at)
                        .put("speaker", line.speaker)
                        .put("colorKey", line.colorKey)
                        .put("text", line.text)
                }))
        }).toString()

        fun fromJson(json: String): List<ConversationRecord> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lines = item.optJSONArray("lines") ?: JSONArray()
                val summary = item.optJSONArray("summary") ?: JSONArray()
                ConversationRecord(
                    id = id,
                    startedAt = item.optLong("startedAt"),
                    endedAt = item.optLong("endedAt"),
                    headline = item.optString("headline").takeIf { it.isNotBlank() && it != "null" },
                    summary = (0 until summary.length()).mapNotNull { summary.optString(it).takeIf(String::isNotBlank) },
                    summaryState = runCatching { SummaryState.valueOf(item.optString("summaryState")) }.getOrDefault(SummaryState.FAILED),
                    lines = (0 until lines.length()).mapNotNull { i ->
                        val line = lines.optJSONObject(i) ?: return@mapNotNull null
                        RecordLine(
                            at = line.optLong("at"),
                            speaker = line.optString("speaker"),
                            colorKey = line.optString("colorKey").takeIf { it.isNotBlank() && it != "null" },
                            text = line.optString("text"),
                        )
                    },
                )
            }
        }
    }
}

/** 대화 기록의 날짜·시간 표시 ("오늘", "오후 12:30 – 오후 2:05", "1시간 35분") */
object HistoryFormat {
    private fun time(at: Long) = SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date(at))

    fun timeOf(at: Long) = time(at)

    fun range(record: ConversationRecord) = "${time(record.startedAt)} – ${time(record.endedAt)}"

    /** 오늘 · 어제 · "11월 7일 (토)" */
    fun day(at: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = daysBetween(target, today)
        return when (days) {
            0 -> "오늘"
            1 -> "어제"
            else -> SimpleDateFormat("M월 d일 (E)", Locale.KOREAN).format(Date(at))
        }
    }

    /** "1시간 35분", "36분". 1분이 안 되면 "1분" */
    fun duration(startedAt: Long, endedAt: Long): String {
        val minutes = ((endedAt - startedAt) / 60_000).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}분"
            rest == 0L -> "${hours}시간"
            else -> "${hours}시간 ${rest}분"
        }
    }

    private fun daysBetween(from: Calendar, to: Calendar): Int {
        fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((dayStart(to) - dayStart(from)) / 86_400_000L).toInt()
    }
}
