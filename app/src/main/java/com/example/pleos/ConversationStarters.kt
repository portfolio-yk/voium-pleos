package com.example.pleos

/**
 * 대화가 없을 때 보여 주는 대화 제안입니다 (Figma 243:4245 시간, 243:4298 위치, 243:4353 날씨).
 * 지금 알 수 있는 상황(시간 · 내비 목적지 · 비)마다 카드를 한 장씩 만들고, 옆으로 넘기며 보여 줍니다.
 * 한 바퀴 돌 때마다 각 카드의 질문도 다음 것으로 바뀝니다.
 */
object ConversationStarters {

    data class Slot(val label: String, val questions: List<String>)

    enum class Kind { TIME, PLACE, WEATHER }

    /** 한 장의 제안 카드: 아이콘 종류, 상황 문구("오후 12:40 · 점심시간"), 질문 */
    data class Card(val kind: Kind, val label: String, val question: String)

    private val RAIN = listOf(
        "창문에 김 서리면\n말해 줄래요?",
        "빗길이니까\n천천히 가요",
        "비 오는 날엔\n뭐 먹고 싶어요?",
    )

    /**
     * 지금 보여 줄 수 있는 카드들. 시간 카드는 늘 있고, 내비 안내 중이면 위치 카드,
     * 비가 오면(와이퍼 작동) 날씨 카드가 붙습니다. round는 몇 바퀴째인지(질문 고르기).
     */
    fun cards(hour: Int, timeText: String, trip: Pair<String, Int>?, raining: Boolean, round: Int): List<Card> = buildList {
        val time = slotFor(hour)
        add(Card(Kind.TIME, "$timeText · ${time.label}", time.questions[round.mod(time.questions.size)]))
        if (trip != null) {
            val place = forTrip(trip.first, trip.second)
            add(Card(Kind.PLACE, place.label, place.questions[round.mod(place.questions.size)]))
        }
        if (raining) add(Card(Kind.WEATHER, "비 오는 중", RAIN[round.mod(RAIN.size)]))
    }

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
