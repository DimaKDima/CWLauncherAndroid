package ru.cw.launcher.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class AccountType { OFFLINE, ELY }

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val type: AccountType = AccountType.OFFLINE,
    var username: String = "",
    var uuid: String = offlineUuid(""),
    var login: String = "",
    var clientToken: String = "",
    var createdAt: Long = System.currentTimeMillis()
) {
    fun shortUuid(): String = uuid.replace("-", "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("username", username)
        .put("uuid", uuid)
        .put("login", login)
        .put("clientToken", clientToken)
        .put("createdAt", createdAt)

    companion object {
        fun validNick(name: String?): Boolean = name != null && name.matches(Regex("[A-Za-z0-9_]{3,16}"))

        fun offlineUuid(name: String): String =
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

        fun fromJson(o: JSONObject): Account = Account(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = runCatching { AccountType.valueOf(o.optString("type", "OFFLINE")) }.getOrDefault(AccountType.OFFLINE),
            username = o.optString("username", ""),
            uuid = o.optString("uuid", offlineUuid("")),
            login = o.optString("login", ""),
            clientToken = o.optString("clientToken", ""),
            createdAt = o.optLong("createdAt", 0L)
        )
    }
}

class Accounts(private val cfg: CwSettings) {
    val items = mutableListOf<Account>()

    init {
        load()
    }

    fun active(): Account? = items.firstOrNull { it.id == cfg.activeAccountId } ?: items.firstOrNull()

    fun addOffline(nick: String): Account {
        val name = nick.trim()
        require(Account.validNick(name)) { "Ник: от 3 до 16 символов, латиница, цифры и _." }
        val existing = items.firstOrNull { it.type == AccountType.OFFLINE && it.username.equals(name, true) }
        if (existing != null) {
            cfg.activeAccountId = existing.id
            cfg.save()
            return existing
        }
        val account = Account(
            type = AccountType.OFFLINE,
            username = name,
            uuid = Account.offlineUuid(name),
            login = name,
            clientToken = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis()
        )
        items.add(account)
        cfg.activeAccountId = account.id
        save()
        cfg.save()
        return account
    }

    fun renameOffline(id: String, username: String): String? {
        val name = username.trim()
        val account = items.firstOrNull { it.id == id } ?: return "Аккаунт не найден"
        if (account.type != AccountType.OFFLINE) return "Имя аккаунта Ely.by задаётся на сайте Ely.by."
        if (!Account.validNick(name)) return "Ник: от 3 до 16 символов, латиница, цифры и _."
        val taken = items.any { it.id != id && it.type == AccountType.OFFLINE && it.username.equals(name, true) }
        if (taken) return "Такой оффлайн-ник уже есть."
        account.username = name
        account.login = name
        account.uuid = Account.offlineUuid(name)
        save()
        return null
    }

    fun addEly(username: String, uuid: String, clientToken: String): Account {
        val existing = items.firstOrNull { it.type == AccountType.ELY && it.username.equals(username, true) }
        val account = existing ?: Account(type = AccountType.ELY, clientToken = clientToken.ifBlank { UUID.randomUUID().toString() })
        account.username = username
        account.login = username
        account.uuid = normalizeUuid(uuid).ifBlank { Account.offlineUuid(username) }
        if (clientToken.isNotBlank()) account.clientToken = clientToken
        if (existing == null) items.add(account)
        cfg.activeAccountId = account.id
        save()
        cfg.save()
        return account
    }

    fun select(id: String) {
        cfg.activeAccountId = id
        cfg.save()
    }

    fun wipe() {
        items.forEach { Secrets.remove("token.${it.id}") }
        items.clear()
        cfg.activeAccountId = ""
        if (AppPaths.accounts.exists()) AppPaths.accounts.delete()
        if (AppPaths.secrets.exists()) AppPaths.secrets.delete()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        Secrets.remove("token.$id")
        if (cfg.activeAccountId == id) cfg.activeAccountId = items.firstOrNull()?.id.orEmpty()
        save()
        cfg.save()
    }

    private fun load() {
        if (!AppPaths.accounts.isFile) return
        try {
            val arr = JSONArray(AppPaths.accounts.readText())
            for (i in 0 until arr.length()) items.add(Account.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) {
            CwLog.warn("accounts.json: ${e.message}")
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        AppPaths.accounts.writeText(arr.toString(2))
    }
}

object Secrets {
    private fun read(): JSONObject {
        if (!AppPaths.secrets.isFile) return JSONObject()
        return try {
            JSONObject(AppPaths.secrets.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    fun put(key: String, value: String) {
        val o = read()
        o.put(key, value)
        AppPaths.secrets.writeText(o.toString())
    }

    @Synchronized
    fun get(key: String): String? {
        val v = read().optString(key, "")
        return v.ifBlank { null }
    }

    @Synchronized
    fun remove(key: String) {
        val o = read()
        o.remove(key)
        AppPaths.secrets.writeText(o.toString())
    }
}

object Ely {
    const val AUTHORIZE = "https://account.ely.by/oauth2/v1"
    const val ACCOUNT_INFO = "https://account.ely.by/api/account/v1/info"
    const val AUTH_HOST = "https://authserver.ely.by"
    const val INJECTOR_API = "$AUTH_HOST/api/authlib-injector"
    const val SCOPE = "account_info minecraft_server_session offline_access"

    fun configError(cfg: CwSettings): String? {
        val id = cfg.elyClientId.trim()
        val redirect = cfg.elyRedirectUri.trim()
        val backend = cfg.elyBackendUrl.trim()
        if (id.isEmpty() || redirect.isEmpty() || backend.isEmpty()) {
            return "Чтобы войти через Ely.by, укажите client id, точный redirect URI и адрес сервера CW. Пароль и client secret в лаунчер не вводятся."
        }
        if (!redirect.startsWith("https://") && !redirect.startsWith("http://")) {
            return "Redirect URI должен быть адресом сайта, который зарегистрирован в приложении Ely.by."
        }
        if (!backend.startsWith("https://")) {
            return "Адрес сервера CW должен начинаться с https://."
        }
        return null
    }

    fun authorizeUrl(clientId: String, redirect: String, state: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
        return AUTHORIZE +
            "?client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(redirect) +
            "&response_type=code" +
            "&scope=" + enc(SCOPE) +
            "&state=" + enc(state)
    }

    fun token(account: Account): String {
        return Secrets.get("token.${account.id}") ?: Secrets.get("ely.access.${account.clientToken}") ?: "0"
    }
}

fun normalizeUuid(uuid: String?): String {
    val s = uuid?.trim().orEmpty()
    if (s.length == 32 && !s.contains('-')) {
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) +
            "-" + s.substring(16, 20) + "-" + s.substring(20)
    }
    return s
}

class ElyLogin(
    private val net: Net,
    private val cfg: CwSettings,
    private val accounts: Accounts
) {
    private val waiting = AtomicBoolean(false)
    @Volatile private var expectedState: String? = null
    @Volatile private var callbackUri: String? = null

    fun deliver(uri: String?) {
        if (uri.isNullOrBlank()) return
        if (!waiting.get()) return
        callbackUri = uri.trim()
    }

    fun login(onPhase: (String) -> Unit): String {
        val problem = Ely.configError(cfg)
        if (problem != null) return problem
        if (!waiting.compareAndSet(false, true)) return "Вход уже выполняется"
        val state = UUID.randomUUID().toString().replace("-", "")
        expectedState = state
        callbackUri = null
        try {
            onPhase("Открываем Ely.by…")
            val url = Ely.authorizeUrl(cfg.elyClientId.trim(), cfg.elyRedirectUri.trim(), state)
            return url
        } finally {
            // Ожидание идёт отдельно, ссылка возвращается сразу.
        }
    }

    fun beginWait(): String = expectedState ?: ""

    fun finish(onPhase: (String) -> Unit): String? {
        val state = expectedState ?: return "Вход не начат"
        val deadline = System.currentTimeMillis() + 180_000
        var netFails = 0
        try {
            onPhase("Авторизация…")
            while (System.currentTimeMillis() < deadline) {
                val uri = callbackUri
                if (uri != null) {
                    callbackUri = null
                    val parsed = parseCallback(uri)
                    if (parsed.state != null && parsed.state != state) return "Вход отклонён: ответ Ely.by не совпал с этим запуском."
                    if (parsed.error != null) return explain(parsed.error)
                    if (!parsed.code.isNullOrBlank()) {
                        val tokens = exchange(parsed.code, state)
                        return store(tokens, onPhase)
                    }
                }
                try {
                    val polled = poll(state)
                    if (polled != null) return store(polled, onPhase)
                    netFails = 0
                } catch (e: Exception) {
                    netFails++
                    if (netFails >= 8) return "Сервер авторизации CW недоступен"
                    CwLog.warn("Ely.by: ${e.message}")
                }
                Thread.sleep(1500)
            }
            return "Время входа истекло. Сайт Ely.by не вернул в лаунчер."
        } finally {
            waiting.set(false)
            expectedState = null
        }
    }

    private fun store(tokens: Tokens, onPhase: (String) -> Unit): String? {
        onPhase("Получение профиля…")
        val info = accountInfo(tokens.access)
        if (info.first.isBlank()) return "Ely.by не вернул имя аккаунта"
        val account = accounts.addEly(info.first, info.second, UUID.randomUUID().toString())
        Secrets.put("token.${account.id}", tokens.access)
        if (tokens.refresh.isNotBlank()) Secrets.put("refresh.${account.id}", tokens.refresh)
        CwLog.info("Ely.by: вход выполнен как ${info.first}")
        return null
    }

    private fun accountInfo(access: String): Pair<String, String> {
        val (code, body) = net.getTextAuth(Ely.ACCOUNT_INFO, access)
        if (code !in 200..299) throw java.io.IOException("Профиль Ely.by: HTTP $code")
        val o = JSONObject(body)
        return o.optString("username") to normalizeUuid(o.optString("uuid"))
    }

    private fun exchange(code: String, state: String): Tokens {
        val body = JSONObject().put("code", code).put("redirect_uri", cfg.elyRedirectUri.trim()).put("state", state)
        val (status, text) = net.postJson(cfg.elyBackendUrl.trim().trimEnd('/') + "/exchange", body.toString())
        if (status !in 200..299) throw java.io.IOException(explainHttp(status, text))
        return readTokens(text) ?: throw java.io.IOException("Сессия входа истекла. Войдите снова через Ely.by")
    }

    private fun poll(state: String): Tokens? {
        val enc = URLEncoder.encode(state, Charsets.UTF_8.name())
        val (code, text) = net.getStatus(cfg.elyBackendUrl.trim().trimEnd('/') + "/session?state=$enc")
        if (code == 204 || code == 202 || code == 404) return null
        if (code !in 200..299) throw java.io.IOException(explainHttp(code, text))
        return readTokens(text)
    }

    private fun readTokens(text: String): Tokens? {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val access = o.optString("access_token").ifBlank { o.optString("accessToken") }
        if (access.isBlank()) return null
        val refresh = o.optString("refresh_token").ifBlank { o.optString("refreshToken") }
        return Tokens(access, refresh)
    }

    private data class Callback(val code: String?, val state: String?, val error: String?)

    private fun parseCallback(uri: String): Callback {
        val q = uri.substringAfter('?', "")
        val map = q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8.name())
        }.toMap()
        return Callback(map["code"], map["state"], map["error"])
    }

    private fun explain(error: String): String = when (error.lowercase(Locale.ROOT)) {
        "access_denied" -> "Вход отменён"
        "invalid_client" -> "Приложение Ely.by не подтверждено. Проверьте client id и redirect URI."
        else -> "Ely.by отклонил вход"
    }

    private fun explainHttp(status: Int, body: String): String {
        val low = body.lowercase(Locale.ROOT)
        if (low.contains("invalid_client")) return "Приложение Ely.by не подтверждено. Проверьте client id и redirect URI."
        return "Сервер авторизации ответил $status"
    }

    private data class Tokens(val access: String, val refresh: String)
}
