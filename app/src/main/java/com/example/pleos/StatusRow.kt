package com.example.pleos

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * 화면 위쪽 상태 줄(include_status_row)입니다.
 * - 상태 칩: 듣는 중(초록), 대기·일시 정지·연결 중(회색), 마이크 오류(빨강)
 * - 대화종료·도움말·주행 중 칩: 자막 화면에서만 씁니다(다른 화면에서는 숨김).
 * - 설정 칩: 설정·목소리 등록 화면에서는 검정으로 선택된 모양
 * - 탑승 인원 칩
 */
class StatusRow(activity: Activity) {
    enum class Kind { LISTENING, IDLE, ERROR }

    val endButton: View = activity.findViewById(R.id.end_button)
    val helpButton: View = activity.findViewById(R.id.help_button)
    val historyButton: View = activity.findViewById(R.id.history_button)
    private val drivingChip: View = activity.findViewById(R.id.driving_chip)
    val statusButton: View = activity.findViewById(R.id.status_button)
    val statusIcon: ImageView = activity.findViewById(R.id.status_icon)
    val statusLabel: TextView = activity.findViewById(R.id.status_label)
    val settingsButton: View = activity.findViewById(R.id.settings_button)
    private val settingsIcon: ImageView = activity.findViewById(R.id.settings_icon)
    private val settingsLabel: TextView = activity.findViewById(R.id.settings_label)
    val passengersChip: View = activity.findViewById(R.id.passengers_chip)
    private val passengersLabel: TextView = activity.findViewById(R.id.passengers_label)
    private val context = activity

    /** listening이면 초록 "듣는 중" 모양, 아니면 회색(자막 일시 정지 등). */
    fun setStatus(label: String, listening: Boolean) = setStatus(label, if (listening) Kind.LISTENING else Kind.IDLE)

    fun setStatus(label: String, kind: Kind) {
        statusLabel.text = label
        statusButton.setBackgroundResource(
            when (kind) {
                Kind.LISTENING -> R.drawable.bg_chip_listening
                Kind.IDLE -> R.drawable.bg_chip_idle
                Kind.ERROR -> R.drawable.bg_chip_error
            },
        )
        statusIcon.setImageResource(if (kind == Kind.ERROR) R.drawable.ic_mic_off else R.drawable.ic_mic)
        val color = VoiumUi.color(
            context,
            when (kind) {
                Kind.LISTENING -> R.color.voium_listening_text
                Kind.IDLE -> R.color.voium_idle_text
                Kind.ERROR -> R.color.voium_error_text
            },
        )
        statusLabel.setTextColor(color)
        VoiumUi.tint(statusIcon, color)
    }

    /** 자막 화면의 칩: 대화종료(듣는 중·오류), 도움말(항상), 주행 중(대기 중이고 주행 중일 때) */
    fun setCaptionControls(showEnd: Boolean, showHelp: Boolean, showDriving: Boolean, showHistory: Boolean = false) {
        endButton.visibility = if (showEnd) View.VISIBLE else View.GONE
        historyButton.visibility = if (showHistory) View.VISIBLE else View.GONE
        helpButton.visibility = if (showHelp) View.VISIBLE else View.GONE
        drivingChip.visibility = if (showDriving) View.VISIBLE else View.GONE
    }

    fun setSettingsSelected(selected: Boolean) {
        settingsButton.isSelected = selected
        settingsButton.setBackgroundResource(if (selected) R.drawable.bg_chip_dark else R.drawable.bg_chip_outline)
        val color = VoiumUi.color(context, if (selected) android.R.color.white else R.color.voium_text_primary)
        settingsLabel.setTextColor(color)
        VoiumUi.tint(settingsIcon, VoiumUi.color(context, if (selected) android.R.color.white else R.color.voium_icon))
    }

    /** 대화에 나온 사람 수. 0이면 숫자 없이 "탑승 인원"으로 보여 줍니다. */
    fun setPassengers(count: Int) {
        val label = if (count > 0) context.getString(R.string.passengers, count) else context.getString(R.string.passengers_unknown)
        if (passengersLabel.text.toString() != label) passengersLabel.text = label
        passengersChip.contentDescription = label
    }
}
