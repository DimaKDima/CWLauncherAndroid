package ru.cw.launcher.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.cw.launcher.work.WorkService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase {
    NOT_INSTALLED, CHECKING, DOWNLOADING, INSTALLING, UPDATING,
    UPDATE_AVAILABLE, READY, STARTING, RUNNING, ERROR, OFFLINE
}

enum class Screen { HOME, SETTINGS, ACCOUNT, VERSIONS, NEWS, UPDATES, FILES, MODS, SKIN, LOG }

data class DialogModel(
    val text: String,
    val confirm: String,
    val dismiss: String?,
    val action: String,
    val alt: String? = null,
    val altAction: String? = null
)

data class UiState(
    val booting: Boolean = true,
    val bootText: String = "Загрузка данных о лаунчере",
    val screen: Screen = Screen.HOME,
    val phase: Phase = Phase.CHECKING,
    val detail: String = "",
    val progress: Float = 0f,
    val progressText: String = "",
    val busy: Boolean = false,
    val accountName: String = "",
    val versionLabel: String = "Common World · 1.20.1 · Fabric",
    val buildLabel: String = "Моды не установлены",
    val server: ServerInfo? = null,
    val news: NewsItem? = null,
    val notes: List<UpdateNote> = emptyList(),
    val releases: List<String> = emptyList(),
    val dialog: DialogModel? = null,
    val info: String? = null,
    val files: List<String> = emptyList(),
    val launcherRemote: String? = null,
    val javaFound: Boolean = false,
    val bootProgress: Float = 0.08f,
    val fabricLabel: String = "Fabric",
    val modsCount: Int = 0,
    val buildVersion: String = "",
    val mcReady: Boolean = false,
    val fabricOk: Boolean = false,
    val modsOk: Boolean = false,
    val accountTick: Int = 0,
    val installedVersions: Set<String> = emptySet(),
    val fabricOn: Set<String> = emptySet(),
    val logText: String = "",
    val browserPath: String = "",
    val lookTick: Int = 0,
    val xferTitle: String = "",
    val xferDone: Long = 0,
    val xferTotal: Long = 0,
    val xferSpeed: Long = 0,
    val xferExtra: String = ""
)

