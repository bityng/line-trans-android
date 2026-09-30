package com.linetrans.app.server

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.linetrans.app.BuildConfig
import com.linetrans.app.ai.LocalDictionary
import com.linetrans.app.ai.TranslationService
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.ExportManager
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.model.ExportFormat
import com.linetrans.app.model.TranslationDoc
import com.linetrans.app.model.UnitMode
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 局域网 Web 翻译台的 JSON 接口。
 * 与独立服务端（line-trans-web）保持同一套 API，网页端可无缝切换。
 */
class WebApi(private val context: android.content.Context) {

    private val gson = Gson()
    private val main = Handler(Looper.getMainLooper())
    private val service = TranslationService(context)

    // ---------- DTO ----------

    private data class UnitDto(
        val i: Int,
        val source: String,
        val translation: String,
        val done: Boolean,
        val starred: Boolean
    )

    private data class DocDto(
        val id: String,
        val name: String,
        val folder: String,
        val unitMode: String,
        val total: Int,
        val done: Int,
        val progress: Int,
        val pinned: Boolean,
        val starred: Int,
        val updatedAt: Long
    )

    private data class DocFullDto(
        val id: String,
        val name: String,
        val folder: String,
        val unitMode: String,
        val total: Int,
        val done: Int,
        val progress: Int,
        val pinned: Boolean,
        val units: List<UnitDto>
    )

    private data class InfoDto(
        val name: String,
        val version: String,
        val mode: String,
        val targetLang: String,
        val provider: String?,
        val docCount: Int,
        val canTranslate: Boolean
    )

    private data class OkDto(
        val ok: Boolean,
        val total: Int = 0,
        val done: Int = 0
    )

    private data class AiDto(
        val ok: Boolean,
        val text: String = "",
        val error: String? = null,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cachedTokens: Int = 0,
        val cost: Double = 0.0,
        val total: Int = 0,
        val done: Int = 0
    )

    // ---------- 路由 ----------

    fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        var path = session.uri.removePrefix("/")
        val isPost = session.method == NanoHTTPD.Method.POST
        return when {
            path == "api/info" -> json(info())
            path == "api/docs" -> json(mapOf("docs" to docs()))
            path == "api/doc" -> docDetail(param(session, "id"))
            path == "api/unit" && isPost -> saveUnit(body(session))
            path == "api/doc" && isPost -> patchDoc(body(session))
            path == "api/ai" && isPost -> runAi(body(session))
            path == "api/export" -> export(param(session, "id"), param(session, "format"))
            path == "api/lookup" -> lookup(session)
            path == "api/dict" -> dict()
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json; charset=utf-8",
                "{\"error\":\"unknown api\"}"
            )
        }
    }

    // ---------- 处理 ----------

    private fun info(): InfoDto {
        val settings = SettingsRepository.settings
        return InfoDto(
            name = "LineTrans",
            version = BuildConfig.VERSION_NAME,
            mode = "Android 客户端",
            targetLang = settings.targetLang,
            provider = settings.activeModel?.name,
            docCount = DocRepository.docs.size,
            canTranslate = settings.activeProvider != null
        )
    }

    private fun docs(): List<DocDto> = onMain {
        DocRepository.docs.sortedWith(
            compareByDescending<TranslationDoc> { it.pinned }.thenByDescending { it.updatedAt }
        ).map { d ->
            DocDto(
                id = d.id,
                name = d.name,
                folder = d.folder,
                unitMode = d.unitMode.name,
                total = d.totalCount,
                done = d.translatedCount,
                progress = (d.progress * 100).toInt(),
                pinned = d.pinned,
                starred = d.starredCount,
                updatedAt = d.updatedAt
            )
        }
    } ?: emptyList()

    private fun docDetail(id: String?): NanoHTTPD.Response {
        if (id.isNullOrBlank()) return error("缺少 id 参数")
        val dto = onMain {
            val doc = DocRepository.get(id) ?: return@onMain null
            DocFullDto(
                id = doc.id,
                name = doc.name,
                folder = doc.folder,
                unitMode = doc.unitMode.name,
                total = doc.totalCount,
                done = doc.translatedCount,
                progress = (doc.progress * 100).toInt(),
                pinned = doc.pinned,
                units = doc.units.mapIndexed { i, u ->
                    UnitDto(i, u.source, u.translation, u.isDone, u.starred)
                }
            )
        }
        return dto?.let { json(it) } ?: error("文档不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    private fun saveUnit(payload: String): NanoHTTPD.Response {
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return error("请求体不是合法 JSON")
        val docId = obj.get("docId")?.asString ?: return error("缺少 docId")
        val index = obj.get("index")?.asInt ?: return error("缺少 index")
        val result = onMain {
            val doc = DocRepository.get(docId) ?: return@onMain null
            val unit = doc.units.getOrNull(index) ?: return@onMain null
            obj.get("translation")?.let { if (!it.isJsonNull) unit.translation = it.asString }
            obj.get("source")?.let { if (!it.isJsonNull) unit.source = it.asString }
            obj.get("done")?.let { if (!it.isJsonNull) unit.done = it.asBoolean }
            obj.get("starred")?.let { if (!it.isJsonNull) unit.starred = it.asBoolean }
            if (unit.translation.isNotBlank()) unit.done = true
            DocRepository.save(doc)
            OkDto(ok = true, total = doc.totalCount, done = doc.translatedCount)
        }
        return result?.let { json(it) } ?: error("文档或句子不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    private fun patchDoc(payload: String): NanoHTTPD.Response {
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return error("请求体不是合法 JSON")
        val docId = obj.get("docId")?.asString ?: return error("缺少 docId")
        val result = onMain {
            val doc = DocRepository.get(docId) ?: return@onMain null
            obj.get("name")?.let { if (!it.isJsonNull && it.asString.isNotBlank()) doc.name = it.asString }
            obj.get("folder")?.let { if (!it.isJsonNull) doc.folder = it.asString }
            obj.get("pinned")?.let { if (!it.isJsonNull) doc.pinned = it.asBoolean }
            obj.get("unitMode")?.let { mode ->
                if (!mode.isJsonNull) {
                    val target = if (mode.asString == "SENTENCE") UnitMode.SENTENCE else UnitMode.LINE
                    applyMode(doc, target)
                }
            }
            DocRepository.save(doc, immediate = true)
            OkDto(ok = true, total = doc.totalCount, done = doc.translatedCount)
        }
        return result?.let { json(it) } ?: error("文档不存在", NanoHTTPD.Response.Status.NOT_FOUND)
    }

    /** 与客户端一致：按原文重新切分并保留已有译文。 */
    private fun applyMode(doc: TranslationDoc, target: UnitMode) {
        if (doc.unitMode == target) return
        val source = if (doc.sourceText.isNotBlank()) doc.sourceText
        else doc.units.joinToString("\n") { it.source }
        val old = doc.units.associateBy { it.source }
        val rebuilt = com.linetrans.app.util.TextParser.parse(source, target).map { u ->
            val prev = old[u.source]
            if (prev != null) com.linetrans.app.model.TranslationUnit(u.source, prev.translation, prev.done, prev.starred) else u
        }.toMutableList()
        doc.units.clear()
        doc.units.addAll(rebuilt)
        doc.unitMode = target
    }

    private fun runAi(payload: String): NanoHTTPD.Response {
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return error("请求体不是合法 JSON")
        val docId = obj.get("docId")?.asString ?: return error("缺少 docId")
        val index = obj.get("index")?.asInt ?: return error("缺少 index")
        val settings = SettingsRepository.settings
        if (settings.activeProvider == null) {
            return json(AiDto(ok = false, error = "手机端还没有配置 API 提供商"))
        }
        val snapshot = onMain {
            val doc = DocRepository.get(docId) ?: return@onMain null
            val unit = doc.units.getOrNull(index) ?: return@onMain null
            Triple(doc, unit, doc.units.take(index))
        } ?: return error("文档或句子不存在", NanoHTTPD.Response.Status.NOT_FOUND)

        val (doc, unit, previous) = snapshot
        return try {
            val result = runBlocking {
                service.translate(
                    settings = SettingsRepository.settings,
                    docName = doc.name,
                    unit = unit,
                    mode = doc.unitMode,
                    index = index,
                    total = doc.units.size,
                    previousUnits = previous
                )
            }
            val model = SettingsRepository.settings.activeModel
            val cost = if (model != null) {
                com.linetrans.app.util.CostCalculator.costFor(model, result.promptTokens, result.completionTokens)
            } else 0.0
            val progress = onMain {
                unit.translation = result.text
                unit.done = true
                DocRepository.save(doc)
                OkDto(ok = true, total = doc.totalCount, done = doc.translatedCount)
            } ?: OkDto(ok = true)
            json(
                AiDto(
                    ok = true,
                    text = result.text,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    cachedTokens = result.cachedTokens,
                    cost = cost,
                    total = progress.total,
                    done = progress.done
                )
            )
        } catch (e: Exception) {
            json(AiDto(ok = false, error = e.message ?: "翻译失败"))
        }
    }

    private fun export(id: String?, format: String?): NanoHTTPD.Response {
        if (id.isNullOrBlank()) return error("缺少 id 参数")
        val parsed = when (format) {
            "md" -> ExportFormat.MARKDOWN_TABLE
            "csv" -> ExportFormat.CSV
            "json" -> ExportFormat.JSON
            "txt_translated" -> ExportFormat.TXT_TRANSLATED_ONLY
            "txt_source" -> ExportFormat.TXT_SOURCE_FALLBACK
            else -> ExportFormat.TXT_BILINGUAL
        }
        val doc = onMain { DocRepository.get(id) } ?: return error("文档不存在", NanoHTTPD.Response.Status.NOT_FOUND)
        val text = ExportManager.buildText(doc, parsed)
        val name = ExportManager.buildFileName(doc, parsed)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/plain; charset=utf-8",
            text
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(name, "UTF-8") + "\"")
        }
    }

    // ---------- 划词查义（与网页端 /api/lookup、/api/dict 同契约） ----------

    /**
     * 划词查义：本地离线词库优先（direct → lemma → variant），未命中时可用 AI 兜底。
     * 参数与返回结构与网页端 line-trans-web 的 `/api/lookup` 完全一致。
     */
    private fun lookup(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val word = LocalDictionary.normalize(param(session, "word") ?: "")
        // 空词只回 ok/error：带空 word 会让网页端误判成「不是这次查的词」而丢弃回包
        if (word.isEmpty()) return json(errorBody("word 不能为空"))
        val query = if (word.length > 64) word.substring(0, 64) else word   // 超长词直接截断

        val langParam = param(session, "lang")
        val lang = when {
            langParam == "zh" || langParam == "both" || langParam == "en" -> langParam
            langParam == null -> definitionLanguage()
            else -> "zh"                                                     // 非法值按 zh
        }

        // 首次请求时懒加载，加载中的请求等加载完成再返回（与网页端 loadDict() 一致）
        runCatching { runBlocking { LocalDictionary.ensureLoaded(context) } }

        LocalDictionary.lookupDetail(query)?.let { hit ->
            val source = if (LocalDictionary.isImportedWord(hit.entry.word)) "import" else "local"
            return json(JsonObject().apply {
                addProperty("ok", true)
                addProperty("word", query)
                addProperty("found", true)
                addProperty("matched", hit.matched)
                addProperty("via", hit.via)
                addProperty("phonetic", hit.entry.phonetic)
                addProperty("meaning", hit.entry.meaning)
                addProperty("source", source)
            })
        }

        // 网页端未传 ai 时默认不兜底（对端 config.lookup.aiFallback 默认 false）
        val allowAi = param(session, "ai") == "1"
        if (allowAi && SettingsRepository.settings.activeProvider != null) {
            // 兜底失败不影响本地查询结果（与网页端 try/catch 行为一致）
            val text = runCatching { aiLookup(query, lang) }.getOrNull()
            if (!text.isNullOrBlank()) {
                return json(JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("word", query)
                    addProperty("found", true)
                    addProperty("matched", query)
                    addProperty("via", "ai")
                    addProperty("phonetic", "")
                    addProperty("meaning", text)
                    addProperty("source", "ai")
                })
            }
        }

        return json(JsonObject().apply {
            addProperty("ok", true)
            addProperty("word", query)
            addProperty("found", false)
            add("matched", JsonNull.INSTANCE)
            add("via", JsonNull.INSTANCE)
            addProperty("source", "none")
        })
    }

    /** 词库状态：entries / lemma / imported 都取自真实加载结果，不写死。 */
    private fun dict(): NanoHTTPD.Response {
        runCatching { runBlocking { LocalDictionary.ensureLoaded(context) } }
        return json(JsonObject().apply {
            addProperty("ok", true)
            addProperty("ready", LocalDictionary.dictReady)
            addProperty("entries", LocalDictionary.entries)
            addProperty("lemma", LocalDictionary.lemmaCount)
            addProperty("imported", LocalDictionary.imported)
            if (LocalDictionary.dictError.isNotEmpty()) addProperty("error", LocalDictionary.dictError)
        })
    }

    /** AI 兜底释义：提示词与网页端 aiLookup 一致；不可用时返回 null。 */
    private fun aiLookup(word: String, lang: String): String? {
        val settings = SettingsRepository.settings
        if (settings.activeProvider == null) return null
        val rule = when (lang) {
            "en" -> "用简明英文给出释义。"
            "both" -> "先给出中文释义，再换行给出对应的英文释义。"
            else -> "给出简明中文释义，并标注词性（如 n. / v. / adj.）。"
        }
        val system = "你是英汉词典。用户会给你一个英语单词，请" + rule +
            "只输出释义本身：不要例句，不要解释，不要重复单词，不要客套话。"
        val text = runBlocking { service.rawChat(settings, system, word) }.trim()
        return text.ifBlank { null }
    }

    /** 释义语言：非法值按 zh（对应网页端 config.definitionLanguage 的处理）。 */
    private fun definitionLanguage(): String = when (SettingsRepository.settings.definitionLanguage) {
        "zh", "both", "en" -> SettingsRepository.settings.definitionLanguage
        else -> "zh"
    }

    private fun errorBody(message: String): JsonObject = JsonObject().apply {
        addProperty("ok", false)
        addProperty("error", message)
    }

    // ---------- 工具 ----------

    private fun json(value: Any): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(value)
        )

    private fun error(message: String, status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.BAD_REQUEST): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", "{\"error\":" + gson.toJson(message) + "}")

    private fun param(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters?.get(name)?.firstOrNull()

    private fun body(session: NanoHTTPD.IHTTPSession): String = try {
        val files = HashMap<String, String>()
        session.parseBody(files)
        files["postData"] ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 文档数据是 Compose 状态，统一回到主线程读写，避免并发问题。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching { block() }.getOrNull()
            latch.countDown()
        }
        return if (latch.await(8, TimeUnit.SECONDS)) result else null
    }
}
