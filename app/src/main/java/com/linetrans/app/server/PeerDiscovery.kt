package com.linetrans.app.server

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.linetrans.app.BuildConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 局域网里看到的一台设备（或手填的远程服务器）。 */
data class PeerInfo(
    val id: String,
    val name: String,
    val platform: String,
    val version: String,
    val host: String = "",
    val port: Int = 0,
    val manual: Boolean = false,
    val token: String = "",
    val url: String = "",
    val lastSeen: Long = 0L
) {
    /** 代理请求实际要用的 base url。 */
    val baseUrl: String
        get() = if (url.isNotBlank()) url.trimEnd('/') else "http://" + host + ":" + port
}

/**
 * 设备发现与身份（v1.9.0）。
 *
 * 和网页服务端（line-trans-web）用同一套约定：
 *   · 每 3 秒往 UDP 41234 广播一次 {"lt":"peer",...}；
 *   · 收到别人的广播就记下来，20 秒没动静当作掉线；
 *   · 手填的远程服务器存在 SharedPreferences 里（跟设置一起被备份覆盖不到，但重启还在）。
 *
 * 浏览器拿不到 UDP，所以发现结果只通过 /api/peers 给网页台使用。
 */
object PeerDiscovery {
    private const val PREFS = "linetrans_peers"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"
    private const val KEY_MANUAL = "manual_peers"
    private const val DISCOVERY_PORT = 41234
    private const val BEAT_MS = 3000L
    private const val OFFLINE_MS = 20_000L

    private val gson = Gson()
    private val discovered = ConcurrentHashMap<String, PeerInfo>()
    private val manual = ConcurrentHashMap<String, PeerInfo>()

    @Volatile private var appContext: Context? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false

    val deviceId: String
        get() {
            val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return fallbackId
            val saved = prefs.getString(KEY_ID, "") ?: ""
            if (saved.isNotBlank()) return saved
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ID, fresh).apply()
            return fresh
        }

    private val fallbackId: String = UUID.randomUUID().toString()

    /** 显示名：用户改过的优先，否则用机型。 */
    val deviceName: String
        get() {
            val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs?.getString(KEY_NAME, "") ?: ""
            return if (saved.isNotBlank()) saved else defaultName()
        }

    private fun defaultName(): String {
        val model = (Build.MODEL ?: "").trim()
        val brand = (Build.BRAND ?: "").trim()
        val raw = when {
            model.isBlank() -> "安卓设备"
            brand.isBlank() -> model
            model.startsWith(brand, ignoreCase = true) -> model
            else -> brand + " " + model
        }
        return raw.take(40)
    }

    fun setDeviceName(name: String) {
        val clean = name.trim().take(40)
        if (clean.isBlank()) return
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_NAME, clean)?.apply()
    }

    /** 启动发现：幂等，重复调用不会起第二个线程。 */
    @Synchronized
    fun start(context: Context, port: Int) {
        appContext = context.applicationContext
        loadManual()
        if (running) return
        running = true
        val t = Thread({ loop(port) }, "linetrans-peer")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun loop(port: Int) {
        val sock = try {
            DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            // 端口被占（例如同时开着网页服务端）：只影响发现，不影响本机服务
            running = false
            return
        }
        socket = sock
        val payload = gson.toJson(
            mapOf(
                "lt" to "peer",
                "v" to 1,
                "id" to deviceId,
                "name" to deviceName,
                "platform" to "android",
                "version" to BuildConfig.VERSION_NAME,
                "port" to port
            )
        ).toByteArray(Charsets.UTF_8)

        val receiver = Thread({
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val map = runCatching {
                        gson.fromJson(text, Map::class.java)
                    }.getOrNull() ?: continue
                    if (map["lt"] != "peer") continue
                    val id = map["id"]?.toString() ?: continue
                    if (id == deviceId) continue
                    val remotePort = (map["port"] as? Number)?.toInt() ?: continue
                    discovered[id] = PeerInfo(
                        id = id,
                        name = (map["name"]?.toString() ?: "另一台设备").take(40),
                        platform = map["platform"]?.toString() ?: "device",
                        version = map["version"]?.toString() ?: "",
                        host = packet.address?.hostAddress ?: "",
                        port = remotePort,
                        lastSeen = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    if (!running) break
                    Thread.sleep(200)
                }
            }
        }, "linetrans-peer-rx")
        receiver.isDaemon = true
        receiver.start()

        while (running) {
            try {
                sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val now = System.currentTimeMillis()
                discovered.entries.removeIf { now - it.value.lastSeen > OFFLINE_MS }
            } catch (e: Exception) {
                // 广播失败（有的系统限制）不致命
            }
            try { Thread.sleep(BEAT_MS) } catch (e: InterruptedException) { break }
        }
    }

    /** 供 /api/peers 使用：手填的在前，自己不算。 */
    fun list(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()
        for (p in manual.values) {
            out.add(mapOf(
                "id" to p.id,
                "name" to if (p.name.isBlank()) p.url else p.name,
                "platform" to "remote",
                "version" to "",
                "url" to p.baseUrl,
                "hasToken" to p.token.isNotBlank(),
                "manual" to true,
                "lastSeen" to p.lastSeen,
                "online" to (p.lastSeen > 0 && now - p.lastSeen < 30000)
            ))
            seen.add(p.id)
        }
        for (p in discovered.values) {
            if (p.id == deviceId || seen.contains(p.id)) continue
            out.add(mapOf(
                "id" to p.id,
                "name" to p.name,
                "platform" to p.platform,
                "version" to p.version,
                "url" to p.baseUrl,
                "hasToken" to false,
                "manual" to false,
                "lastSeen" to p.lastSeen,
                "online" to (now - p.lastSeen < OFFLINE_MS)
            ))
        }
        return out
    }

    fun find(id: String): PeerInfo? = manual[id] ?: discovered[id]

    fun touch(id: String) {
        discovered[id]?.let { discovered[id] = it.copy(lastSeen = System.currentTimeMillis()) }
        manual[id]?.let { manual[id] = it.copy(lastSeen = System.currentTimeMillis()); saveManual() }
    }

    /** 手填远程服务器：返回连接结果（失败原因直接回给界面）。 */
    fun addManual(url: String, token: String, name: String, remote: PeerInfo?): PeerInfo {
        val clean = url.trim().trimEnd('/')
        val normalized = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "http://" + clean
        val id = remote?.id?.takeIf { it.isNotBlank() } ?: normalized
        val peer = PeerInfo(
            id = id,
            name = name.trim().ifBlank { remote?.name ?: normalized },
            platform = remote?.platform ?: "remote",
            version = remote?.version ?: "",
            url = normalized,
            token = token.trim(),
            manual = true,
            lastSeen = System.currentTimeMillis()
        )
        manual[id] = peer
        saveManual()
        return peer
    }

    fun removeManual(id: String): Boolean {
        val removed = manual.remove(id) != null
        if (removed) saveManual()
        return removed
    }

    private fun loadManual() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString(KEY_MANUAL, "") ?: return
        runCatching {
            val type = object : TypeToken<List<PeerInfo>>() {}.type
            val list: List<PeerInfo> = gson.fromJson(raw, type) ?: emptyList()
            manual.clear()
            list.forEach { manual[it.id] = it.copy(manual = true) }
        }
    }

    private fun saveManual() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        runCatching {
            val payload = gson.toJson(manual.values.toList())
            prefs.edit().putString(KEY_MANUAL, payload).apply()
        }
    }
}