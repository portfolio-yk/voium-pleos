package com.example.pleos

/**
 * 대화가 없을 때 보여 주는 대화 제안입니다 (Figma C2 suggestion_placeholder).
 * 시간대마다 몇 가지 질문을 돌려 가며 보여 줍니다.
 */
object ConversationStarters {

    data class Slot(val label: String, val questions: List<String>)

    private val MORNING = Slot("아침 시간", listOf(
        "오늘 아침은\n드셨어요?",
        "어젯밤에는\n잘 주무셨어요?",
        "오늘 제일 기대되는\n일은 뭐예요?",
    ))
    private val LUNCH = Slot("점심시간", listOf(
        "오늘 식사는\n어떻게 하셨어요?",
        "점심으로 뭐가\n먹고 싶으세요?",
        "요즘 맛있게 먹은\n음식 있어요?",
    ))
    private val AFTERNOON = Slot("오후", listOf(
        "요즘 즐겨 듣는\n노래 있어요?",
        "주말에 가 보고 싶은\n곳 있어요?",
        "오늘 오후엔\n뭐 하고 싶으세요?",
    ))
    private val EVENING = Slot("저녁 시간", listOf(
        "저녁은 뭐\n먹을까요?",
        "오늘 하루는\n어떠셨어요?",
        "오늘 제일 재밌었던\n일은 뭐예요?",
    ))
    private val NIGHT = Slot("밤 시간", listOf(
        "오늘 하루는\n어떠셨어요?",
        "내일은 어떤\n계획이 있으세요?",
        "피곤하면 잠깐\n쉬었다 갈까요?",
    ))

    /**
     * 내비게이션 안내 중일 때: 다음 목적지와 남은 시간으로 제안합니다(Pleos NaviHelper).
     * 예: "휴게소까지 20분" · "휴게소에 도착하면 뭐 하고 싶어요?"
     */
    fun forTrip(place: String, remainSeconds: Int): Slot {
        val name = shortPlace(place)
        val minutes = (remainSeconds + 59) / 60
        val questions = buildList {
            add("${name}에 도착하면\n뭐 하고 싶어요?")
            // 한 시간 넘게 남았으면 쉬어 가기를, 곧 도착이면 내릴 준비를 묻습니다.
            add(if (minutes >= 60) "${duration(minutes)} 남았어요\n잠깐 쉬었다 갈까요?" else "곧 도착이에요\n챙길 물건 있어요?")
            add("${name} 가는 길에\n들르고 싶은 곳 있어요?")
        }
        return Slot("${name}까지 ${duration(minutes)}", questions)
    }

    /** 61분 → "1시간 1분", 20분 → "20분" */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    /** 긴 장소 이름은 앞 10자만 씁니다(한 줄에 들어가도록). */
    private fun shortPlace(place: String) = place.trim().let { if (it.length > 10) it.take(10) + "…" else it }

    /** 0~23시 기준 시간대입니다. */
    fun slotFor(hour: Int): Slot = when (hour) {
        in 5..10 -> MORNING
        in 11..13 -> LUNCH
        in 14..17 -> AFTERNOON
        in 18..21 -> EVENING
        else -> NIGHT
    }
}
