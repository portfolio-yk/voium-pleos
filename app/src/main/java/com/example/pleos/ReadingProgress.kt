package com.example.pleos

/**
 * 목소리 등록에서 안내 문장을 어디까지 읽었는지 셉니다.
 *
 * 인식된 글자는 띄어쓰기·문장부호가 문장과 다를 수 있어 글자·숫자만 비교합니다.
 * 인식이 한두 글자 틀려도 이어 갈 수 있게, 문장 글자마다 들린 글자를 조금 앞까지(LOOKAHEAD) 찾아보고
 * 연달아 MAX_MISSES번 못 찾으면 거기서 멈춥니다. 들린 글자의 어느 위치부터 읽기 시작했든 가장 멀리 읽은 결과를 씁니다.
 */
object ReadingProgress {
    private const val LOOKAHEAD = 3
    private const val MAX_MISSES = 2

    /** @return sentence에서 읽은 곳의 끝 위치(문자열 인덱스). 끝까지 읽었으면 sentence.length */
    fun readUpTo(sentence: String, heard: String): Int {
        val target = sentence.indices.filter { sentence[it].isLetterOrDigit() }
        val spoken = heard.filter { it.isLetterOrDigit() }
        if (target.isEmpty() || spoken.isEmpty()) return 0

        var best = 0
        for (start in spoken.indices) {
            if (spoken[start] != sentence[target[0]] && (target.size < 2 || spoken[start] != sentence[target[1]])) continue
            best = maxOf(best, matchFrom(sentence, target, spoken, start))
            if (best == target.size) break
        }
        return when {
            best == 0 -> 0
            best == target.size -> sentence.length
            else -> target[best - 1] + 1
        }
    }

    /** 읽은 문장 글자 수(글자·숫자 기준) */
    private fun matchFrom(sentence: String, target: List<Int>, spoken: String, start: Int): Int {
        var j = start
        var matched = 0
        var misses = 0
        for ((k, index) in target.withIndex()) {
            val ch = sentence[index]
            val limit = minOf(spoken.length, j + LOOKAHEAD)
            var found = -1
            for (p in j until limit) if (spoken[p] == ch) { found = p; break }
            if (found >= 0) {
                j = found + 1
                matched = k + 1
                misses = 0
            } else if (++misses > MAX_MISSES) {
                break
            }
        }
        return matched
    }

    /** 문장을 거의 다 읽었는지(끝 글자 하나 정도는 인식이 틀려도 넘어갑니다). */
    fun isComplete(sentence: String, heard: String): Boolean {
        val letters = sentence.count { it.isLetterOrDigit() }
        val read = sentence.substring(0, readUpTo(sentence, heard)).count { it.isLetterOrDigit() }
        return read >= letters - 1
    }

    /**
     * 다시 요청 안내에 쓸, 마지막으로 잘 들린 말입니다(시안: "‘조금’ 다음부터 잘 들리지 않았어요").
     * 단어 중간까지 읽었으면 읽은 부분("조금만"의 "조금"), 그 부분이 한 글자뿐이면 앞 단어입니다. 못 읽었으면 null.
     */
    fun lastWord(sentence: String, upTo: Int): String? {
        if (upTo <= 0) return null
        val words = Regex("[\\p{L}\\p{N}]+").findAll(sentence).toList()
        val inside = words.firstOrNull { upTo - 1 in it.range }
        if (inside != null) {
            val read = sentence.substring(inside.range.first, upTo)
            if (read.length >= 2 || inside.range.first == 0) return read
        }
        return words.lastOrNull { it.range.last < upTo && it != inside }?.value
    }
}
