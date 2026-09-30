package com.linetrans.app.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 离线本地词库（英汉）。
 *
 * 数据来源：
 *  - 内置 `assets/dict/core.tsv`（常用词，随应用打包）
 *  - 用户导入的 `filesDir/dict-import.tsv`（可换成更大的词典，导入后立刻生效）
 *
 * 每行格式：`单词<TAB>音标<TAB>中文释义`（音标可留空）。
 * 查询命中即返回，不联网，所以划词是秒出。
 */
object LocalDictionary {

    data class Item(val word: String, val phonetic: String, val meaning: String)

    /**
     * 查词命中详情：[entry] 是命中的词条，[matched] 是词库里真正命中的那个词，
     * [via] 是命中方式（direct 原形直中 / lemma 词形还原 / variant 规则变形）。
     * 网页端 /api/lookup 需要 matched 与 via，所以单独提供这个重载。
     */
    data class Hit(val entry: Item, val matched: String, val via: String)

    private const val ASSET_PATH = "dict/core.tsv"
    private const val LEMMA_PATH = "dict/lemma.tsv"
    private const val IMPORT_FILE = "dict-import.tsv"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val map = HashMap<String, Item>(40000)
    /** 词形还原：ran → run，用于查不到原形时回退 */
    private val lemmas = HashMap<String, String>(90000)
    /** 用户导入词典收录的词，用于区分 source=local / import */
    private val importedWords = HashSet<String>(1024)

    @Volatile
    private var loaded = false

    /** core.tsv 成功解析的词条数（与网页端 dictStore.entries 同口径） */
    @Volatile
    private var coreEntries = 0

    /** 导入词典成功解析的词条数 */
    @Volatile
    private var importedEntries = 0

    @Volatile
    private var coreReadOk = false

    @Volatile
    private var lemmaReadOk = false

    @Volatile
    private var appContext: Context? = null

    val size: Int get() = map.size

    val isReady: Boolean get() = loaded

    /** 内置 core.tsv 的词条数（真实加载结果，与网页端 /api/dict 的 entries 同口径）。 */
    val entries: Int get() = coreEntries

    /** 用户导入词典的词条数。 */
    val imported: Int get() = importedEntries

    /** 可用词形映射条数。 */
    val lemmaCount: Int get() = lemmas.size

    /** 核心词库与词形表都读到才算就绪（对应网页端 dictStore.ready）。 */
    val dictReady: Boolean get() = loaded && coreReadOk && lemmaReadOk

    /** 词库异常说明；一切正常时为空串（对应网页端 dictStore.error）。 */
    val dictError: String
        get() {
            if (!loaded) return "词库尚未加载"
            if (dictReady) return ""
            val missing = ArrayList<String>(2)
            if (!coreReadOk) missing.add("core.tsv")
            if (!lemmaReadOk) missing.add("lemma.tsv")
            return "词库文件缺失：" + missing.joinToString("、")
        }

    /** 该词是否来自用户导入的词典（/api/lookup 用它区分 source=local / import）。 */
    fun isImportedWord(word: String): Boolean = importedWords.contains(word)

    /** 应用启动时调用；重复调用无副作用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (loaded) return
        scope.launch { ensureLoaded() }
    }

    suspend fun ensureLoaded(): Boolean {
        val context = appContext ?: return false
        return ensureLoaded(context)
    }

    suspend fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        mutex.withLock {
            if (loaded) return true
            val target = HashMap<String, Item>(40000)
            val lemmaTarget = HashMap<String, String>(90000)
            val importedTarget = HashSet<String>(1024)
            var coreCount = 0
            var importCount = 0
            val coreRead = runCatching {
                context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (parseLine(line, target) != null) coreCount++ }
                }
            }.isSuccess
            val lemmaRead = runCatching {
                context.assets.open(LEMMA_PATH).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank() || line[0] == '#') return@forEach
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val form = parts[0].trim().lowercase()
                            val base = parts[1].trim().lowercase()
                            if (form.isNotEmpty() && base.isNotEmpty()) lemmaTarget[form] = base
                        }
                    }
                }
            }.isSuccess
            runCatching {
                val file = File(context.filesDir, IMPORT_FILE)
                if (file.exists()) {
                    file.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            parseLine(line, target)?.let { word ->
                                importCount++
                                importedTarget.add(word)
                            }
                        }
                    }
                }
            }
            map.clear()
            map.putAll(target)
            lemmas.clear()
            lemmas.putAll(lemmaTarget)
            importedWords.clear()
            importedWords.addAll(importedTarget)
            coreEntries = coreCount
            importedEntries = importCount
            coreReadOk = coreRead
            lemmaReadOk = lemmaRead
            loaded = true
            return true
        }
    }

    /** 解析一行词库，返回入库的词（未解析成功返回 null）——与网页端 parseDictLine 一致。 */
    private fun parseLine(line: String, target: HashMap<String, Item>): String? {
        if (line.isEmpty() || line[0] == '#') return null
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val word = parts[0].trim().lowercase()
        if (word.isEmpty()) return null
        val (phonetic, meaning) = if (parts.size >= 3) {
            parts[1].trim() to parts.drop(2).joinToString(" ").trim()
        } else {
            "" to parts[1].trim()
        }
        if (meaning.isEmpty()) return null
        // 用户导入的词典覆盖内置条目
        target[word] = Item(word, phonetic, meaning)
        return word
    }

