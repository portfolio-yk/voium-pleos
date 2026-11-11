package com.example.pleos.stt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 등록된 목소리 프로필입니다.
 *
 * label은 Speechmatics에 보내는 내부 라벨(예: "P3f9a1c")입니다. 이름을 바꿔도 다시 등록할 필요가 없고,
 * 이름에 어떤 글자를 써도 Speechmatics 라벨 규칙에 걸리지 않게 이름과 분리했습니다.
 * identifiers는 Speechmatics가 만든 목소리 식별값이며, 같은 계정·같은 모델에서만 쓸 수 있습니다.
 */
data class VoiceProfile(
    val label: String,
    val name: String,
    val identifiers: List<String>,
    val createdAt: Long,
    /** 자막에 쓰는 색(SpeakerThemes.ALL의 key). 예전 프로필은 비어 있어 불러올 때 채웁니다. */
    val colorKey: String? = null,
    /** 등록할 때 읽은 문장 수. 0이면 예전 방식(긴 글 한 번)으로 등록한 것이라 다시 등록을 권합니다. */
    val sentences: Int = 0,
) {
    fun toEnrolledSpeaker() = EnrolledSpeaker(label, identifiers)

    val needsReenroll: Boolean get() = sentences < VoiceProfileRules.ENROLL_SENTENCES
}

object VoiceProfileRules {
    const val MAX_PROFILES = 10
    const val MAX_NAME_LENGTH = 12
    /** 목소리 등록 때 읽는 문장 수 */
    const val ENROLL_SENTENCES = 3
    // 등록하지 않은 사람에게 쓰는 표시("화자 1")와 예전 표시는 등록 이름으로 쓸 수 없습니다.
    private val GUEST_NAME = Regex("^(화자|발화자|미등록)\\s*\\d+$")

    /** 이름이 올바르면 null, 아니면 사용자에게 보여 줄 이유를 돌려줍니다. */
    fun validateName(name: String, existing: List<VoiceProfile>, editingLabel: String? = null): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "이름을 입력해 주세요."
            trimmed.length > MAX_NAME_LENGTH -> "이름은 ${MAX_NAME_LENGTH}자 이하로 입력해 주세요."
            GUEST_NAME.matches(trimmed) -> "'화자 번호' 형식은 등록되지 않은 사람에게 쓰여서 사용할 수 없어요."
            existing.any { it.label != editingLabel && it.name == trimmed } -> "같은 이름의 프로필이 이미 있어요."
            else -> null
        }
    }

    fun newLabel(): String = "P" + UUID.randomUUID().toString().replace("-", "").take(10)

    fun toJson(profiles: List<VoiceProfile>): String = JSONArray(profiles.map { profile ->
        JSONObject()
            .put("label", profile.label)
            .put("name", profile.name)
            .put("identifiers", JSONArray(profile.identifiers))
            .put("createdAt", profile.createdAt)
            .put("color", profile.colorKey ?: JSONObject.NULL)
            .put("sentences", profile.sentences)
    }).toString()

    fun fromJson(json: String): List<VoiceProfile> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val label = item.stringOrNull("label") ?: return@mapNotNull null
            val name = item.stringOrNull("name") ?: return@mapNotNull null
            val ids = item.optJSONArray("identifiers") ?: return@mapNotNull null
            val identifiers = (0 until ids.length()).mapNotNull { (ids.opt(it) as? String)?.takeIf(String::isNotBlank) }
            if (identifiers.isEmpty() || RecognitionConfig.isReservedLabel(label)) return@mapNotNull null
            VoiceProfile(
                label = label,
                name = name,
                identifiers = identifiers,
                createdAt = item.optLong("createdAt"),
                colorKey = item.stringOrNull("color"),
                sentences = item.optInt("sentences", 0),
            )
        }
    }

    /** 색이 없는 프로필(예전에 등록한 것)에 다른 프로필이 쓰지 않는 색을 차례로 정해 줍니다. */
    fun withColors(profiles: List<VoiceProfile>): List<VoiceProfile> {
        val used = profiles.mapNotNull { profile -> profile.colorKey?.takeIf { SpeakerThemes.forKey(it) != null } }.toMutableList()
        return profiles.map { profile ->
            if (SpeakerThemes.forKey(profile.colorKey) != null) profile
            else profile.copy(colorKey = SpeakerThemes.firstUnused(used).key.also { used += it })
        }
    }
}

