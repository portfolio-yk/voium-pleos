package com.example.pleos.ai

import android.content.Context

/**
 * 대화 기록 요약과 대화 제안이 쓰는 AI 글 생성 창구입니다.
 * Claude API 키가 있으면 Claude를, 없으면 Pleos Gleo AI를 씁니다.
 * (Pleos 에뮬레이터 v2.1.0부터 LLM SDK 지원이 끝나 Gleo AI는 답하지 않을 수 있습니다.)
 */
object TextAi {
    val isConfigured get() = ClaudeAi.isConfigured || GleoAi.isConfigured

    /** 곧 AI를 부를 것 같을 때(대화 시작) 연결을 미리 준비합니다. */
    fun warmUp() {
        if (ClaudeAi.isConfigured) ClaudeAi.warmUp()
    }

    fun generate(context: Context, prompt: String, timeoutMs: Long, onResult: (String?) -> Unit) {
        if (ClaudeAi.isConfigured) ClaudeAi.generate(prompt, timeoutMs, onResult)
        else GleoAi.generate(context, prompt, timeoutMs, onResult)
    }
}
