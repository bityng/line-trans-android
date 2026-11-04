package com.linetrans.app.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.model.TranslationDoc
import com.linetrans.app.model.TranslationUnit
import com.linetrans.app.model.UnitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 远程文档的应用内编辑（v1.9.6）：镜像 + 绑定 + 去抖回推。
 *
 * 与 PC 端（PeerService 的 PullAsync / PushUnit）同一套思路：
 *   · 「直接编辑」把对端文档整篇拉下来，建一个本地**镜像文档**（文件夹「同步」、
 *     名字带「（来自设备名）」），并记住「本地镜像 id → 对端设备 / 文档 id」的绑定；
 *   · 之后翻译页里的每次编辑（译文 / AI 结果 / 收藏 / 原文修改）除照常保存本地外，
 *     还会按句进入待推队列，800ms 合并后经**本机服务**的 /api/remote/unit 代理写回对端 ——
 *     与原来「浏览器深链编辑」走的是同一条链路，只是界面换成了原生翻译页。
 *
 * 绑定持久化在 SharedPreferences 里，重启后继续生效；删除本地镜像时调 [unbind] 清掉。
 * 推送失败静默丢弃（与 PC 端一致），不打断编辑。
 */
object PeerDocSync {
    private const val TAG = "PeerDocSync"
    private const val PREFS = "linetrans_peer_bindings"
    private const val KEY_BINDINGS = "bindings"

    /** 同一句两次编辑的合并窗口：打字停顿后统一推一批。 */
    private const val FLUSH_DELAY_MS = 800L

    /** 本地镜像 id → 对端绑定。 */
    data class Binding(
        val peerId: String,
        val peerName: String,
        val remoteDocId: String
    )

    /** 待推的一条单元修改（快照值，同 key 覆盖 → 同句只发最后一次）。 */
    private data class PendingUnit(
        val binding: Binding,
        val index: Int,
        val translation: String,
        val source: String,
        val done: Boolean,
        val starred: Boolean
    )

    private val gson = Gson()
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val pending = ConcurrentHashMap<String, PendingUnit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var flushScheduled = false