/**
 * 프로필을 앱 전용 저장 공간의 JSON 파일에 보관합니다.
 * 목소리 식별값은 개인 정보이므로 백업 대상에서도 뺍니다(res/xml의 백업 규칙 참고).
 */
class VoiceProfileStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun load(): List<VoiceProfile> {
        val loaded = if (file.isFile) VoiceProfileRules.fromJson(file.readText()) else emptyList()
        // 예전에 등록해 색이 없는 프로필은 색을 정해 바로 저장합니다(다음에도 같은 색).
        val colored = VoiceProfileRules.withColors(loaded)
        if (colored != loaded) save(colored)
        return colored
    }

    fun find(label: String): VoiceProfile? = load().firstOrNull { it.label == label }

    @Synchronized
    fun save(profiles: List<VoiceProfile>) {
        // 임시 파일에 쓴 뒤 바꿔 끼워, 저장 중에 앱이 종료돼도 파일이 깨지지 않게 합니다.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(VoiceProfileRules.toJson(profiles))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    /** 같은 label이 있으면 바꾸고, 없으면 추가합니다. */
    @Synchronized
    fun upsert(profile: VoiceProfile) {
        val profiles = load().toMutableList()
        val index = profiles.indexOfFirst { it.label == profile.label }
        if (index >= 0) profiles[index] = profile else profiles += profile
        save(profiles)
    }

    @Synchronized
    fun delete(label: String) = save(load().filterNot { it.label == label })

    companion object {
        const val FILE_NAME = "voice_profiles.json"
    }
}

/**
 * 화면에 보여 줄 화자 정보입니다. colorIndex는 등장 순서대로 매깁니다. 확인 중인 화자는 null입니다.
 * colorKey는 등록된 사람의 프로필 색입니다(등록하지 않은 사람은 null).
 */
data class SpeakerName(val label: String, val isEnrolled: Boolean, val colorIndex: Int?, val colorKey: String? = null)

/**
 * 인식 결과의 speaker 값을 사람이 읽는 이름으로 바꿉니다.
 * - 등록된 목소리 → 등록한 이름(예: "홍길동")
 * - 등록되지 않은 사람(S1, S2…) → 처음 등장한 순서대로 "화자 1", "화자 2"
 * - 아직 판단하지 못한 화자(UU) → "화자 확인 중"
 */
class SpeakerDirectory(profiles: List<VoiceProfile> = emptyList()) {
    private var profilesByLabel = profiles.associateBy { it.label }
    private val guestNumbers = HashMap<String, Int>()
    private val colorOrder = HashMap<String, Int>()

    fun setProfiles(profiles: List<VoiceProfile>) {
        profilesByLabel = profiles.associateBy { it.label }
    }

    /**
     * commit이 false면(중간 결과) 번호를 미리 보여 주기만 하고 확정하지 않습니다.
     * 중간 결과에 잠깐 나타났다 사라진 화자 때문에 발화자 번호가 건너뛰지 않게 합니다.
     */
    fun resolve(speaker: String, commit: Boolean = true): SpeakerName {
        if (speaker == UNKNOWN) return SpeakerName(UNKNOWN_LABEL, isEnrolled = false, colorIndex = null)
        val color = colorOrder[speaker] ?: colorOrder.size.also { if (commit) colorOrder[speaker] = it }
        profilesByLabel[speaker]?.let {
            return SpeakerName(it.name, isEnrolled = true, colorIndex = color, colorKey = it.colorKey)
        }
        val number = guestNumbers[speaker] ?: (guestNumbers.size + 1).also { if (commit) guestNumbers[speaker] = it }
        return SpeakerName("$GUEST_PREFIX$number", isEnrolled = false, colorIndex = color)
    }

    /** 새 대화를 시작할 때 발화자 번호와 색 순서를 처음부터 다시 매깁니다. */
    fun reset() {
        guestNumbers.clear()
        colorOrder.clear()
    }

    /** "홍길동: 에어컨 줄여줘" 형식의 한 줄입니다. */
    fun line(speaker: String, text: String) = "${resolve(speaker).label}: ${text.trim()}"

    companion object {
        const val UNKNOWN = "UU"
        const val UNKNOWN_LABEL = "화자 확인 중"
        const val GUEST_PREFIX = "화자 "
    }
}
