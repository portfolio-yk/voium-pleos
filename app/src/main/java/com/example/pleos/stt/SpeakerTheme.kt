package com.example.pleos.stt

/**
 * 화자 색입니다 (VOIUM 시안, 밝은 배경 기준). 프로필마다 하나를 골라 저장합니다.
 * color: 아바타·"말하는 중" 표시·점 색. 태그 배경은 이 색의 12%입니다.
 * text: 12% 배경 위 글자색(노랑처럼 밝은 색은 더 진하게).
 * dot: 이 사람이 말하는 동안 블롭 점의 색입니다.
 */
data class SpeakerTheme(val key: String, val color: Int, val dot: Int, val text: Int = color)

object SpeakerThemes {
    /** 아무도 말하지 않을 때. 블롭은 기본 색(AppOrbStyle)으로 돌아갑니다. */
    val NEUTRAL = SpeakerTheme("neutral", 0xFF8E929C.toInt(), 0xFF9AA0AC.toInt(), 0xD6131417.toInt())

    /** 등록되지 않은 사람("화자 1")과 아직 확인 중인 화자. 고유 색 없이 회색으로 표시합니다. */
    val GUEST = SpeakerTheme("guest", 0xFF8E929C.toInt(), 0xFF9AA0AC.toInt(), 0xD6131417.toInt())

    /** 프로필 색 (시안의 색 고르기 순서). */
    val ALL: List<SpeakerTheme> = listOf(
        SpeakerTheme("blue", 0xFF3D6BF0.toInt(), 0xFF5C86F7.toInt()),
        SpeakerTheme("green", 0xFF13996F.toInt(), 0xFF2DB487.toInt()),
        SpeakerTheme("orange", 0xFFFF8A4C.toInt(), 0xFFFFA36F.toInt(), 0xFFE0661F.toInt()),
        SpeakerTheme("purple", 0xFF8B5CF6.toInt(), 0xFFA27DF8.toInt()),
        SpeakerTheme("pink", 0xFFE0457B.toInt(), 0xFFEA6A97.toInt()),
        SpeakerTheme("yellow", 0xFFE8B100.toInt(), 0xFFF2C531.toInt(), 0xFFB58A00.toInt()),
    )

    fun forKey(key: String?): SpeakerTheme? = ALL.firstOrNull { it.key == key } ?: customOf(key)

    /** 직접 고른 색은 "#RRGGBB" 키로 저장합니다(Figma "사용자 지정"). */
    fun isCustom(key: String?) = key != null && key.startsWith("#")

    fun custom(color: Int): SpeakerTheme {
        val rgb = color and 0xFFFFFF
        val opaque = 0xFF000000.toInt() or rgb
        // 링 점은 조금 밝게, 옅은 배경 위 글자는 밝은 색이면 조금 어둡게 합니다.
        val dot = androidx.core.graphics.ColorUtils.blendARGB(opaque, 0xFFFFFFFF.toInt(), 0.15f)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(opaque) > 0.45
        val text = if (light) androidx.core.graphics.ColorUtils.blendARGB(opaque, 0xFF000000.toInt(), 0.25f) else opaque
        return SpeakerTheme(String.format("#%06X", rgb), opaque, dot, text)
    }

    private fun customOf(key: String?): SpeakerTheme? {
        if (!isCustom(key) || key!!.length != 7) return null
        val rgb = key.substring(1).toIntOrNull(16) ?: return null
        return custom(rgb)
    }

    /** 다른 프로필이 아직 쓰지 않는 첫 색. 모두 쓰였으면 가장 적게 쓰인 색입니다. */
    fun firstUnused(usedKeys: Collection<String?>): SpeakerTheme =
        ALL.firstOrNull { it.key !in usedKeys } ?: ALL.minBy { theme -> usedKeys.count { it == theme.key } }
}

/** 지연 실행을 바꿔 끼울 수 있게 합니다. 반환값은 예약을 취소하는 함수입니다. */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): () -> Unit
}

/**
 * 확정 결과는 바로 반영하고, 임시 결과의 화자 변경만 잠시 지켜본 뒤 반영합니다.
 * 같은 임시 화자가 계속 말해도 처음 예약한 시점을 유지하므로 색 반영이 무한정 밀리지 않습니다.
 * sori-node의 speaker-theme.js를 옮긴 것입니다.
 */
class SpeakerThemeController(
    private val scheduler: Scheduler,
    delayMs: Long = 180,
    private val onChange: (SpeakerTheme) -> Unit,
) {
    private val delayMs = delayMs.coerceAtLeast(0)
    private var current = SpeakerThemes.NEUTRAL
    private var pending: SpeakerTheme? = null
    private var cancelTimer: (() -> Unit)? = null
    private var generation = 0
    private var disposed = false

    init {
        onChange(SpeakerThemes.NEUTRAL)
    }

    fun update(theme: SpeakerTheme?, provisional: Boolean, followLatest: Boolean, hasContent: Boolean) {
        if (disposed) return
        val target = if (hasContent && followLatest) theme ?: SpeakerThemes.NEUTRAL else SpeakerThemes.NEUTRAL

        if (target == current) {
            cancelPending()
            return
        }

        // 중립 배경은 바로 바꿉니다. 모르는 화자가 아는 화자의 색을 물려받으면 안 됩니다.
        if (target == SpeakerThemes.NEUTRAL || !provisional || delayMs == 0L) {
            cancelPending()
            commit(target)
            return
        }

        if (target == pending) return
        cancelPending()
        pending = target
        val scheduled = generation
        cancelTimer = scheduler.schedule(delayMs) {
            if (disposed || scheduled != generation) return@schedule
            pending = null
            cancelTimer = null
            commit(target)
        }
    }

    fun reset() {
        if (disposed) return
        cancelPending()
        commit(SpeakerThemes.NEUTRAL)
    }

    fun dispose() {
        cancelPending()
        disposed = true
    }

    private fun cancelPending() {
        cancelTimer?.invoke()
        cancelTimer = null
        pending = null
        generation += 1
    }

    private fun commit(theme: SpeakerTheme) {
        if (theme == current) return
        current = theme
        onChange(theme)
    }
}
