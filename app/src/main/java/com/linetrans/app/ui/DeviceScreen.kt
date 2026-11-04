package com.linetrans.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.server.PeerDocSync
import com.linetrans.app.server.WebServerService
import com.linetrans.app.ui.settings.SectionCard
import com.linetrans.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 设备（v1.9.1；v1.9.6 起「直接编辑」改为应用内打开）。
 *
 * v1.9.0 的三端互通只做在内置网页台里，安卓原生界面没有任何入口 ——
 * 用户根本找不到这个功能。这一页把入口补回来：
 *
 *   · 局域网自动发现的设备（同 Wi-Fi 下开着网页服务的手机 / 电脑 / 网页服务端）
 *   · 手填的远程服务器（公网地址 + 令牌）
 *   · 点开一台设备看它的文档，可以「同步到本地」，或「直接编辑」——
 *     v1.9.5 及以前是深链跳浏览器网页台，v1.9.6 起由 [PeerDocSync] 拉一份本地镜像
 *     并直接打开原生翻译页，改动按句自动写回对端（与 PC 端同一套思路）
 *
 * 界面本身不直连对端：所有跨端请求都走本机服务（127.0.0.1:端口）的
 * /api/peers、/api/remote/docs|doc|unit|pull，与网页台用的是同一套契约，改一处两端一致。
 */
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** 设备列表行（HomeScreen 的设备卡也用，保持同包可见）。 */
data class PeerRow(
    val id: String,
    val name: String,
    val platform: String,
    val version: String,
    val url: String,
    val manual: Boolean,
    val online: Boolean
) {
    /** 设备类型的中文标签。 */
    val platformLabel: String
        get() = when {
            manual -> "远程"
            platform == "android" -> "安卓"
            platform == "windows" -> "Windows"
            platform == "web" || platform == "node" -> "网页服务端"
            platform.isBlank() -> "设备"
            else -> platform
        }
}

/** 对端文档列表行（HomeScreen 的设备卡也用，保持同包可见）。 */
data class DocRow(
    val id: String,
    val name: String,
    val unitMode: String,
    val total: Int,
    val done: Int
)

