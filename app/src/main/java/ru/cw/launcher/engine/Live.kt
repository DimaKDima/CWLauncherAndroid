package ru.cw.launcher.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern

data class ServerInfo(
    val host: String,
    val port: Int,
    val online: Boolean,
    val players: Int,
    val maxPlayers: Int,
    val pingMs: Long,
    val version: String,
    val motd: String,
    val error: String?
) {
    fun playersText(): String = if (online && error == null) "$players / $maxPlayers" else "—"
    fun statusText(): String = when {
        error != null -> "Статус сервера недоступен"
        online -> "Сервер онлайн"
        else -> "Сервер не отвечает"
    }
}

object ServerPing {
    fun check(address: String, timeoutMs: Int = 4000): ServerInfo {
        var host = address.trim()
        var port = 25965
        val colon = host.lastIndexOf(':')
        if (colon > 0) {
            val tail = host.substring(colon + 1).trim()
            if (tail.all { it.isDigit() }) {
                host = host.substring(0, colon)
                port = tail.toIntOrNull() ?: 25965
            }
        }
        if (host.isBlank()) {
            return ServerInfo("", port, false, 0, 0, -1, "", "", "адрес не задан")
        }
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(1500))
                socket.soTimeout = timeoutMs.coerceAtLeast(1500)
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                val payload = ByteArrayOutputStream()
                writeVarInt(payload, 0)
                writeVarInt(payload, 763)
                writeString(payload, host)
                payload.write((port shr 8) and 0xFF)
                payload.write(port and 0xFF)
                writeVarInt(payload, 1)
                writeVarInt(out, payload.size())
                out.write(payload.toByteArray())
                writeVarInt(out, 1)
                writeVarInt(out, 0)
                out.flush()
                val length = readVarInt(input)
                val body = ByteArray(length)
                input.readFully(body)
                val ping = System.currentTimeMillis() - start
                var idx = skipVarInt(body, 0)
                val jsonLen = varInt(body, idx)
                idx = skipVarInt(body, idx)
                val json = String(body, idx, jsonLen.coerceAtMost(body.size - idx), Charsets.UTF_8)
                val root = JSONObject(json)
                val players = root.optJSONObject("players")
                val version = root.optJSONObject("version")
                val motd = when (val desc = root.opt("description")) {
                    is String -> desc
                    is JSONObject -> desc.optString("text")
                    else -> ""
                }
                ServerInfo(
                    host, port, true,
                    players?.optInt("online") ?: 0,
                    players?.optInt("max") ?: 0,
                    ping,
                    version?.optString("name").orEmpty(),
                    motd.replace(Regex("§."), ""),
                    null
                )
            }
        } catch (e: Exception) {
            CwLog.warn("Сервер $host:$port недоступен: ${e.message}")
            ServerInfo(host, port, false, 0, 0, -1, "", "", e.message ?: "нет ответа")
        }
    }

    private fun writeVarInt(out: java.io.OutputStream, value0: Int) {
        var value = value0
        while (true) {
            if ((value and 0x7F.inv()) == 0) {
                out.write(value)
                return
            }
            out.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
    }

    private fun writeString(out: java.io.OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, b.size)
        out.write(b)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        repeat(5) {
            val b = input.read()
            if (b < 0) throw java.io.IOException("Сервер закрыл соединение")
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return value
            shift += 7
        }
        throw java.io.IOException("Некорректный VarInt от сервера")
    }

    private fun varInt(buf: ByteArray, offset: Int): Int {
        var value = 0
        var shift = 0
        var i = offset
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            i++
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return value
    }

    private fun skipVarInt(buf: ByteArray, offset: Int): Int {
        var i = offset
        while (i < buf.size && (buf[i].toInt() and 0x80) != 0) i++
        return (i + 1).coerceAtMost(buf.size)
    }
}

data class NewsItem(val title: String, val date: String, val text: String)

fun loadNews(net: Net, cfg: CwSettings): NewsItem? {
    val cache = File(AppPaths.cache, "news.txt")
    if (cfg.newsUrl.isBlank()) return null
    val body = try {
        val text = readRemoteText(net, cfg.newsUrl)
        cache.writeText(text)
        text
    } catch (e: Exception) {
        CwLog.warn("Новости: ${e.message}")
        if (cache.isFile) cache.readText() else return null
    }
    return parseNews(body)
}

fun parseNews(body: String): NewsItem? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            val arr: JSONArray? = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val o = JSONObject(trimmed)
                    o.optJSONArray("news") ?: o.optJSONArray("items") ?: o.optJSONArray("updates")
                }
            }
            if (arr != null && arr.length() > 0) {
                val item = arr.getJSONObject(0)
                val title = item.optString("title").ifBlank { item.optString("name") }
                val text = item.optString("text").ifBlank { item.optString("content") }
                if (title.isNotBlank() || text.isNotBlank()) {
                    return NewsItem(title.ifBlank { "Новости" }, item.optString("date"), text)
                }
            }
        } catch (e: Exception) {
            CwLog.warn("news.json: ${e.message}")
        }
    }
    val lines = trimmed.replace("\r\n", "\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val title = lines.first()
    var date = ""
    val rest = mutableListOf<String>()
    lines.drop(1).forEach { line ->
        if (date.isEmpty() && line.matches(Regex("\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}"))) date = line
        else rest.add(line)
    }
    return NewsItem(title, date, rest.joinToString("\n"))
}

data class UpdateNote(val version: String, val title: String, val text: String)

private val updateName: Pattern = Pattern.compile("^UpdateV(\\d+\\.\\d+\\.\\d+)\\.txt$")

fun loadUpdateNotes(net: Net, cfg: CwSettings): List<UpdateNote> {
    val cache = File(AppPaths.cache, "UpdateInfo.zip")
    if (cfg.updateInfoUrl.isBlank()) return cachedNotes()
    try {
        if (cfg.updateInfoUrl.contains("drive.google.com")) {
            Drive.download(net, cfg.updateInfoUrl, cache, true, null, null)
        } else {
            net.download(cfg.updateInfoUrl, cache, null, 0, null, null)
        }
        val dir = File(AppPaths.cache, "update-info")
        clearDir(dir)
        extractZip(cache, dir)
    } catch (e: Exception) {
        CwLog.warn("Информация об обновлениях: ${e.message}")
    }
    return cachedNotes()
}

fun cachedNotes(): List<UpdateNote> {
    val dir = File(AppPaths.cache, "update-info")
    if (!dir.isDirectory) return emptyList()
    val notes = mutableListOf<UpdateNote>()
    dir.walkTopDown().filter { it.isFile }.forEach { file ->
        val m = updateName.matcher(file.name)
        if (!m.matches()) return@forEach
        val version = m.group(1) ?: return@forEach
        notes.add(UpdateNote(version, "Обновление $version", file.readText().trim()))
    }
    return notes.sortedWith { a, b -> compareVersions(b.version, a.version) }
}
