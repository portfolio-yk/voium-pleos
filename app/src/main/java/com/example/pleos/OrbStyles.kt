package com.example.pleos

import android.graphics.Color

/**
 * 입자 링(Figma "visualizer · 입자 링") 색 상태입니다. 모양·움직임 값은 OrbStyle 기본값을 씁니다.
 * - 듣는 중: 파란색(#4471F2), 말하는 사람이 있으면 그 사람의 색(MainActivity가 바꿈)
 * - 대기 중: 회색(#9CA1AB) 45%
 * - 일시 정지(통화 등): 회색 진하게
 */
val AppOrbStyle = OrbStyle()

val IdleOrbStyle = AppOrbStyle.copy(color = Color.parseColor("#9CA1AB"), opacity = 0.45f)

val PausedOrbStyle = AppOrbStyle.copy(color = Color.parseColor("#9CA1AB"), opacity = 0.85f)

/**
 * 마이크 감도입니다. 기기마다 달라서 개발자가 실제 기기에서 정합니다.
 * 디버그 빌드로 녹음하면서 adb logcat -s SttAudio 로 조용할 때와 말할 때의 dBFS를 재고,
 * 무음 기준은 조용할 때보다 3~5dB 높게, 최대 기준은 크게 말할 때 정도로 잡습니다.
 * 지금 값은 2026-11-08에 직접 지정한 값입니다. 에뮬레이터 마이크(무음 -35~-37dB)에서는 -32/-20이 맞았습니다.
 */
val AppMicSensitivity = MicSensitivity(
    silenceDb = -73f,
    loudDb = 0f,
)
