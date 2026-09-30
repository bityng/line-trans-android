package com.linetrans.app.server

import android.content.Context
import com.linetrans.app.data.SettingsRepository
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * 局域网网页翻译台服务：把 assets/web/ 下的网页界面与 [WebApi] 提供给同一局域网内的设备，
 * 电脑 / 平板浏览器打开 http://<手机IP>:<端口>/ 即可接着手机上的进度继续翻译。
 *
 * 这里只做三件事：静态资源、[WebApi] 代理、访问令牌鉴权。
 */
class WebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val api = WebApi(context)

    @Volatile
    private var started = false

    @Synchronized
    fun startServer() {
        if (started) return
        start(SOCKET_READ_TIMEOUT, false)
        started = true
    }

    /** 入口是 [NanoHTTPD.serve]：serveHttp() 只存在于 NanoWSD（已随终端一起移除），普通 NanoHTTPD 没有这个方法。 */
    override fun serve(session: IHTTPSession): Response {
        if (!tokenAllowed(session)) return unauthorized()
        val uri = session.uri.removePrefix("/")
        return when {
            uri.startsWith("api/") -> api.handle(session)
            uri.isEmpty() || uri == "app" -> serveWebApp("index.html")
            uri in WEB_ASSETS -> serveWebApp(uri)
            uri == "health" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
            uri == "favicon.ico" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404 not found")
        }
    }

    /** 网页翻译台的静态资源（assets/web/）。 */
    private fun serveWebApp(name: String, mime: String = ""): Response {
        val bytes = runCatching { context.assets.open("web/" + name).readBytes() }.getOrNull()
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "缺少 assets/web/$name"
            )
        return newFixedLengthResponse(
            Response.Status.OK,
            mime.ifBlank { mimeOf(name) },
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".js") -> "text/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    /** 设置了访问令牌时，请求必须携带 ?token=xxx。 */
    private fun tokenAllowed(session: IHTTPSession): Boolean {
        val expected = SettingsRepository.settings.webServerToken.trim()
        if (expected.isEmpty()) return true
        val provided = session.parameters?.get("token")?.firstOrNull()
        return provided == expected
    }

    private fun unauthorized(): Response = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED,
        "text/plain; charset=utf-8",
        "401 未授权：请在地址后加上 ?token=<访问令牌>"
    )

    companion object {
        private const val SOCKET_READ_TIMEOUT = 5000
        private val WEB_ASSETS = setOf(
            "index.html", "app.js", "style.css", "icon.svg",
            "icon-192.png", "icon-512.png", "manifest.json"
        )
    }
}