    @Volatile private var appContext: Context? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    private fun load() {
        val ctx = appContext ?: return
        val saved = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BINDINGS, null)
        }.getOrNull() ?: return
        runCatching {
            val type = object : TypeToken<Map<String, Binding>>() {}.type
            val map: Map<String, Binding> = gson.fromJson(saved, type) ?: return
            bindings.putAll(map)
        }.onFailure { Log.w(TAG, "绑定读取失败：" + it.message) }
    }

    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BINDINGS, gson.toJson(bindings.toMap()))
                .apply()
        }
    }

    fun bindingOf(localDocId: String): Binding? = bindings[localDocId]

    /** 已有同源镜像时返回它的本地 id（重复点「直接编辑」不会建出第二份副本）。 */
    fun mirrorOf(peerId: String, remoteDocId: String): String? =
        bindings.entries.firstOrNull { it.value.peerId == peerId && it.value.remoteDocId == remoteDocId }?.key

    /**
     * 打开（或复用）一篇对端文档的本地镜像，供原生翻译页编辑。
     *
     * HTTP 拉取在 IO 线程；镜像的创建 / 刷新与 [DocRepository.save] 在调用方上下文执行
     * （调用方都是主线程协程，文档是 Compose 状态）。重复打开同一篇时复用旧镜像，
     * 并把内容刷新为对端最新（上次编辑已实时写回，正常不会丢东西）；刷新失败时
     * 退回旧镜像继续用，只有第一次（无镜像可退）才真正报错。
     */
    suspend fun open(peerId: String, peerName: String, remoteDocId: String): Result<TranslationDoc> {
        val fetched = withContext(Dispatchers.IO) {
            runCatching { fetchRemoteDoc(peerId, remoteDocId) }
        }
        return fetched.fold(
            onSuccess = { json ->
                runCatching { applyMirror(json, peerId, peerName, remoteDocId) }
            },
            onFailure = { fetchError ->
                // 宽容降级：已有镜像就先用本地的（可能稍旧），别让一次网络抖动挡住编辑
                val localId = mirrorOf(peerId, remoteDocId)
                val existing = localId?.let { DocRepository.get(it) }
                if (existing != null) Result.success(existing)
                else Result.failure(fetchError)
            }
        )
    }

    /** 拉对端整篇（走本机服务的 /api/remote/doc 代理，与网页台深链同一条链路）。 */
    private fun fetchRemoteDoc(peerId: String, remoteDocId: String): String {
        val request = Request.Builder().url(localUrl("api/remote/doc?peer=" + enc(peerId) + "&id=" + enc(remoteDocId)))
            .get()
            .build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("本机服务返回 " + res.code + "：" + text.take(120))
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: throw IllegalStateException("返回的不是 JSON")
            if (root.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == false) {
                throw IllegalStateException(root.get("error")?.takeIf { !it.isJsonNull }?.asString ?: "读取失败")
            }
            return text
        }
    }

    /** 把对端 JSON 落成 / 更新本地镜像，并记录绑定。 */
    private fun applyMirror(json: String, peerId: String, peerName: String, remoteDocId: String): TranslationDoc {
        val root = JsonParser.parseString(json).asJsonObject
        val remoteUnits = parseUnits(root)
        val remoteMode = if (root.get("unitMode")?.takeIf { !it.isJsonNull }?.asString == "SENTENCE") {
            UnitMode.SENTENCE
        } else UnitMode.LINE

        val localId = mirrorOf(peerId, remoteDocId)
        val existing = localId?.let { DocRepository.get(it) }
        val doc = if (existing != null) {
            // 复用镜像：只刷新内容，名字 / 文件夹 / 阅读位置都保持用户熟悉的样子
            existing.units.clear()
            existing.units.addAll(remoteUnits)
            existing.unitMode = remoteMode
            existing.lastIndex = existing.lastIndex.coerceIn(0, (remoteUnits.size - 1).coerceAtLeast(0))
            existing
        } else {
            val baseName = root.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: "未命名文档"
            TranslationDoc(
                id = DocRepository.newId(),
                name = baseName + "（来自" + peerName + "）",
                folder = "同步",
                units = remoteUnits,
                unitMode = remoteMode,
                sourceText = remoteUnits.joinToString("\n") { it.source }
            )
        }
        DocRepository.save(doc, immediate = true)
        bindings[doc.id] = Binding(peerId = peerId, peerName = peerName, remoteDocId = remoteDocId)
        persist()
        return doc
    }

    private fun parseUnits(root: JsonObject): MutableList<TranslationUnit> {
        val units = ArrayList<TranslationUnit>()
        (root.getAsJsonArray("units") ?: com.google.gson.JsonArray()).forEach { el ->
            val u = el.asJsonObject
            units.add(
                TranslationUnit(
                    source = u.get("source")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    translation = u.get("translation")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    done = u.get("done")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    starred = u.get("starred")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                )
            )
        }
        return units
    }

    /**
     * 翻译页每次改动一句后调用：非绑定文档直接返回（零开销），命中的进待推队列，
     * 800ms 合并后经 /api/remote/unit 写回对端。
     */
    fun pushUnit(localDocId: String, index: Int, unit: TranslationUnit) {
        val binding = bindings[localDocId] ?: return
        pending[localDocId + "#" + index] = PendingUnit(
            binding = binding,
            index = index,
            translation = unit.translation,
            source = unit.source,
            done = unit.isDone,
            starred = unit.starred
        )
        scheduleFlush()
    }

    /** 立即推掉待推内容（离开编辑页时调用；不阻塞调用方）。 */
    fun flushNow() {
        scope.launch {
            flushAll()
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }

    /** 删除本地镜像文档时同步清掉绑定，别让回推继续往已删除的文档上打。 */
    fun unbind(localDocId: String) {
        if (bindings.remove(localDocId) != null) persist()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(FLUSH_DELAY_MS)
            flushScheduled = false
            flushAll()
            // flush 期间可能又有新改动落进队列，再来一轮
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }

    private fun flushAll() {
        while (true) {
            val key = pending.keys.firstOrNull() ?: break
            val item = pending.remove(key) ?: continue
            runCatching { pushOne(item) }
                .onFailure { Log.w(TAG, "回推失败（已丢弃，不影响本地）：" + it.message) }
        }
    }

    private fun pushOne(item: PendingUnit) {
        val body = JsonObject().apply {
            addProperty("peer", item.binding.peerId)
            addProperty("docId", item.binding.remoteDocId)
            addProperty("index", item.index)
            addProperty("translation", item.translation)
            addProperty("source", item.source)
            addProperty("done", item.done)
            addProperty("starred", item.starred)
        }
        val request = Request.Builder()
            .url(localUrl("api/remote/unit"))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("本机服务返回 " + res.code + "：" + text.take(120))
        }
    }

    /** 本机服务地址；设了访问令牌时必须带上（WebServer 的校验不豁免 127.0.0.1）。 */
    private fun localUrl(pathAndQuery: String): String {
        var url = "http://127.0.0.1:" + SettingsRepository.settings.webServerPort + "/" + pathAndQuery
        val token = SettingsRepository.settings.webServerToken.trim()
        if (token.isNotEmpty()) {
            url += (if (pathAndQuery.contains('?')) "&" else "?") + "token=" + enc(token)
        }
        return url
    }

    private fun enc(value: String): String =
        runCatching { java.net.URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
