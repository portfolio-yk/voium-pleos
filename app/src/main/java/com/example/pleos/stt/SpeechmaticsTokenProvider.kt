package com.example.pleos.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 사용자에게 그대로 보여 줄 수 있는 음성 인식 오류입니다. */
class SttException(message: String) : Exception(message)

/**
 * API 키로 유효기간 60초의 실시간 연결용 임시 키(JWT)를 발급합니다.
 * 유효기간은 새 연결을 시작할 수 있는 시간이며, 이미 연결된 세션을 끊지 않습니다.
 *
 * 주의: 이 방식은 API 키가 APK 안에 들어갑니다. 테스트용이며, 배포할 때는
 * 서버가 임시 키를 발급하고 앱은 임시 키만 받도록 바꿔야 합니다.
 */
class SpeechmaticsTokenProvider(
    private val apiKey: String,
    client: OkHttpClient,
) {
    private val client = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun fetch(): String = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw SttException("local.properties에 speechmatics.apiKey를 설정한 뒤 앱을 다시 빌드해 주세요.")
        }
        val body = """{"ttl":60}""".toRequestBody("application/json".toMediaType())
        for ((index, endpoint) in ENDPOINTS.withIndex()) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    // 경로가 없을 때만 다음 주소를 시도합니다. 인증 실패나 요청 제한은 재시도하지 않습니다.
                    if (response.code == 404 && index < ENDPOINTS.lastIndex) return@use
                    when {
                        response.code == 401 || response.code == 403 ->
                            throw SttException("Speechmatics 인증에 실패했어요. API 키와 실시간 인식 이용 권한을 확인해 주세요.")
                        response.code == 429 ->
                            throw SttException("Speechmatics 연결 요청이 많아요. 잠시 후 다시 시도해 주세요.")
                        !response.isSuccessful ->
                            throw SttException("Speechmatics에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.")
                    }
                    val token = runCatching { JSONObject(response.body?.string().orEmpty()) }
                        .getOrNull()?.stringOrNull("key_value")?.trim()
                    if (token.isNullOrEmpty()) {
                        throw SttException("Speechmatics 연결 토큰을 받지 못했어요. 잠시 후 다시 시도해 주세요.")
                    }
                    return@withContext token
                }
            } catch (e: IOException) {
                // 원본 응답 내용에는 계정 정보가 들어 있을 수 있어 그대로 보여 주지 않습니다.
                throw SttException("연결 요청에 응답이 없어요. 네트워크 상태를 확인하고 다시 시도해 주세요.")
            }
        }
        throw SttException("Speechmatics에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.")
    }

    private companion object {
        // 현재 문서는 /v1/jwts를 안내합니다. 아래 두 주소는 이전 경로이며, 404일 때만 사용합니다.
        val ENDPOINTS = listOf(
            "https://mp.speechmatics.com/v1/jwts?type=rt",
            "https://mp.speechmatics.com/v1/api-keys?type=rt",
            "https://mp.speechmatics.com/v1/api_keys?type=rt",
        )
    }
}
