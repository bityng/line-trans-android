package com.linetrans.app.util

import com.linetrans.app.model.TranslationUnit
import com.linetrans.app.model.UnitMode

object TextParser {

    /** SRT / VTT 时间轴行，例如 `00:00:01,000 --> 00:00:02,500`。 */
    private val timecodeRegex = Regex("^\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->\\s*.*$")

    /** 纯数字序号行（字幕序号、有序列表）。 */
    private val indexOnlyRegex = Regex("^\\d{1,4}[.、)]?$")

    /** Markdown 行首标记。 */
    private val mdPrefixRegex = Regex("^(#{1,6}\\s+|>\\s+|[-*+]\\s+|\\d+\\.\\s+)")

    /**
     * 智能清理：去掉字幕时间轴、序号行与 Markdown 行首标记，并压缩多余空行。
     * 仅在用户勾选“智能清理”时使用。
     */
    fun smartClean(text: String): String {
        val kept = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !timecodeRegex.matches(it) }
            .filter { !indexOnlyRegex.matches(it) }
            .filter { it != "WEBVTT" && !it.startsWith("WEBVTT") }
            .map { mdPrefixRegex.replace(it, "") }
            .filter { it.isNotEmpty() }
        return kept.joinToString("\n")
    }

    /**
     * 判断文本是否像 CSV / 制表符对照表：大部分行都能切成两段。
     */
    fun looksLikeTable(text: String): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }.take(20)
        if (lines.size < 2) return false
        val matched = lines.count { splitSourceTranslation(it) != null }
        return matched >= lines.size * 0.6
    }

    fun parse(text: String, mode: UnitMode): List<TranslationUnit> {
        return when (mode) {
            UnitMode.LINE -> parseLines(text)
            UnitMode.SENTENCE -> parseSentences(text)
        }
    }

    /** 常见缩写，避免 "Mr." "U.S." 被当成句末 */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "a.m", "p.m",
        "no", "fig", "inc", "ltd", "co", "dept", "univ", "approx", "cf", "al", "ibid", "eg", "ie",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun", "u.s", "u.k", "u.n", "d.c",
        "ph.d", "b.a", "m.a", "b.sc", "m.sc", "vol", "pp", "ed", "eds", "trans"
    )

    private const val CLOSERS = "”’\"'）)]》〉」』】〕｝}"

    private fun parseLines(text: String): List<TranslationUnit> {
        return normalizeNewlines(text)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { TranslationUnit(it) }
    }

    /**
     * 逐句切分（比原来的正则更稳）：
     *  - 空行分段；段落内的软换行先合并（英文补空格、中日韩不补）
     *  - 只在真正的句末标点断开：。！？!?… ；不再在英文分号处断开
     *  - 保护小数点（3.14）、网址（example.com）、缩写（Mr. / U.S. / e.g.）与首字母缩写（J. K.）
     *  - 省略号连续的点会压成一个 …，句末的引号/括号跟着上一句
     *  - 只有标点或单字的碎片会并回上一句
     */
    private fun parseSentences(text: String): List<TranslationUnit> {
        val raw = mutableListOf<String>()
        normalizeNewlines(text).split(Regex("\n[ \t]*\n+")).forEach { paragraph ->
            val lines = paragraph.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEach
            splitInto(raw, joinSoftLines(lines))
        }
        val merged = mutableListOf<String>()
        raw.forEach { sentence ->
            val core = sentence.filter { it.isLetterOrDigit() }
            if (merged.isNotEmpty() && core.length <= 1) {
                merged[merged.lastIndex] = merged.last() + sentence
            } else {
                merged.add(sentence)
            }
        }
        return merged
            .map { it.replace(Regex("[ \t]{2,}"), " ").trim() }
            .filter { it.isNotEmpty() }
            .map { TranslationUnit(it) }
    }

    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** 段落内的换行多为排版软换行，合并成一行再断句。 */
    private fun joinSoftLines(lines: List<String>): String {
        val sb = StringBuilder(lines.first())
        for (i in 1 until lines.size) {
            val prev = sb.last()
            val next = lines[i].first()
            val latinPrev = (prev in 'A'..'Z' || prev in 'a'..'z' || prev.isDigit() ||
                prev in ".,;:!?)]}\"'”’")
            val latinNext = (next in 'A'..'Z' || next in 'a'..'z' || next.isDigit() ||
                next in "([{\"'“‘")
            val cjk = isCjk(prev) || isCjk(next)
            if (latinPrev && latinNext && !cjk) sb.append(' ')
            sb.append(lines[i])
        }
        return sb.toString()
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) || (code in 0x3400..0x4DBF) || (code in 0x4E00..0x9FFF) ||
            (code in 0xF900..0xFAFF) || (code in 0xFF66..0xFF9F)
    }

    private fun isSentenceEnd(ch: Char): Boolean =
        ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '…' || ch == '；'

    private fun isAbbreviation(prefix: String): Boolean {
        val match = Regex("([A-Za-z][A-Za-z.]*)$").find(prefix) ?: return false
        val rawToken = match.groupValues[1]
        val token = rawToken.lowercase().trimEnd('.')
        if (token.length == 1 && rawToken.trim().firstOrNull()?.isUpperCase() == true) return true
        return ABBREVIATIONS.contains(token)
    }

    private fun splitInto(out: MutableList<String>, text: String) {
        var buffer = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            buffer.append(ch)
            val isDot = ch == '.'
            val isEllipsis = ch == '…'
            val isTripleDot = isDot && i >= 2 && text.substring(i - 2, i + 1) == "..."
            if (!isSentenceEnd(ch) && !isDot) {
                i++
                continue
            }

            if (isEllipsis || isTripleDot) {
                var start = i
                while (start > 0 && (text[start - 1] == '.' || text[start - 1] == '…')) start--
                var end = i + 1
                while (end < text.length && (text[end] == '.' || text[end] == '…')) end++
                val drop = i - start + 1
                buffer.setLength(buffer.length - drop)
                buffer.append('…')
                while (end < text.length && CLOSERS.indexOf(text[end]) >= 0) {
                    buffer.append(text[end])
                    end++
                }
                out.add(buffer.toString().trim())
                buffer = StringBuilder()
                i = end
                continue
            }

            if (isDot) {
                val prev = text.getOrNull(i - 1)
                val next = text.getOrNull(i + 1)
                if (prev != null && next != null && prev.isDigit() && next.isDigit()) { i++; continue }
                if (next != null && (next.isLetterOrDigit())) { i++; continue }
                if (isAbbreviation(buffer.substring(0, buffer.length - 1))) { i++; continue }
                if (next != null && !next.isWhitespace() && CLOSERS.indexOf(next) < 0) { i++; continue }
            }

            var end = i + 1
            while (end < text.length && CLOSERS.indexOf(text[end]) >= 0) {
                buffer.append(text[end])
                end++
            }
            val sentence = buffer.toString().trim()
            if (sentence.isNotEmpty()) out.add(sentence)
            buffer = StringBuilder()
            i = end
        }
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
    }

    fun detectLanguage(text: String): String {
        if (text.isBlank()) return "auto"
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        val kana = text.count { it in '\u3040'..'\u30ff' }
        val hangul = text.count { it in '\uac00'..'\ud7af' }
        val latin = text.count { it.isLetter() && it.code < 128 }
        return when {
            kana > latin -> "ja"
            hangul > latin -> "ko"
            cjk > latin -> "zh-CN"
            latin > cjk -> "en"
            else -> "auto"
        }
    }

    /** 解析一行里已经存在的「原文 ⇥ 译文」（Tab、`,`、`=>`、`|` 分隔）。 */
    fun splitSourceTranslation(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("\t")) {
            val parts = trimmed.split("\t", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0].trim() to parts[1].trim()
            }
        }
        for (sep in listOf("=>", "⇥", "|")) {
            val idx = trimmed.indexOf(sep)
            if (idx > 0) {
                val s = trimmed.substring(0, idx).trim()
                val t = trimmed.substring(idx + sep.length).trim()
                if (s.isNotBlank() && t.isNotBlank()) return s to t
            }
        }
        // CSV：仅当恰好两列且第二列不像纯英文原文时采用
        if (trimmed.contains(",") && !trimmed.contains("，")) {
            val parts = splitCsvLine(trimmed)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0].trim().trim('"') to parts[1].trim().trim('"')
            }
        }
        return null
    }

    /** 简易 CSV 行拆分，支持双引号包裹与转义引号。 */
    fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"" + escaped + "\"" else escaped
    }

    fun buildLineUnit(source: String, translation: String = ""): TranslationUnit =
        TranslationUnit(source, translation)

    /**
     * 切换逐行 / 逐句时把老译文搬到新单元上（v1.9.2）。
     *
     * 以前只认「原文完全一致」的对齐，而切分方式一变原文就变了 —— 切一次译文全丢。
     * 现在按字符区间对齐，三种情况都保住内容：
     *   1. 原文一样 → 直接搬；
     *   2. 一段拆成多句 → 译文能按换行拆成同样份数就逐句给，拆不出就先给第一句（绝不重复）；
     *   3. 多句合成一段 → 用换行接起来，切回去还能原样拆开。
     * 语义与网页端 server.js 的 realignUnits、PC 端 DocRepository.RebuildUnits 一致。
     */
    fun realignUnits(
        sourceText: String,
        previous: List<TranslationUnit>,
        next: List<TranslationUnit>
    ): List<TranslationUnit> {
        val oldSpans = unitSpans(sourceText, previous.map { it.source })
        val newSpans = unitSpans(sourceText, next.map { it.source })

        val exact = HashMap<String, TranslationUnit>()
        previous.forEach { exact[it.source] = it }

        // 新单元整个落在哪个老单元里（= 一段被拆成多句），以及组内序号
        val holder = IntArray(next.size) { -1 }
        for (i in next.indices) {
            val ns = newSpans[i] ?: continue
            for (j in previous.indices) {
                val os = oldSpans[j] ?: continue
                if (os.first <= ns.first && ns.second <= os.second) {
                    holder[i] = j
                    break
                }
            }
        }
        val groupIndex = IntArray(next.size)
        val groupSize = HashMap<Int, Int>()
        val counter = HashMap<Int, Int>()
        for (i in next.indices) {
            val j = holder[i]
            if (j < 0) continue
            val n = counter[j] ?: 0
            groupIndex[i] = n
            counter[j] = n + 1
            groupSize[j] = n + 1
        }

        return next.mapIndexed { i, unit ->
            val prev = exact[unit.source]
            val span = newSpans[i]
            when {
                prev != null -> TranslationUnit(unit.source, prev.translation, prev.done, prev.starred)
                span == null -> TranslationUnit(unit.source)
                holder[i] >= 0 -> {
                    val old = previous[holder[i]]
                    when {
                        old.translation.isEmpty() -> TranslationUnit(unit.source, "", false, old.starred)
                        else -> {
                            val parts = old.translation.split("\n")
                            val size = groupSize[holder[i]] ?: 1
                            when {
                                parts.size == size -> {
                                    val piece = parts.getOrElse(groupIndex[i]) { "" }
                                    TranslationUnit(unit.source, piece, piece.isNotBlank() || old.done, old.starred)
                                }
                                groupIndex[i] == 0 -> TranslationUnit(unit.source, old.translation, true, old.starred)
                                else -> TranslationUnit(unit.source, "", false, old.starred)
                            }
                        }
                    }
                }
                else -> {
                    // 合并：多个老单元落进同一个新单元，用换行接起来
                    val pieces = ArrayList<String>()
                    var starred = false
                    for (k in previous.indices) {
                        val os = oldSpans[k] ?: continue
                        if (!(os.second > span.first && os.first < span.second)) continue
                        val old = previous[k]
                        if (old.starred) starred = true
                        if (old.translation.isNotBlank()) pieces.add(old.translation)
                    }
                    val joined = pieces.joinToString("\n")
                    // 与 server.js / PC 端一致：合并后只要有译文就算 done
                    TranslationUnit(unit.source, joined, joined.isNotBlank(), starred)
                }
            }
        }
    }

    /** 给一组单元在原文里算字符区间 [起, 止)；找不到的记 null。 */
    private fun unitSpans(text: String, sources: List<String>): List<Pair<Int, Int>?> {
        val out = ArrayList<Pair<Int, Int>?>(sources.size)
        var cursor = 0
        for (src in sources) {
            if (src.isEmpty()) {
                out.add(null)
                continue
            }
            var at = text.indexOf(src, cursor)
            if (at < 0) at = text.indexOf(src)
            if (at < 0) {
                out.add(null)
                continue
            }
            out.add(at to (at + src.length))
            if (at + src.length > cursor) cursor = at + src.length
        }
        return out
    }
}
