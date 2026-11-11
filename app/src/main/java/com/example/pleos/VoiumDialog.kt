package com.example.pleos

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 확인 대화상자입니다 (Figma 89:1301 dialog).
 * 제목, 설명, 선택 입력칸, 그리고 왼쪽 취소·오른쪽 주 동작(채운 버튼)을 둡니다.
 *
 * onConfirm이 false를 돌려주면 창을 닫지 않습니다(입력 오류를 보여 줄 때).
 */
object VoiumDialog {

    fun show(
        context: Context,
        title: String,
        confirm: String,
        description: String? = null,
        content: View? = null,
        /** 왼쪽 버튼 글자. 없으면 "취소" */
        cancel: String? = null,
        onConfirm: (Dialog) -> Boolean,
    ): Dialog {
        val dialog = Dialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_voium, null, false)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_description).apply {
            text = description.orEmpty()
            visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<FrameLayout>(R.id.dialog_content).apply {
            if (content != null) {
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                visibility = View.VISIBLE
            }
        }
        view.findViewById<TextView>(R.id.dialog_confirm).apply {
            text = confirm
            setOnClickListener { if (onConfirm(dialog)) dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.dialog_cancel).apply {
            if (cancel != null) text = cancel
            setOnClickListener { dialog.cancel() }
        }

        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Figma 대화상자 너비 760dp. 좁은 화면에서는 양옆 32dp를 남깁니다.
            val metrics = context.resources.displayMetrics
            val width = minOf((760 * metrics.density).toInt(), metrics.widthPixels - (64 * metrics.density).toInt())
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 입력칸이 있으면 바로 키보드를 띄웁니다.
            if (content != null) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        return dialog
    }
}
