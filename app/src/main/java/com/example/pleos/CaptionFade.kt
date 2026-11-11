package com.example.pleos

/**
 * 실시간 자막에서 어떤 글자를 서서히 나타나게 할지 정합니다.
 *
 * 글자마다 "나타나기 시작한 시각"을 둡니다. 0이면 이미 다 보이는 글자입니다.
 * - 앞뒤로 같은 글자(이미 보이던 글자)는 그 상태를 그대로 이어 갑니다. 진해지던 글자는 이어서 진해집니다.
 * - 가운데가 고쳐진 글자("저녁 먹었습니다" → "저녁을 먹었습니다")는 애니메이션 없이 바로 바꿉니다.
 * - 끝에 새 말이 붙었을 때만 그 부분을 서서히 나타나게 합니다.
 *   마침표·쉼표·공백만 붙은 경우("먹었습니다" → "먹었습니다.")는 바로 보여 줍니다.
 * - 이 문단에서 한 번이라도 보였던 단어는 다시 서서히 나타나게 하지 않습니다.
 *   확정 결과가 오면서 예비 글자 끝부분이 잠깐 빠졌다가 다시 붙어도 애니메이션 없이 바로 보입니다.
 *   즉 애니메이션은 단어가 처음 화면에 나타날 때 한 번뿐입니다.
 */
object CaptionFade {

    /**
     * @param old 지금 화면에 보이는 글자
     * @param new 새로 보여 줄 글자
     * @param oldStarts old의 글자별 시작 시각
     * @param now 지금 시각
     * @param fadeFrom 이 위치 앞의 글자는 새로 붙어도 서서히 나타나게 하지 않습니다(확정 글자 자리).
     * @param enabled false면 새 글자도 모두 바로 보여 줍니다.
     * @param knownWords 이 문단에서 이미 보였던 단어(words로 만든 것). 이 단어들은 다시 서서히 나타나지 않습니다.
     */
    fun plan(
        old: String,
        new: String,
        oldStarts: LongArray,
        now: Long,
        fadeFrom: Int,
        enabled: Boolean,
        knownWords: Set<String> = emptySet(),
    ): LongArray {
        val prefix = commonPrefix(old, new)
        val suffix = commonSuffix(old, new, limit = minOf(old.length, new.length) - prefix)
        val shift = new.length - old.length
        // 끝에 새로 붙은 부분: 뒤쪽이 old와 하나도 겹치지 않고, old보다 길어진 부분입니다.
        val appendedFrom = if (suffix == 0) maxOf(prefix, old.length) else new.length
        val wordAt = wordKeys(new)
        val tokenAt = tokenIds(new)
        fun isNew(i: Int) = enabled && i >= appendedFrom && i >= fadeFrom &&
            wordAt[i].let { it != null && it !in knownWords }
        // 처음 보는 단어에 붙은 문장부호("했지?"의 "?")는 그 단어와 함께 나타납니다.
        val fadingTokens = (0 until new.length).filter(::isNew).map { tokenAt[it] }.toSet()

        return LongArray(new.length) { i ->
            when {
                i < prefix -> oldStarts.getOrElse(i) { 0L }
                i >= new.length - suffix -> oldStarts.getOrElse(i - shift) { 0L }
                isNew(i) -> now
                enabled && i >= appendedFrom && i >= fadeFrom && tokenAt[i] in fadingTokens -> now
                else -> 0L
            }
        }
    }

    /** 글자마다 몇 번째 어절(공백으로 나눈 덩어리)인지. 공백은 -1입니다. */
    private fun tokenIds(text: String): IntArray {
        val ids = IntArray(text.length) { -1 }
        var token = -1
        var inToken = false
        for (i in text.indices) {
            if (text[i].isWhitespace()) {
                inToken = false
            } else {
                if (!inToken) token++
                inToken = true
                ids[i] = token
            }
        }
        return ids
    }

    /** 글자에 들어 있는 단어들입니다(문장부호를 뺀 글자·숫자). "했지?"와 "했지"는 같은 단어로 봅니다. */
    fun words(text: String): Set<String> = wordKeys(text).filterNotNull().toSet()

    /** 글자마다 그 글자가 속한 단어. 공백이나 문장부호뿐인 자리는 null입니다. */
    private fun wordKeys(text: String): Array<String?> {
        val keys = arrayOfNulls<String>(text.length)
        var start = 0
        while (start < text.length) {
            if (text[start].isWhitespace()) {
                start++
                continue
            }
            var end = start
            while (end < text.length && !text[end].isWhitespace()) end++
            val key = text.substring(start, end).filter { it.isLetterOrDigit() }
            if (key.isNotEmpty()) {
                for (i in start until end) if (text[i].isLetterOrDigit()) keys[i] = key
            }
            start = end
        }
        return keys
    }

    private fun commonPrefix(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var n = 0
        while (n < max && a[n] == b[n]) n++
        return n
    }

    private fun commonSuffix(a: String, b: String, limit: Int): Int {
        var n = 0
        while (n < limit && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }
}