    /** 取词清洗：trim → 去掉首尾非字母且非 - ' 的字符 → 转小写（与网页端 normalizeWord 一致）。 */
    fun normalize(raw: String): String =
        raw.trim().trim { !it.isLetter() && it != '-' && it != '\'' }.lowercase()

    /** 查词：直接命中 + 常见词形变化。 */
    fun lookup(raw: String): Item? = lookupDetail(raw)?.entry

    /**
     * 查词并返回命中详情（含 matched 与 via），供网页端 /api/lookup 使用。
     * 顺序与网页端 localLookup 一致：原形直中 → lemma 词形还原 → 规则变形。
     */
    fun lookupDetail(raw: String): Hit? {
        if (!loaded) return null
        val word = normalize(raw)
        if (word.isEmpty()) return null
        map[word]?.let { return Hit(it, word, "direct") }
        lemmas[word]?.let { base -> map[base]?.let { return Hit(it, base, "lemma") } }
        for (variant in variants(word)) {
            map[variant]?.let { return Hit(it, variant, "variant") }
            lemmas[variant]?.let { base -> map[base]?.let { return Hit(it, base, "variant") } }
        }
        return null
    }

    private fun variants(word: String): List<String> {
        val out = ArrayList<String>(8)
        fun add(w: String) { if (w.length >= 2 && w != word) out.add(w) }
        if (word.endsWith("ies") && word.length > 4) add(word.dropLast(3) + "y")
        if (word.endsWith("es") && word.length > 3) add(word.dropLast(2))
        if (word.endsWith("s") && word.length > 2) add(word.dropLast(1))
        if (word.endsWith("ing") && word.length > 5) {
            add(word.dropLast(3))
            add(word.dropLast(3) + "e")
        }
        if (word.endsWith("ed") && word.length > 4) {
            add(word.dropLast(2))
            add(word.dropLast(1))
        }
        if (word.endsWith("er") && word.length > 4) {
            add(word.dropLast(2))
            add(word.dropLast(1))
        }
        if (word.endsWith("est") && word.length > 5) add(word.dropLast(3))
        if (word.endsWith("ly") && word.length > 4) add(word.dropLast(2))
        return out
    }

    /** 导入外部词典文件（每行 word TAB [音标 TAB] 释义），成功后立即重新加载。 */
    fun importFrom(context: Context, text: String): Int =
        writeImport(context, text.lineSequence())

    /**
     * 从文件导入词典（流式读取，支持几十万行的词典文件）。
     * 支持两种格式：
     *  1. 制表符：`单词[TAB]音标[TAB]释义` 或 `单词[TAB]释义`
     *  2. ECDICT 的 CSV：`word,phonetic,definition,translation,...`
     */
    fun importFromUri(context: Context, uri: android.net.Uri): Int {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取文件")
        val count = input.bufferedReader().use { reader -> writeImport(context, reader.lineSequence()) }
        return count
    }

    private fun writeImport(context: Context, lines: Sequence<String>): Int {
        val target = File(context.filesDir, IMPORT_FILE)
        var count = 0
        target.outputStream().bufferedWriter().use { writer ->
            lines.forEach { raw ->
                val parsed = parseImportLine(raw) ?: return@forEach
                writer.append(parsed).append('\n')
                count++
            }
        }
        if (count == 0) {
            target.delete()
            return 0
        }
        reload(context)
        return count
    }

    /** 解析一行导入数据，返回规范化的 `词\t音标\t释义`；不合格返回 null。 */
    private fun parseImportLine(raw: String): String? {
        val line = raw.trimEnd()
        if (line.isBlank() || line.startsWith("#")) return null
        val parsed: Triple<String, String, String>? = if (line.contains('\t')) {
            val parts = line.split('\t').map { it.trim() }
            when {
                parts.size < 2 -> null
                parts.size >= 3 -> Triple(parts[0], parts[1], parts.drop(2).joinToString(" "))
                else -> Triple(parts[0], "", parts[1])
            }
        } else if (line.contains(',')) {
            val parts = com.linetrans.app.util.TextParser.splitCsvLine(line).map { it.trim().trim('"') }
            if (parts.size < 4) null else Triple(parts[0], parts[1], parts[3].ifBlank { parts[2] })
        } else {
            null
        }
        val (word, phonetic, meaning) = parsed ?: return null
        if (word.isBlank() || meaning.isBlank()) return null
        // 只保留带中文的释义，避免把纯英文释义也塞进来
        if (meaning.none { it.code in 0x4E00..0x9FFF }) return null
        val normalizedMeaning = meaning.replace("\\n", "；").replace("\n", "；").trim()
        return word.trim().lowercase() + "\t" + phonetic.trim() + "\t" + normalizedMeaning
    }

    fun clearImport(context: Context) {
        File(context.filesDir, IMPORT_FILE).delete()
        reload(context)
    }

    fun hasImport(context: Context): Boolean = File(context.filesDir, IMPORT_FILE).exists()

    private fun reload(context: Context) {
        loaded = false
        map.clear()
        appContext = context.applicationContext
        scope.launch { ensureLoaded() }
    }
}
