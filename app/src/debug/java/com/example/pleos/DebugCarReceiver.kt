package com.example.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.pleos.car.CarSignals

/**
 * 디버그 빌드 전용: 에뮬레이터에서 차량 상태를 바꿔 보며 화면을 확인하거나 시연할 때 씁니다.
 *
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --ei seats 2
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es gear drive     (drive | park)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es place 휴게소 --ei remain 1200
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es place none     (안내 끝)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es lights none    (none | ok: 실내 조명 미지원/지원)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es history demo   (예시 대화 기록을 저장하고 AI 요약 요청)
 */
class DebugCarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("history") == "demo") {
            saveDemoRecord(context)
            return
        }
        CarSignals.start(context)
        val seats = if (intent.hasExtra("seats")) intent.getIntExtra("seats", 0) else CarSignals.occupiedSeats
        val parked = when (intent.getStringExtra("gear")) {
            "park" -> true
            "drive" -> false
            else -> CarSignals.isParked
        }
        val trip = when (val place = intent.getStringExtra("place")) {
            null -> CarSignals.trip
            "none" -> null
            else -> CarSignals.Trip(place, intent.getIntExtra("remain", 600))
        }
        val lights = when (intent.getStringExtra("lights")) {
            "none" -> false
            "ok" -> true
            else -> CarSignals.lightsSupported
        }
        CarSignals.simulate(seats, parked, trip, lights)
    }

    private fun saveDemoRecord(context: Context) {
        val start = System.currentTimeMillis() - 95 * 60_000L
        val script = listOf(
            Triple("나", "blue", "창문 조금 열어드릴까요?"),
            Triple("엄마", "green", "괜찮다, 바람 좋네"),
            Triple("할머니", "purple", "저 앞에 휴게소 들르자. 호두과자 사 먹게"),
            Triple("나", "blue", "다들 안전벨트 했지? 휴게소까지 20분 남았대"),
            Triple("엄마", "green", "어머니, 지난번 거기 맛있었죠?"),
            Triple("할머니", "purple", "그럼, 그 집 팥이 달지 않아서 좋더라"),
        )
        val record = com.example.pleos.history.ConversationRecord(
            id = com.example.pleos.history.ConversationRecord.newId(),
            startedAt = start,
            endedAt = System.currentTimeMillis(),
            lines = script.mapIndexed { i, (who, color, text) ->
                com.example.pleos.history.RecordLine(start + 11 * 60_000L + i * 60_000L, who, color, text)
            },
        )
        com.example.pleos.history.ConversationHistoryStore(context).upsert(record)
        com.example.pleos.history.ConversationSummarizer.summarize(context, record.id)
    }
}
