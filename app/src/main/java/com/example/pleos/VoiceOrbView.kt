package com.example.pleos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 입자 링 모양과 움직임 값입니다(Figma "visualizer · 입자 링").
 * 모양 값은 Figma 링(점 1,700개)을 재서 맞췄고, 움직임 값은 화면에서 보며 정했습니다.
 */
data class OrbStyle(
    /** 점 개수 */
    val pointCount: Int = 1700,
    /** 뷰 크기 대비 링 반지름 (Figma 280/900) */
    val ringRatio: Float = 0.311f,
    /** 링 두께: 점이 링 중심선에서 퍼지는 정도(반지름 대비 표준편차, Figma 39/280) */
    val thickness: Float = 0.141f,
    /** 점 색 */
    val color: Int = Color.parseColor("#4471F2"),
    /** 전체 불투명도(대기 중 회색 링은 0.45) */
    val opacity: Float = 1f,
    /** 가장 작은 점 반지름(뷰 크기 대비, Figma 1.7/900) */
    val dotMinRatio: Float = 0.0019f,
    /** 가장 큰 점 반지름(뷰 크기 대비, Figma 5.6/900) */
    val dotMaxRatio: Float = 0.0062f,
    /** 조용할 때 링이 도는 속도(도/초) */
    val idleSpinDeg: Float = 5f,
    /** 최대 소리일 때 더해지는 회전 속도(도/초) */
    val talkSpinDeg: Float = 22f,
    /** 조용할 때 숨 쉬듯 커졌다 작아지는 정도(반지름 대비) */
    val idleBreath: Float = 0.015f,
    /** 최대 소리일 때 링이 커지는 정도(반지름 대비) */
    val talkSwell: Float = 0.07f,
    /** 최대 소리일 때 링 두께가 늘어나는 비율 */
    val talkSpread: Float = 0.9f,
    /** 조용할 때 링 테두리의 물결 크기(반지름 대비) */
    val idleWave: Float = 0.012f,
    /** 최대 소리일 때 더해지는 물결 크기(반지름 대비) */
    val talkWave: Float = 0.11f,
    /** 물결이 링을 따라 흐르는 속도 */
    val waveSpeed: Float = 0.9f,
    /** 최대 소리일 때 물결 속도 배수 증가량 */
    val talkWaveBoost: Float = 2.2f,
    /** 최대 소리일 때 점이 커지는 비율 */
    val talkDotGrow: Float = 0.25f,
    /** 소리가 커질 때 따라가는 시간(초) */
    val attackSec: Float = 0.07f,
    /** 말이 끝난 뒤 가라앉는 시간(초) */
    val releaseSec: Float = 0.35f,
)

/**
 * 마이크 감도입니다. 같은 목소리도 마이크마다 들어오는 크기가 달라서,
 * 디자이너 값(OrbStyle)과 따로 두고 개발자가 실제 기기에서 재서 정합니다.
 */
data class MicSensitivity(
    /** 무음 기준(dBFS). 이보다 작은 소리는 무시합니다. 기기 소음보다 조금 높게 잡습니다. */
    val silenceDb: Float = -60f,
    /** 최대 기준(dBFS). 이 크기 이상이면 가장 크게 반응합니다. */
    val loudDb: Float = 0f,
) {
    /** 소리 크기(dBFS)를 링이 쓰는 0~1 값으로 바꿉니다. */
    fun levelOf(dbfs: Float): Float =
        ((dbfs - silenceDb) / max(loudDb - silenceDb, 1f)).coerceIn(0f, 1f)
}

/**
 * 점으로 이루어진 링입니다(Figma "visualizer · 입자 링").
 * - 조용할 때: 천천히 돌며 숨 쉬듯 살짝 커졌다 작아집니다.
 * - 말할 때(setLevel): 링이 부풀고 두꺼워지며, 테두리에 물결이 링을 따라 흐릅니다.
 * 링 중심선에서 먼 점일수록 옅게 그려 Figma처럼 가장자리가 흩어져 보입니다.
 */
class VoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 점마다 고정된 값: 링 위 각도, 중심선에서 벗어난 정도(표준편차 단위), 크기, 진하기, 움직임 차이
    private var angle = FloatArray(0)
    private var offset = FloatArray(0)
    private var size = FloatArray(0)
    private var alpha = FloatArray(0)
    private var speed = FloatArray(0)
    private var phase = FloatArray(0)

    var style: OrbStyle = OrbStyle()
        set(value) {
            if (value.pointCount != field.pointCount) buildPoints(value.pointCount)
            field = value
            invalidate()
        }

    var sensitivity: MicSensitivity = MicSensitivity()

    private var targetLevel = 0f
    private var level = 0f
    private var spin = 0f
    private var wave = 0f
    private var time = 0f
    private var lastFrame = SystemClock.uptimeMillis()

    init {
        buildPoints(style.pointCount)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 마이크 입력 크기(0~1)를 전달합니다. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    /** 마이크 소리 크기(dBFS)를 전달합니다. sensitivity 기준으로 0~1로 바꿉니다. */
    fun setDecibel(dbfs: Float) = setLevel(sensitivity.levelOf(dbfs))

    /** 점을 링 둘레에 흩뿌립니다. 매번 같은 모양이 되도록 씨앗값을 고정합니다. */
    private fun buildPoints(count: Int) {
        val random = Random(SEED)
        angle = FloatArray(count)
        offset = FloatArray(count)
        size = FloatArray(count)
        alpha = FloatArray(count)
        speed = FloatArray(count)
        phase = FloatArray(count)
        for (i in 0 until count) {
            angle[i] = (random.nextDouble() * 2 * PI).toFloat()
            // 중심선 근처에 많이, 바깥으로 갈수록 드물게(정규분포). 너무 먼 점은 잘라 냅니다.
            offset[i] = gaussian(random).coerceIn(-3.5f, 3.5f)
            size[i] = random.nextFloat()
            // Figma: 중심선에서 반지름의 약 4% 안쪽은 100%, 바깥으로 갈수록 옅어져 17%까지
            val distance = abs(offset[i]) * OrbStyle().thickness
            alpha[i] = (1f - (distance - 0.03f) / 0.45f).coerceIn(0.17f, 1f)
            speed[i] = 0.6f + random.nextFloat() * 0.8f
            phase[i] = (random.nextDouble() * 2 * PI).toFloat()
        }
    }

    private fun gaussian(random: Random): Float {
        val u = random.nextDouble().coerceAtLeast(1e-9)
        val v = random.nextDouble()
        return (sqrt(-2 * ln(u)) * cos(2 * PI * v)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = style
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val animate = ValueAnimator.areAnimatorsEnabled()

        // 소리가 커질 때는 attackSec, 작아질 때는 releaseSec 동안 따라갑니다.
        val tau = if (targetLevel > level) s.attackSec else s.releaseSec
        level += (targetLevel - level) * (1f - exp(-dt / max(tau, 0.001f)))

        if (animate) {
            time += dt
            spin += Math.toRadians((s.idleSpinDeg + level * s.talkSpinDeg).toDouble()).toFloat() * dt
            wave += s.waveSpeed * (1f + level * s.talkWaveBoost) * dt
        }

        val box = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val ring = box * s.ringRatio
        val base = ring * (1f + s.idleBreath * sin(time * 0.9f) + level * s.talkSwell)
        val spread = ring * s.thickness * (1f + level * s.talkSpread)
        val waveAmp = ring * (s.idleWave + level * s.talkWave)
        val dotScale = box * (1f + level * s.talkDotGrow)
        val color = s.color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val opacity = s.opacity.coerceIn(0f, 1f)

        for (i in angle.indices) {
            // 점마다 조금씩 다른 속도로 돌아, 링이 한 덩어리로만 돌지 않고 흐르듯 보입니다.
            val a = angle[i] + spin * speed[i]
            // 링 테두리를 따라 흐르는 물결(두 개를 겹쳐 규칙적으로 보이지 않게)
            val ripple = 0.6f * sin(3f * a - 1.7f * wave) + 0.4f * sin(5f * a + 2.3f * wave + 1f)
            // 점 하나하나도 제자리에서 살짝 들썩입니다.
            val jitter = 1f + 0.12f * sin(time * (1.1f + speed[i]) + phase[i])
            val radius = base + waveAmp * ripple + offset[i] * spread * jitter
            val x = cx + cos(a) * radius
            val y = cy + sin(a) * radius
            val dot = (s.dotMinRatio + size[i] * (s.dotMaxRatio - s.dotMinRatio)) * dotScale
            paint.color = Color.argb((alpha[i] * opacity * 255).toInt(), r, g, b)
            canvas.drawCircle(x, y, dot, paint)
        }

        val settled = targetLevel == 0f && level < 0.001f
        if ((animate && isShown) || !settled) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            lastFrame = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val SEED = 7
    }
}