class Engine(private val app: Context) {
    val cfg: CwSettings
    private val net = Net()
    val accounts: Accounts
    val ely: ElyLogin
    private val cancel = AtomicBoolean(false)
    @Volatile private var deletePartialOnCancel = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installer: Installer
    private val game: GameLaunch
    private var booting = true
    private var network = true
    private var filesNeedUpdate = false
    private var pendingGameUpdate = false
    private var launchAfterMods = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        AppPaths.init(app)
        cfg = CwSettings.load()
        Lang.use(cfg.resolvedLanguage())
        accounts = Accounts(cfg)
        ely = ElyLogin(net, cfg, accounts)
        installer = Installer(net, cfg, cancel) { p ->
            if (booting) {
                _state.update { it.copy(bootText = p.title) }
            } else {
                _state.update {
                    it.copy(
                        progressText = p.title,
                        progress = p.fraction,
                        detail = p.title,
                        xferTitle = if (p.totalBytes > 0) p.title else "",
                        xferDone = p.doneBytes,
                        xferTotal = p.totalBytes,
                        xferSpeed = p.speedBps,
                        xferExtra = p.extra
                    )
                }
            }
        }
        game = GameLaunch(app, cfg, installer)
        CwLog.info("=== CWLauncher v${CwLog.VERSION} запущен ===")
    }

    fun start() {
        scope.launch {
            boot()
            while (true) {
                delay(10_000)
                if (!_state.value.busy) checkServer()
            }
        }
        scope.launch {
            delay((cfg.checkIntervalSec.coerceAtLeast(30) * 1000L))
            while (true) {
                if (!_state.value.busy) {
                    refreshRemote(false)
                }
                delay((cfg.checkIntervalSec.coerceAtLeast(30) * 1000L))
            }
        }
    }

    fun deliverOauth(uri: String) {
        ely.deliver(uri)
    }

    fun open(screen: Screen) {
        if (screen == Screen.FILES || screen == Screen.MODS) reloadFiles(screen)
        if (screen == Screen.LOG) reloadLog()
        if (screen == Screen.VERSIONS) {
            scope.launch {
                val list = if (_state.value.releases.isEmpty()) {
                    try {
                        installer.releases()
                    } catch (e: Exception) {
                        CwLog.warn("Список версий: ${e.message}")
                        emptyList()
                    }
                } else {
                    _state.value.releases
                }
                val installed = (list + cfg.minecraftVersion + "1.20.1").distinct()
                    .filter { installer.present(it) }
                    .toSet()
                val fabric = cfg.fabricVersions.filterValues { it }.keys
                _state.update { it.copy(releases = list, installedVersions = installed, fabricOn = fabric) }
            }
        }
        _state.update { it.copy(screen = screen) }
    }

    fun altDialog() {
        val action = _state.value.dialog?.altAction
        _state.update { it.copy(dialog = null) }
        if (action == "cancel-keep") {
            deletePartialOnCancel = false
            cancel.set(true)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun dismissInfo() = _state.update { it.copy(info = null) }

    fun confirmDialog() {
        val action = _state.value.dialog?.action
        _state.update { it.copy(dialog = null) }
        when (action) {
            "cancel" -> cancel.set(true)
            "cancel-delete" -> {
                deletePartialOnCancel = true
                cancel.set(true)
            }
            "cancel-keep" -> {
                deletePartialOnCancel = false
                cancel.set(true)
            }
            "install" -> install(false)
            "repair" -> install(false)
            "account" -> open(Screen.ACCOUNT)
        }
    }

    fun onMain() {
        val phase = _state.value.phase
        if (_state.value.busy || phase == Phase.CHECKING || phase == Phase.INSTALLING || phase == Phase.DOWNLOADING || phase == Phase.UPDATING) {
            _state.update {
                it.copy(dialog = DialogModel(
                    "Остановить загрузку? Скачанное можно оставить и продолжить позже или удалить файлы.",
                    "Удалить файлы",
                    "Продолжить",
                    "cancel-delete",
                    "Остановить",
                    "cancel-keep"
                ))
            }
            return
        }
        when (phase) {
            Phase.READY, Phase.OFFLINE -> {
                if (!requireAccount()) return
                launchGame()
            }
            Phase.NOT_INSTALLED -> {
                if (!requireAccount()) return
                install(false)
            }
            Phase.UPDATE_AVAILABLE -> {
                if (!requireAccount()) return
                if (pendingGameUpdate) install(true) else {
                    launchAfterMods = true
                    updateMods()
                }
            }
            Phase.ERROR -> {
                if (!requireAccount()) return
                val text = _state.value.detail
                if (text.startsWith("Не удалось") || text.startsWith("Игра закрылась")) install(true)
                else verifyThenRepair()
            }
            Phase.RUNNING, Phase.STARTING -> Unit
            else -> Unit
        }
    }

    fun addOffline(nick: String) {
        try {
            accounts.addOffline(nick)
            publishAccount()
            evaluate()
        } catch (e: Exception) {
            _state.update { it.copy(info = e.message) }
        }
    }

    fun removeAccount(id: String) {
        accounts.remove(id)
        publishAccount()
        evaluate()
    }

    fun selectAccount(id: String) {
        accounts.select(id)
        publishAccount()
    }

    fun renameOffline(id: String, nick: String): String? {
        val error = accounts.renameOffline(id, nick)
        if (error == null) {
            publishAccount()
            evaluate()
        }
        return error
    }

    fun beginEly(openUrl: (String) -> Unit) {
        scope.launch {
            val url = ely.login { text -> _state.update { it.copy(detail = text) } }
            if (!url.startsWith("http")) {
                _state.update { it.copy(info = url) }
                return@launch
            }
            withContext(Dispatchers.Main) { openUrl(url) }
            val error = ely.finish { text -> _state.update { it.copy(detail = text) } }
            if (error != null) _state.update { it.copy(info = error) }
            else {
                publishAccount()
                evaluate()
                _state.update { it.copy(info = "Вход через Ely.by выполнен") }
            }
        }
    }

    fun selectCommonWorld() {
        cfg.commonWorld = true
        cfg.minecraftVersion = "1.20.1"
        cfg.loader = "fabric"
        cfg.save()
        evaluate()
        open(Screen.HOME)
        scope.launch { refreshRemote(true) }
    }

    fun selectRelease(id: String, fabric: Boolean) {
        cfg.commonWorld = false
        cfg.minecraftVersion = id
        cfg.loader = if (fabric) "fabric" else "vanilla"
        cfg.fabricVersions[id] = fabric
        cfg.save()
        evaluate()
        open(Screen.HOME)
    }

    fun toggleFabric(id: String) {
        val shown = if (cfg.fabricVersions.containsKey(id)) cfg.fabricFor(id) else id == "1.20.1" && cfg.commonWorld
        val on = !shown
        cfg.fabricVersions[id] = on
        if (!cfg.commonWorld && cfg.minecraftVersion.equals(id, true)) {
            cfg.loader = if (on) "fabric" else "vanilla"
        }
        cfg.save()
        _state.update { state ->
            val next = state.fabricOn.toMutableSet()
            if (on) next.add(id) else next.remove(id)
            state.copy(fabricOn = next)
        }
        evaluate()
    }

    fun saveSettings(block: CwSettings.() -> Unit) {
        cfg.block()
        if (cfg.commonWorld) {
            cfg.minecraftVersion = "1.20.1"
            cfg.loader = "fabric"
        }
        cfg.checkIntervalSec = cfg.checkIntervalSec.coerceAtLeast(30)
        cfg.save()
        Lang.use(cfg.resolvedLanguage())
        game.applyOptions()
        evaluate()
        _state.update { it.copy(info = Lang.t("saved"), javaFound = game.javaBinary() != null, lookTick = it.lookTick + 1) }
    }

    fun noteLook() {
        _state.update { it.copy(lookTick = it.lookTick + 1) }
    }

    fun tell(text: String) {
        _state.update { it.copy(info = text) }
    }

    fun clearCache() {
        clearDir(AppPaths.cache)
        clearDir(AppPaths.tmp)
        _state.update { it.copy(info = "Кэш очищен. Миры, аккаунты и настройки не трогались.") }
    }

    fun resetGame() {
        if (_state.value.busy) return
        game.stop()
        deleteTree(AppPaths.gamesRoot)
        AppPaths.gamesRoot.mkdirs()
        cfg.commonWorld = true
        cfg.minecraftVersion = "1.20.1"
        cfg.loader = "fabric"
        cfg.fabricLoaderVersion = ""
        cfg.save()
        evaluate()
        _state.update { it.copy(info = "Скачанные версии удалены") }
    }

    fun reinstallLauncher() {
        if (_state.value.busy) return
        game.stop()
        AppPaths.root.listFiles()?.forEach { child ->
            if (child.name != "jre") deleteTree(child)
        }
        AppPaths.init(app)
        CwSettings().save()
        cfg.takeFrom(CwSettings.load())
        accounts.wipe()
        Lang.use(cfg.resolvedLanguage())
        evaluate()
        publishAccount()
        _state.update {
            it.copy(
                screen = Screen.HOME,
                info = "Переустановка выполнена. Фоны, аккаунты, миры, моды и скачанные версии удалены.",
                lookTick = it.lookTick + 1,
                accountTick = it.accountTick + 1
            )
        }
    }

    fun uninstallLauncher() {
        if (_state.value.busy) return
        game.stop()
        try {
            AppPaths.root.listFiles()?.forEach { deleteTree(it) }
            app.cacheDir.listFiles()?.forEach { deleteTree(it) }
            app.getExternalFilesDir(null)?.listFiles()?.forEach { deleteTree(it) }
        } catch (e: Exception) {
            CwLog.warn("Очистка перед удалением: ${e.message}")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun downloadLauncher() {
        val url = cfg.launcherDownloadUrl
        if (url.isBlank()) {
            _state.update { it.copy(info = "Адрес пакета лаунчера не задан") }
            return
        }
        if (!begin("Обновление лаунчера")) return
        scope.launch {
            try {
                setPhase(Phase.DOWNLOADING, "Обновление CWLauncher")
                val dest = File(AppPaths.updates, "CWLauncher.zip")
                val part = File(dest.parentFile, dest.name + ".part")
                if (part.isFile && part.length() > 0) {
                    CwLog.info("Продолжаю обновление лаунчера с ${formatBytes(part.length())}")
                }
                val meter = SpeedMeter()
                val tick = java.util.concurrent.atomic.AtomicLong(0)
                val report = { done: Long, total: Long ->
                    val now = System.currentTimeMillis()
                    val prev = tick.get()
                    if (now - prev >= 200 && tick.compareAndSet(prev, now)) {
                        val speed = meter.note(done)
                        val fraction = if (total > 0) done.toFloat() / total else 0f
                        _state.update {
                            it.copy(
                                progress = fraction,
                                progressText = "Обновление CWLauncher",
                                detail = "Обновление CWLauncher",
                                xferTitle = "Обновление CWLauncher",
                                xferDone = done,
                                xferTotal = total,
                                xferSpeed = speed
                            )
                        }
                    }
                }
                if (url.contains("drive.google.com")) {
                    Drive.download(net, url, dest, false, cancel, report)
                } else {
                    net.download(url, dest, null, 0, cancel, report)
                }
                endBusy()
                evaluate()
                _state.update {
                    it.copy(info = "Пакет сохранён: ${dest.absolutePath}. На сервере это архив Windows-лаунчера. Эта сборка Android — ${CwLog.VERSION}.")
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun stopGame() {
        game.stop()
        evaluate()
    }

    fun importSkin(bytes: ByteArray) {
        val account = accounts.active() ?: return
        val dir = File(AppPaths.skins, account.id)
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        File(dir, "skin.png").writeBytes(bytes)
        publishAccount()
    }

    fun reloadLog() {
        val text = if (AppPaths.mainLog.isFile) AppPaths.mainLog.readText().takeLast(12_000) else ""
        val gameLog = if (AppPaths.gameLog.isFile) AppPaths.gameLog.readText().takeLast(8_000) else ""
        _state.update { it.copy(logText = text + if (gameLog.isBlank()) "" else "\n\n--- latest.log ---\n" + gameLog) }
    }

    private fun boot() {
        try {
            bootText("Распаковка Java 17", 0.08f)
            try {
                BundledJava.ensure(app) { bootText(it, 0.16f) }
            } catch (e: Exception) {
                CwLog.error("Java: ${e.message}")
            }
            bootText("Загрузка данных о лаунчере", 0.32f)
            applyPatch(net, cfg)
            bootText("Проверка установленной игры", 0.55f)
            val ready = installer.versionReady(cfg.minecraftVersion)
            if (ready) filesNeedUpdate = installer.remoteJsonDiffers(cfg.minecraftVersion)
            bootText("Подгрузка версии лаунчера", 0.74f)
            fetchLauncherVersion()
            fetchBuildVersion(true)
            fetchNews()
            fetchNotes()
            checkServer()
            network = true
            bootText("Готово", 1f)
        } catch (e: Exception) {
            network = false
            CwLog.error("Подготовка: ${e.message}")
        } finally {
            booting = false
            publishAccount()
            evaluate()
            _state.update { it.copy(booting = false, javaFound = game.javaBinary() != null) }
        }
    }

    private fun bootText(text: String, progress: Float = _state.value.bootProgress) {
        _state.update { it.copy(bootText = text, bootProgress = progress.coerceIn(0f, 1f)) }
    }

    fun reloadNews() {
        scope.launch { fetchNews() }
    }

    fun reloadNotes() {
        scope.launch { fetchNotes() }
    }

    private fun refreshRemote(readZip: Boolean) {
        try {
            fetchLauncherVersion()
            fetchBuildVersion(readZip)
            if (cfg.newsUrl.isNotBlank()) fetchNews()
        } catch (e: Exception) {
            CwLog.warn("Плановая проверка: ${e.message}")
        }
        evaluate()
    }

    private fun fetchLauncherVersion() {
        try {
            val text = readRemoteText(net, cfg.lVersionUrl)
            val value = readVersionToken(text)
            cfg.remoteLauncherVersion = value
            if (value != null) CwLog.info("lVersion.txt = $value")
            _state.update { it.copy(launcherRemote = value) }
        } catch (e: Exception) {
            cfg.remoteLauncherVersion = null
            CwLog.warn("lVersion.txt недоступен: ${e.message}")
        }
    }

    private fun fetchBuildVersion(readZip: Boolean) {
        if (!cfg.commonWorldMods()) return
        val local = installer.installedBuild(cfg.minecraftVersion)
        if (local.isNullOrBlank()) {
            cfg.remoteBuildVersion = null
            CwLog.info("Папка модов пустая, сверка версии пропущена")
            return
        }
        val remote = try {
            readVersionToken(readRemoteText(net, cfg.cwVersionUrl))
        } catch (e: Exception) {
            CwLog.warn("cwVersion.txt: ${e.message}")
            null
        }
        cfg.remoteBuildVersion = remote
        if (remote == null) CwLog.info("Версия модов в папке $local, cwVersion.txt недоступен")
        else CwLog.info("Версия модов в папке $local, cwVersion.txt = $remote")
        if (readZip && remote != null && compareVersions(local, remote) < 0) {
            CwLog.info("На диске версия модов новее: $local → $remote")
        }
    }

    private fun fetchNews() {
        val item = loadNews(net, cfg)
        _state.update { it.copy(news = item) }
    }

    private fun fetchNotes() {
        val notes = loadUpdateNotes(net, cfg)
        _state.update { it.copy(notes = notes) }
    }

    private fun checkServer() {
        val info = ServerPing.check(cfg.serverIp)
        if (info.error == null) network = true
        _state.update { it.copy(server = info) }
    }

    fun evaluate() {
        if (_state.value.busy) return
        val version = cfg.minecraftVersion
        val jar = installer.clientJar(version).isFile
        val mcReady = installer.versionReady(version)
        val useFabric = cfg.loader.equals("fabric", true)
        val installedLoader = installer.installedLoader(version)
        val fabricReady = !useFabric || installer.fabricReady(version, installedLoader)
        val modsInstalled = installer.modsInstalled(version)
        val modsProblems = if (cfg.commonWorldMods() && cfg.verifyBuildOnLaunch) installer.verifyMods(version) else emptyList()
        val modsNeed = cfg.commonWorldMods() && cfg.autoUpdateBuild && cfg.remoteBuildVersion != null &&
            compareVersions(installer.installedBuild(version), cfg.remoteBuildVersion) < 0 &&
            installer.installedBuild(version) != null
        val gameNeeds = jar && (filesNeedUpdate || !mcReady || !fabricReady)
        pendingGameUpdate = gameNeeds
        val phase: Phase
        val detail: String
        if (game.running()) {
            if (game.booted) {
                phase = Phase.RUNNING
                detail = "Minecraft запущен"
            } else {
                phase = Phase.STARTING
                detail = "Minecraft запускается"
            }
        } else if (!jar) {
            phase = Phase.NOT_INSTALLED
            detail = "Minecraft $version не установлен"
        } else if (gameNeeds) {
            phase = Phase.UPDATE_AVAILABLE
            detail = when {
                !mcReady -> "Файлы игры скачаны не полностью"
                !fabricReady -> "Нужно обновить Fabric для Minecraft $version"
                else -> "Файлы Minecraft $version нужно обновить"
            }
        } else if (cfg.commonWorldMods() && !modsInstalled) {
            phase = Phase.NOT_INSTALLED
            detail = "Моды Common World не установлены"
        } else if (modsNeed) {
            phase = Phase.UPDATE_AVAILABLE
            detail = "Доступно обновление модов: ${cfg.remoteBuildVersion}"
        } else if (modsProblems.isNotEmpty()) {
            phase = Phase.ERROR
            detail = modsProblems.first()
        } else if (!network && cfg.offlineAllowed) {
            phase = Phase.OFFLINE
            detail = "Нет соединения — запуск из локальной установки"
        } else {
            phase = Phase.READY
            detail = "Сборка актуальна"
        }
        val build = installer.installedBuild(version)
        val modsCount = AppPaths.mods(version).listFiles()?.count { it.isFile && it.extension.equals("jar", true) } ?: 0
        val fabricLabel = if (installedLoader.isNullOrBlank()) "Fabric" else "Fabric $installedLoader"
        val label = if (cfg.commonWorld) "Common World · 1.20.1 · Fabric" else "$version · ${cfg.loader}"
        val buildLabel = when {
            !cfg.commonWorldMods() -> "Моды сборки не используются"
            build == null -> "Моды не установлены"
            modsNeed -> "Есть обновление $build → ${cfg.remoteBuildVersion}"
            else -> "Сборка: $build"
        }
        _state.update {
            it.copy(
                phase = phase,
                detail = detail,
                versionLabel = label,
                buildLabel = buildLabel,
                accountName = accounts.active()?.username?.ifBlank { "без имени" } ?: "Нет аккаунта",
                javaFound = game.javaBinary() != null,
                launcherRemote = cfg.remoteLauncherVersion,
                fabricLabel = fabricLabel,
                modsCount = modsCount,
                buildVersion = build ?: "",
                mcReady = mcReady,
                fabricOk = fabricReady,
                modsOk = modsInstalled && modsProblems.isEmpty() && !modsNeed
            )
        }
    }

    private fun requireAccount(): Boolean {
        if (accounts.active() != null) return true
        _state.update {
            it.copy(dialog = DialogModel(
                "Сначала создайте профиль: оффлайн-ник или вход Ely.by.",
                "Профиль", "Закрыть", "account"
            ))
        }
        return false
    }

    private fun install(thenLaunch: Boolean) {
        if (!begin("Установка")) return
        scope.launch {
            try {
                val version = cfg.minecraftVersion
                val useFabric = cfg.loader.equals("fabric", true)
                if (filesNeedUpdate || !installer.versionReady(version)) {
                    setPhase(Phase.INSTALLING, "Установка Minecraft $version")
                    installer.installMinecraft(version)
                    filesNeedUpdate = false
                }
                if (useFabric) {
                    val loader = cfg.fabricLoaderVersion.ifBlank {
                        installer.latestLoader(version) ?: installer.installedLoader(version) ?: ""
                    }
                    if (loader.isBlank()) throw java.io.IOException("Не удалось определить версию Fabric. Проверьте интернет.")
                    if (!installer.fabricReady(version, loader)) {
                        setPhase(Phase.INSTALLING, "Проверка Fabric")
                        installer.installFabric(version, loader)
                    }
                }
                val modsNeed = cfg.commonWorldMods() && (
                    !installer.modsInstalled(version) ||
                        (cfg.remoteBuildVersion != null &&
                            compareVersions(installer.installedBuild(version), cfg.remoteBuildVersion) < 0)
                    )
                if (modsNeed) {
                    launchAfterMods = thenLaunch
                    endBusy()
                    updateMods()
                    return@launch
                }
                endBusy()
                evaluate()
                if (thenLaunch) launchGame()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun updateMods() {
        if (_state.value.busy) return
        if (!cfg.commonWorldMods()) {
            launchAfterMods = false
            evaluate()
            return
        }
        if (!begin("Обновление сборки")) return
        scope.launch {
            try {
                setPhase(Phase.UPDATING, "Загрузка сборки Common World")
                if (cfg.remoteBuildVersion == null) fetchBuildVersion(true)
                installer.installMods(cfg.remoteBuildVersion)
                val start = launchAfterMods
                launchAfterMods = false
                endBusy()
                evaluate()
                if (start) launchGame()
            } catch (e: Exception) {
                launchAfterMods = false
                fail(e)
            }
        }
    }

    private fun launchGame() {
        if (!begin("Запуск")) return
        scope.launch {
            try {
                val customJava = cfg.javaPath.trim().let { path -> path.isNotEmpty() && File(path).isFile }
                if (!customJava) {
                    setPhase(Phase.STARTING, "Подготовка Java 17")
                    BundledJava.ensure(app) { text -> setPhase(Phase.STARTING, text) }
                }
                val account = accounts.active() ?: throw java.io.IOException("Нет аккаунта")
                val version = cfg.minecraftVersion
                val profileId = if (!cfg.loader.equals("fabric", true)) version else {
                    val loader = cfg.fabricLoaderVersion.ifBlank { installer.installedLoader(version) }
                        ?: throw java.io.IOException("Fabric не установлен")
                    "fabric-loader-$loader-$version"
                }
                setPhase(Phase.STARTING, "Проверка библиотек")
                installer.ensureRuntime(version, profileId)
                setPhase(Phase.STARTING, "Minecraft запускается")
                WorkService.show(app, "Minecraft запускается")
                game.start(account, profileId)
                endBusy()
                CwLog.info("Окно игры открывается на телефоне")
                val cursors = mutableMapOf<String, Long>()
                game.outputLogs().forEach { file ->
                    if (file.isFile) cursors[file.absolutePath] = file.length()
                }
                var lastLine = "Окно игры открывается на телефоне"
                var lastBeat = System.currentTimeMillis()
                val openedAt = System.currentTimeMillis()
                var diedEarly = false
                _state.update { it.copy(detail = lastLine, progressText = lastLine) }
                while (true) {
                    val stage = PhoneVm.stage()
                    val beat = PhoneVm.beat()
                    val stale = beat > 0 && System.currentTimeMillis() - beat > 12_000
                    if (stage == PhoneVm.Stage.EXITED) break
                    if (stage == PhoneVm.Stage.RUNNING && stale) break
                    if (stage == PhoneVm.Stage.STARTING && stale && System.currentTimeMillis() - openedAt > 15_000) {
                        diedEarly = true
                        break
                    }
                    if (stage == PhoneVm.Stage.IDLE) break
                    val lines = game.freshGameLines(cursors)
                    for (line in lines) {
                        val short = line.take(220)
                        CwLog.info("Игра: $short")
                        lastLine = short
                        lastBeat = System.currentTimeMillis()
                    }
                    if (lines.isNotEmpty()) {
                        _state.update { it.copy(detail = lastLine, progressText = lastLine) }
                    } else if (System.currentTimeMillis() - lastBeat > 5000) {
                        lastBeat = System.currentTimeMillis()
                        CwLog.info(lastLine)
                    }
                    if (game.windowReady() && _state.value.phase != Phase.RUNNING) {
                        _state.update { it.copy(phase = Phase.RUNNING, detail = "Minecraft запущен", progressText = "Minecraft запущен") }
                        CwLog.info("Окно Minecraft создано")
                    }
                    delay(400)
                }
                val code = PhoneVm.storedCode()
                val storedError = PhoneVm.storedError()
                CwLog.info("Игра завершилась с кодом $code")
                val showed = game.booted
                game.booted = false
                if (game.stoppedByUser || storedError == "stop") {
                    evaluate()
                } else if (diedEarly || storedError.isNotBlank() || !showed) {
                    val tail = gameLogsTail()
                    if (tail.isNotBlank()) CwLog.warn("Игра завершилась с ошибкой:\n$tail")
                    val message = when {
                        storedError.isNotBlank() && storedError != "stop" -> storedError
                        else -> explainCrash(tail, code)
                    }
                    evaluate()
                    _state.update {
                        it.copy(phase = Phase.ERROR, detail = message.lineSequence().first(), info = message)
                    }
                } else {
                    evaluate()
                }
                WorkService.hide(app)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun gameLogsTail(): String {
        val parts = game.outputLogs()
        return parts.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            try {
                file.readText().takeLast(8_000)
            } catch (_: Exception) {
                null
            }
        }.joinToString("\n")
    }

    private fun verifyThenRepair() {
        val problems = installer.verifyMods(cfg.minecraftVersion).toMutableList()
        if (!installer.versionReady(cfg.minecraftVersion)) problems.add("Файлы Minecraft не готовы")
        if (problems.isEmpty()) {
            evaluate()
            return
        }
        _state.update {
            it.copy(dialog = DialogModel(
                problems.joinToString("\n") + "\nВосстановить сборку?",
                "Восстановить", "Закрыть", "repair"
            ))
        }
    }

    private fun begin(name: String): Boolean {
        if (_state.value.busy) {
            CwLog.warn("Операция «$name» отклонена: уже выполняется")
            return false
        }
        cancel.set(false)
        deletePartialOnCancel = false
        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                progressText = name,
                xferTitle = "",
                xferDone = 0,
                xferTotal = 0,
                xferSpeed = 0,
                xferExtra = ""
            )
        }
        WorkService.show(app, name)
        return true
    }

    private fun deleteCancelledDownloads() {
        fun parts(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (file.name == "jre") return@forEach
                    parts(file)
                } else if (file.name.endsWith(".part") || file.name.endsWith(".drv")) {
                    file.delete()
                }
            }
        }
        parts(AppPaths.root)
        val version = cfg.minecraftVersion
        if (!installer.versionReady(version)) deleteTree(AppPaths.game(version))
    }

    private fun endBusy() {
        _state.update {
            it.copy(busy = false, progress = 0f, xferTitle = "", xferDone = 0, xferTotal = 0, xferSpeed = 0, xferExtra = "")
        }
        WorkService.hide(app)
    }

    private fun fail(e: Exception) {
        var cur: Throwable = e
        while (cur.cause != null && cur !is java.io.IOException) {
            cur = cur.cause!!
        }
        var message = cur.message ?: e.message ?: "Ошибка"
        if (message.contains("Pointer tag", ignoreCase = true)
            || message.contains("failed to connect", ignoreCase = true)
            || message.contains("timed out", ignoreCase = true)
            || message.contains("timeout", ignoreCase = true)
            || message.contains("unable to resolve", ignoreCase = true)
            || message.contains("no address associated", ignoreCase = true)
            || message.contains("sakura.sld.tw", ignoreCase = true)
        ) {
            message = "Нет связи с серверами Minecraft. Уже скачанное сохранено, нажмите ещё раз."
        }
        val cancelled = cancel.get() || message == "Отменено"
        val wipe = cancelled && deletePartialOnCancel
        deletePartialOnCancel = false
        if (cancelled) CwLog.warn(if (wipe) "Загрузка отменена, файлы удалены" else "Загрузка остановлена, скачанное сохранено")
        else CwLog.warn("Ошибка: $message\n${e.stackTraceToString().take(2000)}")
        endBusy()
        if (wipe) deleteCancelledDownloads()
        if (cancelled) {
            evaluate()
        } else {
            _state.update { it.copy(phase = Phase.ERROR, detail = message, info = message) }
        }
    }

    private fun explainCrash(log: String, code: Int): String {
        if (log.contains("ASM not detected") || log.contains("ClassReader.class was renamed")) {
            return "Не удалось запустить Minecraft.\n\nПричина:\nFabric Loader не нашёл библиотеку ASM.\n\nФайл:\norg.objectweb.asm.ClassReader\n\nНажмите «Повторить» — недостающая библиотека будет скачана заново. Подробности записаны в лог."
        }
        if (log.contains("UnsupportedClassVersionError")) {
            return "Не удалось запустить Minecraft.\n\nПричина:\nНужна Java 17.\n\nФайл:\nJava runtime"
        }
        val line = log.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull {
            val text = it.trim()
            if (text.isEmpty() || text.startsWith("A fatal error has been detected", true)) false
            else text.contains("Exception") || text.startsWith("Caused by:") || text.contains("dlopen failed")
                || text.contains("CANNOT LINK EXECUTABLE")
                || text.contains("SIGSEGV") || text.contains("SIGABRT") || text.contains("Internal Error")
                || text.contains("Problematic frame") || text.contains("Could not reserve")
                || text.contains("Unrecognized VM option") || text.contains("libc.so.6")
                || (text.contains("UnsatisfiedLinkError") && !text.contains("already", true))
                || text.contains("OutOfMemory")
                || text.contains("Failed to initialize GLFW") || text.contains("Failed to create the GLFW window")
                || text.contains("couldn't locate the game") || text.contains("Failed to attach")
                || text.contains("окно Minecraft не создано") || text.contains("завершился с ошибкой")
        }
        val reported = line ?: crashReportLine()
        val tailLine = log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().takeLast(8)
            .joinToString("\n")
        if (!reported.isNullOrBlank()) {
            return "Не удалось запустить Minecraft.\n\nПричина:\n${reported.take(220)}\n\nПодробности записаны в лог."
        }
        if (tailLine.isNotBlank()) {
            return "Minecraft закрылся, не открыв окно.\n\nПоследние строки:\n${tailLine.take(700)}"
        }
        if (code == 134) {
            return "Игра закрылась с кодом 134. Java была остановлена при запуске. Подробности записаны в лог."
        }
        return "Minecraft закрылся внутри графической библиотеки, окно не создано. Код $code."
    }

    private fun crashReportLine(): String? {
        val files = mutableListOf(File(AppPaths.logs, "hs_err.log"))
        AppPaths.logs.listFiles()?.filterTo(files) { it.name.startsWith("hs_err") && it.isFile }
        AppPaths.game(cfg.minecraftVersion).listFiles()?.filterTo(files) { it.name.startsWith("hs_err") && it.isFile }
        val text = files.filter { it.isFile && it.length() > 0 }.maxByOrNull { it.lastModified() }?.readText()?.take(12_000)
            ?: return null
        val lines = text.lineSequence().map { it.trim().trimStart('#', ' ') }.filter { it.isNotBlank() }.toList()
        return lines.firstOrNull { it.contains("Problematic frame") || it.contains("Internal Error") || it.contains("SIGSEGV") || it.contains("SIGABRT") }
            ?: lines.firstOrNull { it.startsWith("C ") || it.startsWith("V ") || it.startsWith("j ") }
    }

    private fun setPhase(phase: Phase, detail: String) {
        _state.update { it.copy(phase = phase, detail = detail, progressText = detail) }
        WorkService.show(app, detail)
    }

    private fun publishAccount() {
        _state.update {
            it.copy(
                accountName = accounts.active()?.username?.ifBlank { "без имени" } ?: "Нет аккаунта",
                accountTick = it.accountTick + 1
            )
        }
    }

    private fun reloadFiles(screen: Screen) {
        val root = if (screen == Screen.MODS) AppPaths.mods(cfg.minecraftVersion) else AppPaths.game(cfg.minecraftVersion)
        val list = root.listFiles()?.sortedBy { it.name }?.map { file ->
            if (file.isDirectory) file.name + "/" else file.name
        }.orEmpty()
        _state.update { it.copy(files = list, browserPath = root.absolutePath) }
    }
}
