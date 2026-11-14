package com.example.pleos

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * 자막 화면 위쪽의 상단 바(include_status_row, Figma 243:4249).
 * - 왼쪽: 상태 점과 글자(듣는 중 초록, 대기·일시 정지·연결 중 회색, 마이크 오류 빨강)
 * - 오른쪽: 도움말·설정 아이콘 버튼, 대화 종료(듣는 중·오류일 때만)
 */
class StatusRow(activity: Activity) {
    enum class Kind { LISTENING, IDLE, ERROR }

    val endButton: View = activity.findViewById(R.id.end_button)
    val helpButton: View = activity.findViewById(R.id.help_button)
    val statusButton: View = activity.findViewById(R.id.status_button)
    val statusIcon: ImageView = activity.findViewById(R.id.status_icon)
    val statusLabel: TextView = activity.findViewById(R.id.status_label)
    val settingsButton: View = activity.findViewById(R.id.settings_button)
    private val context = activity

    /** listening이면 초록 "듣는 중" 모양, 아니면 회색(자막 일시 정지 등). */
    fun setStatus(label: String, listening: Boolean) = setStatus(label, if (listening) Kind.LISTENING else Kind.IDLE)

    fun setStatus(label: String, kind: Kind) {
        statusLabel.text = label
        statusIcon.setImageResource(
            when (kind) {
                Kind.LISTENING -> R.drawable.ic_status_dot_listening
                Kind.IDLE -> R.drawable.ic_status_dot_idle
                Kind.ERROR -> R.drawable.ic_status_dot_error
            },
        )
        statusLabel.setTextColor(
            VoiumUi.color(
                context,
                when (kind) {
                    Kind.LISTENING -> R.color.voium_listening_text
                    Kind.IDLE -> R.color.voium_idle_text
                    Kind.ERROR -> R.color.voium_error_text
                },
            ),
        )
        updateDescription()
    }

    /** 대화 종료는 듣는 중·오류일 때만 보입니다. */
    fun setEndVisible(visible: Boolean) {
        endButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateDescription() {
        statusButton.contentDescription = statusLabel.text
    }
}