/** 本机服务的薄封装：只做 HTTP + JSON 取字段，逻辑全在 WebApi 那边（HomeScreen 的设备卡也用它）。 */
object DeviceApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun base(): String = "http://127.0.0.1:" + SettingsRepository.settings.webServerPort

    private fun exec(method: String, path: String, body: String? = null): String {
        val builder = Request.Builder().url(base() + path)
        when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(JSON_MEDIA))
            "DELETE" -> builder.delete()
        }
        http.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (text.isBlank()) throw IllegalStateException("本机服务无响应（" + res.code + "）")
            return text
        }
    }

    private fun root(method: String, path: String, body: String? = null): JsonObject {
        val text = exec(method, path, body)
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("服务返回的不是 JSON")
        if (obj.flag("ok") == false) throw IllegalStateException(obj.str("error").ifBlank { "操作失败" })
        return obj
    }

    fun peers(): List<PeerRow> {
        val arr = root("GET", "/api/peers").getAsJsonArray("peers") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            PeerRow(
                id = o.str("id"),
                name = o.str("name").ifBlank { o.str("url") },
                platform = o.str("platform"),
                version = o.str("version"),
                url = o.str("url"),
                manual = o.bool("manual"),
                online = o.bool("online")
            )
        }.filter { it.id.isNotBlank() }
    }

    fun remoteDocs(peerId: String): List<DocRow> {
        val arr = root("GET", "/api/remote/docs?peer=" + enc(peerId)).getAsJsonArray("docs")
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            DocRow(
                id = o.str("id"),
                name = o.str("name").ifBlank { "未命名文档" },
                unitMode = o.str("unitMode"),
                total = o.num("total"),
                done = o.num("done")
            )
        }
    }

    /** 同步到本地：返回新文档名。 */
    fun pull(peerId: String, docId: String): String {
        val body = JsonObject().apply {
            addProperty("peer", peerId)
            addProperty("docId", docId)
        }
        return root("POST", "/api/remote/pull", body.toString()).str("name").ifBlank { "已同步" }
    }

    /** 添加远程服务器：先探活再入册，失败原因直接抛给界面。 */
    fun addPeer(url: String, token: String, name: String): String {
        val body = JsonObject().apply {
            addProperty("url", url)
            addProperty("token", token)
            addProperty("name", name)
        }
        val peer = root("POST", "/api/peers", body.toString()).getAsJsonObject("peer")
        return peer?.str("name")?.ifBlank { url } ?: url
    }

    fun removePeer(id: String) {
        root("DELETE", "/api/peers?id=" + enc(id))
    }

    private fun enc(value: String): String =
        runCatching { java.net.URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: ""

    private fun JsonObject.num(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() } ?: 0

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

    /** ok 字段可能不存在（老服务端），只有明确 false 才算失败。 */
    private fun JsonObject.flag(key: String): Boolean? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(onBack: () -> Unit, onOpenDoc: (docId: String, startIndex: Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var peers by remember { mutableStateOf<List<PeerRow>>(emptyList()) }
    var loadError by remember { mutableStateOf("") }
    var serviceOn by remember { mutableStateOf(WebServerService.isRunning) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var docsOf by remember { mutableStateOf<Map<String, List<DocRow>>>(emptyMap()) }
    var docErrorOf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var addOpen by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var pulling by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }

    // 手填的排前面、自动发现的排后面，界面上分两段显示
    val manualPeers = peers.filter { it.manual }
    val discovered = peers.filter { !it.manual }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun refreshPeers(): Boolean {
        val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.peers() } }
        return result.fold(
            onSuccess = { list -> peers = list; loadError = ""; true },
            onFailure = { e -> loadError = friendlyError(e.message); false }
        )
    }

    // 进页面就把互通服务拉起来（发现线程跟着服务走），之后自适应轮询：
    // 服务还没就绪就 400ms 重试，正常后 3 秒刷一次在线状态。
    LaunchedEffect(refreshTick) {
        val ctx = context.applicationContext
        if (!WebServerService.isRunning) {
            runCatching { WebServerService.start(ctx) }
        }
        while (true) {
            val ok = refreshPeers()
            serviceOn = WebServerService.isRunning
            delay(if (ok) 3000 else 400)
        }
    }

    fun togglePeer(peer: PeerRow) {
        if (expandedId == peer.id) {
            expandedId = null
            return
        }
        expandedId = peer.id
        if (docsOf.containsKey(peer.id)) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.remoteDocs(peer.id) } }
            result.onSuccess { docs -> docsOf = docsOf + (peer.id to docs); docErrorOf = docErrorOf - peer.id }
                .onFailure { e -> docErrorOf = docErrorOf + (peer.id to friendlyError(e.message)) }
        }
    }

    fun pullDoc(peer: PeerRow, doc: DocRow) {
        if (pulling.isNotBlank()) return
        pulling = peer.id + "/" + doc.id
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.pull(peer.id, doc.id) } }
            pulling = ""
            result.onSuccess { name -> notify("已同步到本地：" + name) }
                .onFailure { e -> notify("同步失败：" + friendlyError(e.message)) }
        }
    }

    /**
     * 直接编辑（v1.9.6）：拉对端文档建本地镜像（已有镜像则复用并刷新为对端最新），
     * 然后直接打开原生翻译页 —— 不再跳浏览器。之后翻译页里的改动按句回推对端。
     */
    fun editRemote(peer: PeerRow, doc: DocRow) {
        if (opening.isNotBlank()) return
        opening = peer.id + "/" + doc.id
        scope.launch {
            val result = PeerDocSync.open(peer.id, peer.name, doc.id)
            opening = ""
            result.onSuccess { local ->
                onOpenDoc(local.id, local.nextUndoneIndex(0) ?: 0)
            }.onFailure { e -> notify("打开失败：" + friendlyError(e.message)) }
        }
    }

    fun openConsole(query: String) {
        val settings = SettingsRepository.settings
        val params = ArrayList<String>()
        if (settings.webServerToken.isNotBlank()) {
            params.add("token=" + Uri.encode(settings.webServerToken))
        }
        if (query.isNotBlank()) params.add(query)
        var url = "http://127.0.0.1:" + settings.webServerPort + "/"
        if (params.isNotEmpty()) url += "?" + params.joinToString("&")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { notify("没有可用的浏览器") }
    }

    fun removePeer(peer: PeerRow) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.removePeer(peer.id) } }
            result.onSuccess {
                docsOf = docsOf - peer.id
                notify("已移除：" + peer.name)
                refreshTick++
            }.onFailure { e -> notify("移除失败：" + friendlyError(e.message)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column {
                        Text("设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            peers.count { it.online }.toString() + " 台在线 · 共 " + peers.size + " 台",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 立即搜索：先发探针让在线设备马上回包，再刷新列表
                        com.linetrans.app.server.PeerDiscovery.probeNow()
                        refreshTick++
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "立即搜索")
                    }
                    IconButton(onClick = { addOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加远程服务器")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionCard(
                    title = "本机",
                    subtitle = if (serviceOn) "互通服务运行中" else "互通服务未启动",
                    icon = Icons.Default.Devices
                ) {
                    Text(
                        "设备名：" + com.linetrans.app.server.PeerDiscovery.deviceName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                    Text(
                        "本机地址：http://" + ip + ":" + SettingsRepository.settings.webServerPort,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { openConsole("") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("打开网页翻译台")
                    }
                }
            }

            // ---- 远程服务器（手填的公网 / 跨网地址）----
            item {
                SectionHeader(
                    title = "远程服务器",
                    hint = if (manualPeers.isEmpty()) "" else manualPeers.size.toString() + " 台"
                )
            }
            item {
                androidx.compose.material3.OutlinedButton(
                    onClick = { addOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加远程服务器（地址 + 令牌）")
                }
            }
            if (manualPeers.isEmpty()) {
                item {
                    HintCard(
                        title = "还没添加远程服务器",
                        body = "服务器 / 另一台不同 Wi-Fi 的设备用这里：填 http://地址:端口，" +
                            "对方设了访问令牌就一起填上。"
                    )
                }
            } else {
                items(manualPeers, key = { "m-" + it.id }) { peer ->
                    PeerCard(
                        peer = peer,
                        expanded = expandedId == peer.id,
                        docs = docsOf[peer.id],
                        docError = docErrorOf[peer.id],
                        pullingKey = pulling,
                        openingKey = opening,
                        onToggle = { togglePeer(peer) },
                        onPull = { doc -> pullDoc(peer, doc) },
                        onEdit = { doc -> editRemote(peer, doc) },
                        onRemove = { removePeer(peer) }
                    )
                }
            }

            // ---- 局域网设备（UDP 自动发现）----
            item {
                SectionHeader(
                    title = "局域网设备",
                    hint = if (loadError.isNotBlank()) loadError else "每 3 秒自动刷新",
                    isError = loadError.isNotBlank()
                )
            }
            if (discovered.isEmpty()) {
                item {
                    HintCard(
                        title = "同一 Wi-Fi 里没发现别的设备",
                        body = "1. 手机和电脑要在同一个 Wi-Fi（不要一个连 5G、一个连访客网络）；\n" +
                            "2. 对方要开着 LineTrans（网页端 / PC 端 / 另一台手机）；\n" +
                            "3. 电脑开了防火墙的话，放行 8790 / 8080；\n" +
                            "4. 点右上角 ⟳ 立即搜索，不用等下一轮广播。"
                    )
                }
            } else {
                items(discovered, key = { "d-" + it.id }) { peer ->
                    PeerCard(
                        peer = peer,
                        expanded = expandedId == peer.id,
                        docs = docsOf[peer.id],
                        docError = docErrorOf[peer.id],
                        pullingKey = pulling,
                        openingKey = opening,
                        onToggle = { togglePeer(peer) },
                        onPull = { doc -> pullDoc(peer, doc) },
                        onEdit = { doc -> editRemote(peer, doc) },
                        onRemove = { removePeer(peer) }
                    )
                }
            }

            item {
                Text(
                    "「同步到本地」= 复制一份到本机，对端不变；「直接编辑」= 在应用内改对端那份，" +
                        "每次改动都会自动写回对端（不再需要开浏览器）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (addOpen) {
        AddPeerDialog(
            onDismiss = { addOpen = false },
            onAdded = { name ->
                addOpen = false
                notify("已连接：" + name)
                refreshTick++
            }
        )
    }
}

/**
 * 把底层异常翻成人话 —— 界面上绝不直接甩 OkHttp 原文。
 * 尤其是 Android 9+ 的 CLEARTEXT 限制，用户看到只会一脸问号。
 */
private fun friendlyError(raw: String?): String {
    val text = raw ?: return "未知错误"
    return when {
        text.contains("CLEARTEXT", ignoreCase = true) ->
            "系统禁止明文 HTTP（v1.9.4 起已放开；若还出现请更新客户端）"
        text.contains("refused", ignoreCase = true) || text.contains("Failed to connect", ignoreCase = true) ||
            text.contains("ECONNREFUSED", ignoreCase = true) ->
            "连不上本机互通服务：等一两秒再点右上角刷新"
        text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) ->
            "超时：对方可能不在同一网络，或者没在运行"
        text.contains("401") || text.contains("未授权") ->
            "令牌不对：检查对方设置的访问令牌"
        text.contains("404") -> "对方版本可能太旧（没有这个接口）"
        else -> text.take(120)
    }
}

@Composable
private fun SectionHeader(title: String, hint: String = "", isError: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HintCard(title: String, body: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerRow,
    expanded: Boolean,
    docs: List<DocRow>?,
    docError: String?,
    pullingKey: String,
    openingKey: String,
    onToggle: () -> Unit,
    onPull: (DocRow) -> Unit,
    onEdit: (DocRow) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            if (peer.online) androidx.compose.ui.graphics.Color(0xFF22C55E)
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        peer.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(peer.platformLabel)
                            if (peer.version.isNotBlank()) append(" · v").append(peer.version)
                            if (peer.url.isNotBlank()) append(" · ").append(peer.url)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (peer.manual) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "移除这台远程服务器")
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                when {
                    docError != null -> Text(
                        "连不上：" + docError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    docs == null -> Text(
                        "正在读取文件列表…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    docs.isEmpty() -> Text(
                        "这台设备还没有文档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> docs.forEach { doc ->
                        RemoteDocRow(
                            doc = doc,
                            busy = pullingKey == peer.id + "/" + doc.id,
                            opening = openingKey == peer.id + "/" + doc.id,
                            onPull = { onPull(doc) },
                            onEdit = { onEdit(doc) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteDocRow(
    doc: DocRow,
    busy: Boolean,
    opening: Boolean,
    onPull: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (doc.unitMode == "SENTENCE") "逐句" else "逐行") + " · " + doc.done + "/" + doc.total,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPull, enabled = !busy) {
            Text(if (busy) "同步中…" else "同步到本地")
        }
        TextButton(onClick = onEdit, enabled = !opening) {
            Text(if (opening) "打开中…" else "直接编辑")
        }
    }
}

@Composable
private fun AddPeerDialog(
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加远程服务器") },
        text = {
            Column {
                Text(
                    "填一台跑着 LineTrans 服务端的地址：局域网里的手机 / 电脑，或者公网服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址") },
                    placeholder = { Text("http://192.168.1.10:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    hint = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { DeviceApi.addPeer(url.trim(), token.trim(), name.trim()) }
                        }
                        busy = false
                        result.onSuccess { onAdded(it) }
                            .onFailure { hint = friendlyError(it.message) }
                    }
                }
            ) { Text(if (busy) "连接中…" else "连接并保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
