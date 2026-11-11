package com.example.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.Locale

/**
 * 디버그 전용: Pleos SpeechToText SDK가 이 기기에서 어떻게 동작하는지 로그로 확인합니다.
 *
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es stt device   (ON_DEVICE, SDK가 직접 녹음)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es stt server   (SERVER)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es stt hybrid   (HYBRID)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es stt feed     (ON_DEVICE, 앱이 녹음해 sendAudio)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es stt stop
 * 로그: adb logcat -s SttProbe
 */
object PleosSttProbe {
    private const val TAG = "SttProbe"
    private var stt: SpeechToText? = null
    private var feeder: Thread? = null
    @Volatile private var feeding = false

    fun handle(context: Context, command: String) {
        when (command) {
            "stop" -> stop()
            "again" -> stt?.request().also { Log.i(TAG, "request again") }
            "device" -> start(context, Mode.ON_DEVICE, feed = false)
            "server" -> start(context, Mode.SERVER, feed = false)
            "hybrid" -> start(context, Mode.HYBRID, feed = false)
            "feed" -> start(context, Mode.ON_DEVICE, feed = true)
            // files/probe.wav (16kHz mono 16bit)를 실제 속도로 sendAudio. fileonly는 request()를 부르지 않음
            "file" -> start(context, Mode.ON_DEVICE, feed = false, file = true)
            "fileonly" -> start(context, Mode.ON_DEVICE, feed = false, file = true, request = false)
        }
    }

    private fun start(context: Context, mode: Mode, feed: Boolean, file: Boolean = false, request: Boolean = true) {
        stop()
        val app = context.applicationContext
        Log.i(TAG, "start mode=$mode feed=$feed")
        val engine = SpeechToText(app, mode)
        stt = engine
        engine.addListener(object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) = Log.i(TAG, "onUpdated final=$isFinal text=$text").let { }
            override fun onUpdatedEpdData(on: Long, off: Long) = Log.i(TAG, "epd on=$on off=$off").let { }
            override fun onStartedRecognition() = Log.i(TAG, "started").let { }
            override fun onEndedRecognition() = Log.i(TAG, "ended").let { }
            override fun onError(type: ErrorType) = Log.w(TAG, "error $type").let { }
            override fun onReady() = Log.i(TAG, "ready").let { }
            override fun onUpdatedRms(rms: Double) = Unit
        })
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.i(TAG, "init ok")
                runCatching { engine.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "setLocale", it) }
                if (request) runCatching { engine.request() }.onFailure { Log.w(TAG, "request", it) }
                if (feed) startFeeding(engine)
                if (file) feedFile(engine, java.io.File(app.filesDir, "probe.wav"))
            }

            override fun onFailure(type: ErrorType) = Log.w(TAG, "init fail $type").let { }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startFeeding(engine: SpeechToText) {
        feeding = true
        feeder = Thread {
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 4)
            record.startRecording()
            val chunk = ByteArray(rate / 10 * 2) // 100ms
            var sent = 0L
            while (feeding) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) {
                    engine.sendAudio(chunk.copyOf(n))
                    sent += n
                    if (sent % (rate * 2 * 5) < n) Log.i(TAG, "fed ${sent / (rate * 2)}s")
                }
            }
            record.stop()
            record.release()
            runCatching { engine.completeAudioSend() }
        }.apply { start() }
    }

    private fun feedFile(engine: SpeechToText, wav: java.io.File) {
        feeding = true
        feeder = Thread {
            val bytes = wav.readBytes()
            Log.i(TAG, "file ${bytes.size} bytes")
            var at = String(bytes, Charsets.ISO_8859_1).indexOf("data").let { if (it < 0) 44 else it + 8 }
            val chunk = 3200 // 100ms
            while (feeding && at < bytes.size) {
                val end = minOf(at + chunk, bytes.size)
                engine.sendAudio(bytes.copyOfRange(at, end))
                at = end
                Thread.sleep(100)
            }
            // 끝에 1.5초 무음
            repeat(15) { if (feeding) { engine.sendAudio(ByteArray(chunk)); Thread.sleep(100) } }
            Log.i(TAG, "file done")
            runCatching { engine.completeAudioSend() }
        }.apply { start() }
    }

    fun stop() {
        feeding = false
        feeder?.join(500)
        feeder = null
        stt?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
            Log.i(TAG, "released")
        }
        stt = null
    }
}
