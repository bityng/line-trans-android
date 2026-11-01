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
 * 设备（v1.9.1）。
 *
 * v1.9.0 的三端互通只做在内置网页台里，安卓原生界面没有任何入口 ——
 * 用户根本找不到这个功能。这一页把入口补回来：
 *
 *   · 局域网自动发现的设备（同 Wi-Fi 下开着网页服务的手机 / 电脑 / 网页服务端）
 *   · 手填的远程服务器（公网地址 + 令牌）
 *   · 点开一台设备看它的文档，可以「同步到本地」或「直接编辑」（深链到网页台）
 *
 * 界面本身不直连对端：所有跨端请求都走本机服务（127.0.0.1:端口）的
 * /api/peers、/api/remote/docs|doc|unit|pull，与网页台用的是同一套契约，改一处两端一致。
 */
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private data class PeerRow(
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

private data class DocRow(
    val id: String,
    val name: String,
    val unitMode: String,
    val total: Int,
    val done: Int
)

/** 本机服务的薄封装：只做 HTTP + JSON 取字段，逻辑全在 WebApi 那边。 */
private object DeviceApi {
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
fun DeviceScreen(onBack: () -> Unit) {
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

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun refreshPeers(): Boolean {
        val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.peers() } }
        return result.fold(
            onSuccess = { list -> peers = list; loadError = ""; true },
            onFailure = { e -> loadError = e.message ?: "读取失败"; false }
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
                .onFailure { e -> docErrorOf = docErrorOf + (peer.id to (e.message ?: "读取失败")) }
        }
    }

    fun pullDoc(peer: PeerRow, doc: DocRow) {
        if (pulling.isNotBlank()) return
        pulling = peer.id + "/" + doc.id
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceApi.pull(peer.id, doc.id) } }
            pulling = ""
            result.onSuccess { name -> notify("已同步到本地：" + name) }
                .onFailure { e -> notify("同步失败：" + (e.message ?: "未知错误")) }
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
            }.onFailure { e -> notify("移除失败：" + (e.message ?: "未知错误")) }
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
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "附近设备",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (loadError.isBlank()) "每 3 秒自动刷新" else loadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (peers.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没发现其它设备", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "1. 手机和电脑要在同一个 Wi-Fi；\n" +
                                    "2. 对方也要开着 LineTrans（网页端 / PC 端 / 另一台手机）；\n" +
                                    "3. 公网服务器用右上角 ＋ 手填地址和令牌。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(peers, key = { it.id }) { peer ->
                PeerCard(
                    peer = peer,
                    expanded = expandedId == peer.id,
                    docs = docsOf[peer.id],
                    docError = docErrorOf[peer.id],
                    pullingKey = pulling,
                    onToggle = { togglePeer(peer) },
                    onPull = { doc -> pullDoc(peer, doc) },
                    onEdit = { doc ->
                        openConsole(
                            "peer=" + Uri.encode(peer.id) + "&doc=" + Uri.encode(doc.id)
                        )
                    },
                    onRemove = { removePeer(peer) }
                )
            }

            item {
                Text(
                    "「同步到本地」= 复制一份到本机，对端不变；「直接编辑」= 在网页台里改对端那份，" +
                        "改完自动写回对端。",
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

@Composable
private fun PeerCard(
    peer: PeerRow,
    expanded: Boolean,
    docs: List<DocRow>?,
    docError: String?,
    pullingKey: String,
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
        TextButton(onClick = onEdit) { Text("直接编辑") }
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
                            .onFailure { hint = it.message ?: "连接失败" }
                    }
                }
            ) { Text(if (busy) "连接中…" else "连接并保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
