package ru.cw.launcher.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.cw.launcher.CwApplication
import ru.cw.launcher.R
import ru.cw.launcher.engine.Account
import ru.cw.launcher.engine.AccountType
import ru.cw.launcher.engine.AppPaths
import ru.cw.launcher.engine.CwLog
import ru.cw.launcher.engine.Lang
import ru.cw.launcher.engine.CwSettings
import ru.cw.launcher.engine.Engine
import ru.cw.launcher.engine.formatBytes
import ru.cw.launcher.engine.formatEta
import ru.cw.launcher.engine.formatSpeed
import ru.cw.launcher.engine.Phase
import ru.cw.launcher.engine.Screen
import ru.cw.launcher.engine.UiState
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Panel = Color(0xD2081224)
private val Panel2 = Color(0xE6162744)
private val Field = Color(0xCC101A2C)
private val Line = Color(0xBE4C96FF)
private val TextMain = Color(0xFFF4F7FB)
private val Muted = Color(0xFF9BB0D0)
private val Green = Color(0xFF3DDC97)
private val Amber = Color(0xFFF0B429)
private val Red = Color(0xFFFF6B6B)
private val Blue = Color(0xFF3B82F6)
private val BlueDeep = Color(0xFF2F6FE0)
private val CardShape = RoundedCornerShape(18.dp)

class LauncherViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val engine: Engine = (app as CwApplication).engine
}

@Composable
fun CwRoot(model: LauncherViewModel = viewModel()) {
    val state by model.engine.state.collectAsState()
    Lang.use(model.engine.cfg.resolvedLanguage())
    val context = LocalContext.current
    BackHandler(enabled = state.screen != Screen.HOME && !state.booting) {
        model.engine.open(Screen.HOME)
    }
    val customBg = remember(state.lookTick) { loadBackground(model.engine.cfg.backgroundPath) }
    val dimOther = !state.booting && state.screen != Screen.HOME && state.screen != Screen.VERSIONS
    val dimAlpha = if (!dimOther) 0 else {
        val percent = model.engine.cfg.backgroundDim.coerceIn(0, 80) * 100 / 80
        percent * 220 / 100
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = customBg ?: painterResource(R.drawable.bg_world),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().then(if (dimOther && model.engine.cfg.uiBlur) Modifier.blur(12.dp) else Modifier),
            contentScale = ContentScale.Crop
        )
        if (dimAlpha > 0) Box(Modifier.fillMaxSize().background(Color(dimAlpha shl 24)))
        if (state.booting) {
            Splash(state.bootText, state.bootProgress)
        } else {
            when (state.screen) {
                Screen.HOME, Screen.VERSIONS -> Home(state, model.engine, context)
                Screen.SETTINGS -> SettingsPage(model.engine)
                Screen.ACCOUNT -> AccountPage(state, model.engine, context)
                Screen.NEWS -> NewsPage(state, model.engine)
                Screen.UPDATES -> UpdatesPage(state, model.engine)
                Screen.FILES -> FilesPage("Папка Minecraft", state, model.engine)
                Screen.MODS -> FilesPage("Моды", state, model.engine)
                Screen.SKIN -> SkinPage(model.engine)
                Screen.LOG -> LogPage(state, model.engine)
            }
            if (state.screen == Screen.VERSIONS) VersionPopup(state, model.engine)
        }
        state.dialog?.let { dialog ->
            Notice(dialog.text, dialog.confirm, dialog.dismiss, { model.engine.confirmDialog() }, { model.engine.dismissDialog() })
        }
        state.info?.let { text ->
            Notice(text, "Закрыть", null, { model.engine.dismissInfo() }, { model.engine.dismissInfo() })
        }
    }
}

@Composable
private fun Splash(text: String, progress: Float) {
    val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CWLauncher", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(text, color = Color(0xFFC5D4EA), fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = Blue,
                trackColor = Color(0xFF162033),
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(10.dp))
            Text("$percent%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Home(state: UiState, engine: Engine, context: Context) {
    val server = state.server
    val clock = rememberClock()
    val fps = rememberFps()
    val compact = state.lookTick >= 0 && engine.cfg.uiStyle == "compact"
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton("site") { openLink(context, "https://commonworld.ru/index.php") }
                Spacer(Modifier.width(6.dp))
                IconButton("send") { openLink(context, engine.cfg.telegramUrl) }
                Spacer(Modifier.width(6.dp))
                IconButton("chat") { openLink(context, engine.cfg.discordUrl) }
                Spacer(Modifier.width(6.dp))
                IconButton("user") { engine.open(Screen.ACCOUNT) }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Text("CW ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Launcher", color = Color(0xFF4C8DFF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text("Common World", color = Color(0xFF9BB4E0), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(0.30f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Glass(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("mountain", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Common World", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "●  ${server?.statusText() ?: "Статус сервера недоступен"}",
                        color = if (server?.online == true) Green else Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "IP: ${engine.cfg.serverIp}",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x332F6FE0)).clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ip", engine.cfg.serverIp))
                                Toast.makeText(context, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                            },
                            contentAlignment = Alignment.Center
                        ) { Glyph("copy", TextMain, Modifier.size(12.dp)) }
                    }
                }
                Glass(Modifier.weight(1f).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("news", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Lang.t("news"), color = Blue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.news?.title ?: "Новости недоступны",
                        color = TextMain,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!state.news?.text.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(state.news?.text.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        Lang.t("more"),
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.End).clickable { engine.open(Screen.NEWS) }
                    )
                }
            }
            Column(
                Modifier.weight(0.40f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Glass(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MinecraftMark(Modifier.size(36.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (engine.cfg.commonWorld) "Сервер Common World" else state.versionLabel,
                                color = TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("Minecraft ${engine.cfg.minecraftVersion}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val tone = statusTone(state.phase)
                Text("●  ${readyCaption(state)}", color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.busy) {
                    Spacer(Modifier.height(4.dp))
                    if (state.xferTotal > 0L) {
                        TransferBar(state)
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                            color = Blue,
                            trackColor = Field,
                            drawStopIndicator = {}
                        )
                        Text(state.progressText, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!state.javaFound && !state.busy) {
                    Text(Lang.t("java_missing"), color = Amber, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { engine.onMain() },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor(state.phase, state.busy), contentColor = Color.White)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!state.busy && state.phase != Phase.RUNNING) Glyph("play", Color.White, Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(buttonLabel(state.phase, state.busy, state.detail), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (state.phase == Phase.RUNNING) {
                    Spacer(Modifier.height(4.dp))
                    GhostButton(Lang.t("close_game")) { engine.stopGame() }
                }
                Spacer(Modifier.height(6.dp))
                GhostButton(Lang.t("version"), "chevron") { engine.open(Screen.VERSIONS) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { engine.open(Screen.SETTINGS) },
                    modifier = Modifier.width(180.dp).height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1A2C4A), contentColor = TextMain)
                ) {
                    Glyph("gear", TextMain, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Lang.t("settings"), fontSize = 13.sp)
                }
                val remote = state.launcherRemote
                if (remote != null && remote != CwLog.VERSION) {
                    TextButton(onClick = { engine.downloadLauncher() }) {
                        Text("Обновить лаунчер $remote", color = Amber, fontSize = 11.sp)
                    }
                }
            }
            Column(Modifier.weight(0.30f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Glass(Modifier.fillMaxWidth()) {
                    Text(Lang.t("status_build"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    CheckLine(state.mcReady, "Minecraft ${engine.cfg.minecraftVersion}")
                    CheckLine(state.fabricOk, state.fabricLabel)
                    CheckLine(state.modsOk && state.modsCount > 0, "Моды (${state.modsCount})")
                    CheckLine(state.buildVersion.isNotBlank(), "Версия модов: ${state.buildVersion.ifBlank { "—" }}")
                    CheckLine(server?.online == true, server?.statusText() ?: "Статус сервера недоступен", badIsRed = true)
                }
                Glass(Modifier.weight(1f).fillMaxWidth()) {
                    Text(Lang.t("latest"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    val note = state.notes.firstOrNull()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x332F6FE0)).clickable { engine.open(Screen.UPDATES) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Glyph("info", Blue, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(note?.title ?: "Список обновлений пуст", color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Lang.t("more"), color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).clickable { engine.open(Screen.UPDATES) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(58.dp)) {
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionTile("folder", Lang.t("folder")) { engine.open(Screen.FILES) }
                ActionTile("box", Lang.t("mods")) { engine.open(Screen.MODS) }
                ActionTile("skin", Lang.t("skin")) { engine.open(Screen.SKIN) }
            }
            Button(
                onClick = { openLink(context, engine.cfg.supportUrl) },
                modifier = Modifier.align(Alignment.Center).height(34.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1A2C4A), contentColor = TextMain)
            ) {
                Glyph("info", TextMain, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(Lang.t("support"), fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "●  ${server?.statusText() ?: Lang.t("foot_none")}",
                color = if (server?.online == true) Green else Muted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (state.modsOk) "●  ${Lang.t("mods_fresh")}" else "●  ${Lang.t("mods_check")}",
                color = if (state.modsOk) Green else Amber,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (engine.cfg.showFps) "$clock   $fps FPS   CWLauncher v${CwLog.VERSION}" else "$clock   CWLauncher v${CwLog.VERSION}",
                color = Muted,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(Lang.t("logs"), color = Muted, fontSize = 10.sp, modifier = Modifier.clickable { engine.open(Screen.LOG) })
        }
    }
}

@Composable
private fun SettingsPage(engine: Engine) {
    val cfg = engine.cfg
    val context = LocalContext.current
    val totalMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1024)
    }
    val capGb = (totalMb / 1024).coerceAtLeast(1)
    var tab by remember { mutableIntStateOf(0) }
    var ramGb by remember { mutableIntStateOf(if (cfg.ramMb > 0) ((cfg.ramMb + 512) / 1024).coerceIn(1, capGb) else (totalMb / 2048).coerceIn(1, capGb)) }
    var autoStart by remember { mutableStateOf(cfg.autoStart) }
    var autoBuild by remember { mutableStateOf(cfg.autoUpdateBuild) }
    var autoLauncher by remember { mutableStateOf(cfg.autoUpdateLauncher) }
    var launcherEvery by remember { mutableStateOf(cfg.launcherUpdateEvery) }
    var modsEvery by remember { mutableStateOf(cfg.modsUpdateEvery) }
    var timeUnit by remember { mutableStateOf(cfg.playTimeUnit) }
    var offline by remember { mutableStateOf(cfg.offlineAllowed) }
    var verify by remember { mutableStateOf(cfg.verifyBuildOnLaunch) }
    var backups by remember { mutableStateOf(cfg.modBackups) }
    var copies by remember { mutableIntStateOf(if (cfg.modBackupCount >= 10) 10 else if (cfg.modBackupCount <= 3) 3 else 5) }
    var dev by remember { mutableStateOf(cfg.developerMode) }
    var verbose by remember { mutableStateOf(cfg.verboseLogging) }
    var leader by remember { mutableStateOf(cfg.internetLeader) }
    var limitNet by remember { mutableStateOf(cfg.limitBandwidth) }
    var band by remember { mutableIntStateOf(cfg.bandwidthMbit) }
    var disk by remember { mutableStateOf(cfg.checkDiskSpace) }
    var proxy by remember { mutableStateOf(cfg.useSystemProxy) }
    var notes by remember { mutableStateOf(cfg.notificationsEnabled) }
    var language by remember { mutableStateOf(cfg.gameLanguage) }
    var ownJava by remember { mutableStateOf(cfg.javaPath.isNotBlank()) }
    var javaPath by remember { mutableStateOf(cfg.javaPath) }
    var jvm by remember { mutableStateOf(cfg.extraJvmArgs) }
    var width by remember { mutableStateOf(cfg.screenWidth.toString()) }
    var height by remember { mutableStateOf(cfg.screenHeight.toString()) }
    var fps by remember { mutableStateOf(cfg.framerateLimit.toString()) }
    var fullscreen by remember { mutableStateOf(cfg.screenFullscreen) }
    var vsync by remember { mutableStateOf(cfg.vsync) }
    var noFs by remember { mutableStateOf(cfg.disableFullscreenOpt) }
    var highPri by remember { mutableStateOf(cfg.highPriority) }
    var noRt by remember { mutableStateOf(cfg.disableRealtimeOpt) }
    var effects by remember { mutableStateOf(cfg.uiEffects) }
    var blur by remember { mutableStateOf(cfg.uiBlur) }
    var anim by remember { mutableStateOf(cfg.uiAnim) }
    var fade by remember { mutableStateOf(cfg.uiTransitions) }
    var style by remember { mutableStateOf(cfg.uiStyle) }
    var showFps by remember { mutableStateOf(cfg.showFps) }
    var dim by remember { mutableIntStateOf((cfg.backgroundDim.coerceIn(0, 80) * 100 / 80).coerceIn(0, 100)) }
    var askWipe by remember { mutableStateOf(false) }
    var askReinstall by remember { mutableStateOf(false) }
    var uninstallStep by remember { mutableIntStateOf(0) }
    var bgTick by remember { mutableIntStateOf(0) }
    var cacheLabel by remember { mutableStateOf(human(folderBytes(AppPaths.cache) + folderBytes(AppPaths.tmp))) }
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = copyBackground(context, uri) ?: return@rememberLauncherForActivityResult
        engine.cfg.backgroundPath = dest.absolutePath
        engine.cfg.save()
        engine.noteLook()
    }
    fun save() {
        val startOn = autoStart
        val syncOn = vsync
        val fpsOn = showFps
        val javaFile = if (ownJava) javaPath.trim() else ""
        engine.saveSettings {
            ramMb = ramGb * 1024
            autoStart = startOn
            autoUpdateBuild = autoBuild
            autoUpdateLauncher = autoLauncher
            launcherUpdateEvery = launcherEvery
            modsUpdateEvery = modsEvery
            playTimeUnit = timeUnit
            offlineAllowed = offline
            verifyBuildOnLaunch = verify
            modBackups = backups
            modBackupCount = copies
            developerMode = dev
            verboseLogging = verbose
            internetLeader = leader
            limitBandwidth = limitNet
            bandwidthMbit = if (limitNet) band else 0
            checkDiskSpace = disk
            useSystemProxy = proxy
            notificationsEnabled = notes
            gameLanguage = language
            javaPath = javaFile
            extraJvmArgs = jvm
            screenWidth = width.toIntOrNull() ?: 1280
            screenHeight = height.toIntOrNull() ?: 720
            framerateLimit = fps.toIntOrNull() ?: 0
            screenFullscreen = fullscreen
            vsync = syncOn
            disableFullscreenOpt = noFs
            highPriority = highPri
            disableRealtimeOpt = noRt
            uiEffects = effects
            uiBlur = blur
            uiAnim = anim
            uiTransitions = fade
            uiStyle = style
            showFps = fpsOn
            backgroundDim = dim * 80 / 100
        }
    }
    fun reset() {
        val defGb = (totalMb / 2048).coerceIn(1, capGb)
        ramGb = defGb
        autoStart = true
        autoBuild = true
        autoLauncher = true
        launcherEvery = "day"
        modsEvery = "day"
        timeUnit = "sec"
        offline = true
        verify = true
        backups = true
        copies = 5
        dev = false
        verbose = false
        leader = false
        limitNet = false
        band = 0
        disk = true
        proxy = false
        notes = true
        language = ""
        ownJava = false
        javaPath = ""
        jvm = ""
        width = "1280"
        height = "720"
        fps = "240"
        fullscreen = false
        vsync = true
        noFs = false
        highPri = false
        noRt = false
        effects = true
        blur = true
        anim = true
        fade = false
        style = "standard"
        showFps = false
        dim = 60
        engine.cfg.backgroundDim = 48
        engine.noteLook()
    }
    val every = listOf("day" to Lang.t("every_day"), "start" to Lang.t("every_start"), "manual" to Lang.t("every_manual"))
    val times = listOf("sec" to Lang.t("time_sec"), "min" to Lang.t("time_min"), "hour" to Lang.t("time_hour"), "day" to Lang.t("time_day"), "week" to Lang.t("time_week"))
    val bands = listOf(0 to Lang.t("band_none"), 5 to "5 ${Lang.t("mbit")}", 10 to "10 ${Lang.t("mbit")}", 25 to "25 ${Lang.t("mbit")}", 50 to "50 ${Lang.t("mbit")}", 100 to "100 ${Lang.t("mbit")}")
    val lookTick = engine.state.collectAsState().value.lookTick
    val preview = remember(lookTick) { loadBackground(engine.cfg.backgroundPath) }
    Overlay("CWLauncher", "Настройки", engine) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Lang.t("tab_main") to "gear", Lang.t("tab_game") to "play", Lang.t("tab_mods") to "box", Lang.t("tab_look") to "news", Lang.t("tab_extra") to "info").forEachIndexed { index, item ->
                val on = tab == index
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xE6244882) else Color(0xD2122A4E)).border(1.dp, Color(0xFF4C8DFF), RoundedCornerShape(12.dp)).clickable { tab = index }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(item.second, Color.White, Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(item.first, color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> Split {
                OptionCard(Lang.t("autostart"), Lang.t("autostart_h"), autoStart, { autoStart = it })
                OptionCard(Lang.t("autoupdate"), Lang.t("autoupdate_h"), autoLauncher, { autoLauncher = it }) {
                    Text(Lang.t("check_updates"), color = Muted, fontSize = 11.sp)
                    Choice(every.labelOf(launcherEvery), every.map { it.second }) { launcherEvery = every[it].first }
                }
                OptionCard(Lang.t("behavior"), Lang.t("behavior_h"), null, null) {
                    Text(Lang.t("on_launch"), color = Muted, fontSize = 11.sp)
                    Text(Lang.t("menu_behavior"), color = TextMain, fontSize = 13.sp)
                }
            }
            1 -> Split {
                OptionCard(Lang.t("ram"), "${Lang.t("ram_hint")} $capGb ${Lang.t("gb")}.", null, null) {
                    Text("$ramGb ${Lang.t("gb")} ${Lang.t("ram_of")} $capGb ${Lang.t("gb")}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Slider(ramGb.toFloat(), { ramGb = it.toInt().coerceIn(1, capGb) }, valueRange = 1f..capGb.toFloat(), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
                OptionCard(Lang.t("java"), Lang.t("java_h"), ownJava, { ownJava = it }) {
                    Text(if (engine.state.value.javaFound) "Java найдена" else "Будет использована встроенная Java 17", color = Muted, fontSize = 11.sp)
                    Field("Путь к Java", javaPath, ownJava) { javaPath = it }
                }
                OptionCard(Lang.t("jvm"), Lang.t("jvm_h"), null, null) {
                    Field("Аргументы", jvm) { jvm = it }
                }
            }
            2 -> Split {
                OptionCard(Lang.t("modsup"), Lang.t("modsup_h"), autoBuild, { autoBuild = it }) {
                    Text(Lang.t("check_updates"), color = Muted, fontSize = 11.sp)
                    Choice(every.labelOf(modsEvery), every.map { it.second }) { modsEvery = every[it].first }
                }
                OptionCard(Lang.t("verify"), Lang.t("verify_h"), verify, { verify = it })
                OptionCard(Lang.t("modmgr"), Lang.t("modmgr_h"), null, null) {
                    GhostButton(Lang.t("open_mods")) { engine.open(Screen.MODS) }
                }
            }
            3 -> Split {
                OptionCard(Lang.t("bg"), Lang.t("bg_h"), null, null) {
                    Image(
                        painter = preview ?: painterResource(R.drawable.bg_world),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                    val chosen = engine.cfg.backgroundPath
                    BackgroundRow(Lang.t("bg_main"), chosen.isBlank()) {
                        engine.cfg.backgroundPath = ""
                        engine.cfg.save()
                        engine.noteLook()
                    }
                    backgroundFiles().forEach { file ->
                        if (bgTick < 0) return@forEach
                        BackgroundRow(file.name, file.absolutePath == chosen, onDelete = {
                            val was = engine.cfg.backgroundPath == file.absolutePath
                            file.delete()
                            if (was) {
                                engine.cfg.backgroundPath = ""
                                engine.cfg.save()
                            }
                            bgTick++
                            engine.noteLook()
                        }) {
                            engine.cfg.backgroundPath = file.absolutePath
                            engine.cfg.save()
                            engine.noteLook()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    GhostButton(Lang.t("add_bg")) { pickBg.launch("image/*") }
                }
                OptionCard(Lang.t("dim"), Lang.t("dim_hint"), null, null) {
                    Text("$dim%", color = Color.White, fontWeight = FontWeight.Bold)
                    Slider(dim.toFloat(), {
                        val next = it.toInt()
                        dim = next
                        engine.cfg.backgroundDim = next * 80 / 100
                        engine.cfg.save()
                        engine.noteLook()
                    }, valueRange = 0f..100f, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
                }
                OptionCard(Lang.t("blur"), Lang.t("blur_h"), blur, {
                    blur = it
                    engine.cfg.uiBlur = it
                    engine.cfg.save()
                    engine.noteLook()
                })
                OptionCard(Lang.t("look_theme"), Lang.t("look_theme_h"), null, null) {
                    Text(Lang.t("color"), color = Muted, fontSize = 11.sp)
                    Text(Lang.t("blue"), color = TextMain, fontSize = 13.sp)
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OptionCard(Lang.t("cache"), Lang.t("cache_h"), null, null) {
                            Text("${Lang.t("cache_size")}$cacheLabel", color = Muted, fontSize = 12.sp)
                            GhostButton(Lang.t("clear")) {
                                engine.clearCache()
                                cacheLabel = human(folderBytes(AppPaths.cache) + folderBytes(AppPaths.tmp))
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        OptionCard(Lang.t("logs"), Lang.t("logs_h"), null, null) {
                            GhostButton(Lang.t("logs")) { engine.open(Screen.LOG) }
                        }
                    }
                }
                OptionCard(Lang.t("extra_opts"), Lang.t("extra_h"), null, null) {
                    Toggle(Lang.t("fps"), showFps) { showFps = it; engine.cfg.showFps = it; engine.cfg.save(); engine.noteLook() }
                    Toggle(Lang.t("disk"), disk) { disk = it }
                    Toggle(Lang.t("proxy"), proxy) { proxy = it }
                    Toggle(Lang.t("offline"), offline) { offline = it }
                    Toggle(Lang.t("dev"), dev) { dev = it }
                    Toggle(Lang.t("verbose"), verbose) { verbose = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OptionCard(Lang.t("downloads"), Lang.t("downloads_h"), null, null) {
                            GhostButton(Lang.t("open_dl")) { engine.tell("${Lang.t("downloads")}:\n${AppPaths.cache.absolutePath}") }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        OptionCard(Lang.t("diag"), Lang.t("diag_h"), null, null) {
                            GhostButton(Lang.t("logs")) { engine.open(Screen.LOG) }
                        }
                    }
                }
                OptionCard(Lang.t("reset_card"), Lang.t("reset_h"), null, null) {
                    GhostButton(Lang.t("reset_short")) { reset() }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("wipe_ver")) { askWipe = true }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("wipe_re")) { askReinstall = true }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(Lang.t("wipe_all")) { uninstallStep = 1 }
                }
            }
        }
        if (tab == 0) {
            Spacer(Modifier.height(8.dp))
            val density = LocalDensity.current
            var notesHeight by remember { mutableIntStateOf(0) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).onSizeChanged { notesHeight = it.height }) {
                    OptionCard(Lang.t("notes"), Lang.t("notes_h"), notes, { notes = it }) {
                        Text(Lang.t("time_show"), color = Muted, fontSize = 11.sp)
                        Choice(times.labelOf(timeUnit), times.map { it.second }) { timeUnit = times[it].first }
                    }
                }
                Box(Modifier.weight(1f).heightIn(min = with(density) { notesHeight.toDp() })) {
                    OptionCard(Lang.t("lang"), Lang.t("lang_hint"), null, null, stretch = true) {
                        Text(Lang.t("lang_pick"), color = Muted, fontSize = 11.sp)
                        Choice(languageName(language), languageOptions.map { if (it.first.isEmpty()) Lang.t("lang_sys") else it.second }) {
                            language = languageOptions[it].first
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OptionCard(Lang.t("theme"), Lang.t("theme_hint"), null, null) {
                Text(Lang.t("color"), color = Muted, fontSize = 11.sp)
                Text(Lang.t("blue"), color = TextMain, fontSize = 13.sp)
            }
        }
        if (tab == 1) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("leader"), Lang.t("leader_h"), leader, { leader = it }) {
                        Toggle(Lang.t("limit_name"), limitNet) { limitNet = it }
                        Text(Lang.t("mbit"), color = Muted, fontSize = 11.sp)
                        Choice(bands.firstOrNull { it.first == band }?.second ?: "Без ограничений", bands.map { it.second }) { band = bands[it].first }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("gameopt"), Lang.t("gameopt_h"), null, null) {
                        Toggle(Lang.t("nofull"), noFs) { noFs = it }
                        Toggle(Lang.t("hipri"), highPri) { highPri = it }
                        Toggle(Lang.t("nort"), noRt) { noRt = it }
                        Text(Lang.t("res"), color = Muted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(1f)) { Field("Ширина", width) { width = it.filter { ch -> ch.isDigit() } } }
                            Box(Modifier.weight(1f)) { Field("Высота", height) { height = it.filter { ch -> ch.isDigit() } } }
                        }
                        Toggle(Lang.t("fullscreen"), fullscreen) { fullscreen = it }
                        Toggle("VSync", vsync) { vsync = it }
                        Field(Lang.t("fps_cap"), fps) { fps = it.filter { ch -> ch.isDigit() } }
                    }
                }
            }
        }
        if (tab == 2) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("sources"), Lang.t("sources_h"), null, null) {
                        GhostButton(Lang.t("edit")) { engine.tell(Lang.t("sources_h")) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("backups"), Lang.t("backups_h"), backups, { backups = it }) {
                        Text(Lang.t("copies"), color = Muted, fontSize = 11.sp)
                        Choice(copies.toString(), listOf("3", "5", "10")) { copies = listOf(3, 5, 10)[it] }
                    }
                }
            }
        }
        if (tab == 3) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("effects"), Lang.t("effects_h"), effects, {
                        effects = it
                        engine.cfg.uiEffects = it
                        engine.cfg.save()
                        engine.noteLook()
                    }) {
                        Toggle(Lang.t("anim"), anim) { anim = it; engine.cfg.uiAnim = it; engine.cfg.save() }
                        Toggle(Lang.t("fade"), fade) { fade = it; engine.cfg.uiTransitions = it; engine.cfg.save() }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OptionCard(Lang.t("uistyle"), Lang.t("uistyle_h"), null, null) {
                        Choice(if (style == "compact") Lang.t("style_compact") else Lang.t("style_std"), listOf(Lang.t("style_std"), Lang.t("style_compact"))) {
                            style = if (it == 1) "compact" else "standard"
                            engine.cfg.uiStyle = style
                            engine.cfg.save()
                            engine.noteLook()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { reset() }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xD2122A4E), contentColor = TextMain)) {
                Text(Lang.t("reset"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { save() }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) {
                Text(Lang.t("save"))
            }
        }
    }
    if (askWipe) {
        Notice(
            "Удалить все версии игры, моды и миры? Лаунчер останется, но игру нужно будет скачать заново.",
            "Удалить версии",
            "Отмена",
            { askWipe = false; engine.resetGame() },
            { askWipe = false }
        )
    }
    if (askReinstall) {
        Notice(
            "Переустановка сотрёт все данные: добавленные фоны, аккаунты, миры, моды и скачанные версии. Само приложение останется.",
            "Переустановить",
            "Отмена",
            { askReinstall = false; engine.reinstallLauncher() },
            { askReinstall = false }
        )
    }
    if (uninstallStep == 1) {
        Notice(
            "Вы действительно хотите удалить лаунчер?",
            "Продолжить",
            "Отмена",
            { uninstallStep = 2 },
            { uninstallStep = 0 }
        )
    }
    if (uninstallStep == 2) {
        Notice(
            "Удалить CWLauncher окончательно? Приложение и все его файлы будут удалены. Вернуть их будет нельзя.",
            "Удалить лаунчер",
            "Отмена",
            { uninstallStep = 0; engine.uninstallLauncher() },
            { uninstallStep = 0 }
        )
    }
}

@Composable
private fun NewsPage(state: UiState, engine: Engine) {
    SplitList(
        title = Lang.t("news"),
        items = emptyList(),
        selected = 0,
        onSelect = {},
        repeat = { engine.reloadNews() },
        showRepeat = false,
        showList = false,
        engine = engine
    ) {
        Text(state.news?.title ?: Lang.t("news_down"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(state.news?.text?.ifBlank { Lang.t("news_empty") } ?: Lang.t("news_empty"), color = TextMain, fontSize = 13.sp)
    }
}

@Composable
private fun UpdatesPage(state: UiState, engine: Engine) {
    var index by remember { mutableIntStateOf(0) }
    val items = state.notes
    val safe = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    SplitList(
        title = Lang.t("updates_title"),
        items = if (items.isEmpty()) listOf(Lang.t("updates_empty")) else items.map { it.title },
        selected = safe,
        onSelect = { index = it },
        repeat = { engine.reloadNotes() },
        showRepeat = true,
        showList = true,
        scrollBody = true,
        engine = engine
    ) {
        val note = items.getOrNull(safe)
        Text(note?.title ?: Lang.t("updates_empty"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(note?.text ?: Lang.t("updates_hint"), color = TextMain, fontSize = 13.sp)
    }
}

@Composable
private fun SplitList(
    title: String,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    repeat: () -> Unit,
    showRepeat: Boolean,
    showList: Boolean,
    scrollBody: Boolean = false,
    engine: Engine,
    body: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.fillMaxSize().clip(CardShape).background(Color(0xE60E1830)).border(1.dp, Line, CardShape).padding(12.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showList) {
                    Column(Modifier.wrapContentWidth().widthIn(max = 220.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.forEachIndexed { i, name ->
                            val on = i == selected
                            Text(
                                name,
                                color = TextMain,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.wrapContentWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) BlueDeep else Color(0x66101A2C)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
                if (scrollBody) {
                    ScrollPane(Modifier.weight(1f).fillMaxHeight()) { body() }
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) { body() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (showRepeat) {
                    Button(onClick = repeat, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text(Lang.t("repeat")) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = { engine.open(Screen.HOME) }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243656), contentColor = TextMain)) { Text(Lang.t("close")) }
            }
        }
    }
}

@Composable
private fun AccountPage(state: UiState, engine: Engine, context: Context) {
    val tick = state.accountTick
    val current = engine.accounts.active()
    var form by remember { mutableStateOf<String?>(null) }
    var nick by remember { mutableStateOf("") }
    var elyId by remember { mutableStateOf(engine.cfg.elyClientId) }
    var elyRedirect by remember { mutableStateOf(engine.cfg.elyRedirectUri) }
    var elyBackend by remember { mutableStateOf(engine.cfg.elyBackendUrl) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
            Toast.makeText(context, "Скин не сохранён", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        engine.importSkin(bytes)
    }
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Column(
            Modifier.fillMaxSize().clip(CardShape).background(Color(0xE60E1830)).border(1.dp, Line, CardShape).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CWLauncher", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Профиль", color = Muted, fontSize = 11.sp)
                }
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF243656)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
                    Text("×", color = TextMain, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCard("Текущий аккаунт") {
                        if (current == null) {
                            Text("Аккаунтов нет", color = TextMain, fontWeight = FontWeight.Bold)
                            Text("Создайте профиль, чтобы устанавливать версии и запускать игру.", color = Muted, fontSize = 11.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Face(current, 52.dp, tick)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(current.username.ifBlank { "—" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(if (current.type == AccountType.ELY) "Аккаунт Ely.by" else "Оффлайн", color = Muted, fontSize = 11.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MiniPill("Изменить имя") {
                                            if (current.type == AccountType.ELY) form = "ely-name" else {
                                                nick = current.username
                                                form = "rename"
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text("●  Активный", color = Green, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    ProfileCard("Информация об аккаунте") {
                        InfoLine("ID аккаунта", if (current == null) "—" else shortAccountId(current), Blue)
                        InfoLine("Платформа", if (current == null) "—" else if (current.type == AccountType.ELY) "Ely.by" else "Оффлайн", TextMain)
                        InfoLine("Дата создания", formatCreated(current?.createdAt ?: 0L), TextMain)
                        InfoLine("Статус", if (current == null) "—" else "Активный", if (current == null) Muted else Green)
                    }
                    ProfileCard("Настройки аккаунта") {
                        ActionLine("Сменить аватарку", "Голова скина этого аккаунта. Если файла нет, остаётся значок профиля.") {
                            if (current == null) form = "need" else pick.launch("image/*")
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCard("Управление аккаунтами") {
                        ActionLine("Создать новый аккаунт", "Добавьте аккаунт Ely.by или оффлайн-ник.") {
                            nick = ""
                            form = "create"
                        }
                        Spacer(Modifier.height(6.dp))
                        ActionLine("Удалить аккаунт", "Удалить выбранный аккаунт из лаунчера.") {
                            if (current != null) form = "delete"
                        }
                    }
                    ProfileCard("Вход через Ely.by") {
                        Text("Ely.by", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Войдите через Ely.by. Пароль вводится только на сайте, лаунчер его не видит.", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { engine.beginEly { openLink(context, it) } },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)
                        ) { Text("Войти через Ely.by", fontSize = 12.sp) }
                        Spacer(Modifier.height(4.dp))
                        MiniPill("Параметры входа") {
                            elyId = engine.cfg.elyClientId
                            elyRedirect = engine.cfg.elyRedirectUri
                            elyBackend = engine.cfg.elyBackendUrl
                            form = "ely"
                        }
                    }
                    ProfileCard("Список аккаунтов") {
                        if (engine.accounts.items.isEmpty()) {
                            Text("Список пуст", color = Muted, fontSize = 12.sp)
                        }
                        engine.accounts.items.forEach { account ->
                            val on = account.id == current?.id
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, if (on) Blue else Color(0xFF1E5AA8), RoundedCornerShape(8.dp)).clickable { engine.selectAccount(account.id) }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Face(account, 32.dp, tick)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account.username.ifBlank { "—" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(if (account.type == AccountType.ELY) "Ely.by" else "Оффлайн", color = Muted, fontSize = 11.sp)
                                }
                                if (on) Text("●", color = Green, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Аватарка — голова скина этого аккаунта. Этот скин уходит в игру.", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                MiniPill("Назад") { engine.open(Screen.HOME) }
            }
        }
        when (form) {
            "create" -> FormBox("Новый аккаунт", { form = null }) {
                Field("Ник", nick) { nick = it }
                Text("Добавьте аккаунт Ely.by или оффлайн-ник.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Войти через Ely.by") {
                        form = null
                        engine.beginEly { openLink(context, it) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        try {
                            engine.addOffline(nick)
                            form = null
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Ник не подошёл", Toast.LENGTH_SHORT).show()
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text("Создать оффлайн") }
                }
            }
            "rename" -> FormBox("Изменить имя", { form = null }) {
                Field("Ник", nick) { nick = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val error = current?.let { engine.renameOffline(it.id, nick) }
                        if (error == null) form = null else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text("Сохранить") }
                }
            }
            "delete" -> FormBox("Удалить аккаунт", { form = null }) {
                Text("Удалить аккаунт ${current?.username.orEmpty()}?", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        current?.let { engine.removeAccount(it.id) }
                        form = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("Удалить аккаунт") }
                }
            }
            "ely" -> FormBox("Параметры входа", { form = null }) {
                Field("Client id Ely.by", elyId) { elyId = it }
                Field("Redirect URI", elyRedirect) { elyRedirect = it }
                Field("Сервер CW (https)", elyBackend) { elyBackend = it }
                Text("Войдите через Ely.by. Пароль вводится только на сайте, лаунчер его не видит.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    MiniPill("Отмена") { form = null }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        engine.saveSettings {
                            elyClientId = elyId.trim()
                            elyRedirectUri = elyRedirect.trim()
                            elyBackendUrl = elyBackend.trim()
                        }
                        form = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = BlueDeep, contentColor = Color.White)) { Text("Сохранить") }
                }
            }
            "ely-name" -> FormBox("Изменить имя", { form = null }) {
                Text("Имя аккаунта Ely.by задаётся на сайте Ely.by.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniPill("Закрыть") { form = null }
                }
            }
            "need" -> FormBox("Профиль", { form = null }) {
                Text("Сначала создайте профиль. Без аккаунта установка и запуск недоступны.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiniPill("Закрыть") { form = null }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    Glass {
        Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionLine(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF1E5AA8), RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(hint, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Blue, fontSize = 18.sp)
    }
}

@Composable
private fun MiniPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF243656)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, color = TextMain, fontSize = 12.sp) }
}

@Composable
private fun FormBox(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA06101C)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.55f).clip(CardShape).background(Panel2).border(1.dp, Line, CardShape).clickable { }.padding(12.dp)
        ) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Face(account: Account, size: androidx.compose.ui.unit.Dp, tick: Int) {
    val face = remember(account.id, account.uuid, tick) { skinHead(account.id) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFF10233F)).border(1.dp, Color(0xFF4C8DFF), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (face != null) {
            Image(face, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        } else {
            Glyph("user", Color(0xFFD6E2F5), Modifier.size(size / 2))
        }
    }
}

private fun skinHead(accountId: String): androidx.compose.ui.graphics.ImageBitmap? {
    val dir = File(AppPaths.skins, accountId)
    val file = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.maxByOrNull { it.lastModified() } ?: return null
    val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val scale = src.width / 64
    if (scale < 1 || src.width != scale * 64 || src.height < scale * 32) return src.asImageBitmap()
    val cell = 8 * scale
    val face = Bitmap.createBitmap(src, cell, cell, cell, cell).copy(Bitmap.Config.ARGB_8888, true)
    if (src.height >= scale * 64) {
        val hat = Bitmap.createBitmap(src, 40 * scale, cell, cell, cell)
        Canvas(face).drawBitmap(hat, 0f, 0f, null)
    }
    return face.asImageBitmap()
}

private fun shortAccountId(account: Account): String {
    val hex = account.shortUuid().uppercase()
    return "#" + hex.take(6)
}

private fun formatCreated(millis: Long): String {
    if (millis <= 0L) return "—"
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}

@Composable
private fun VersionPopup(state: UiState, engine: Engine) {
    val ids = remember(state.releases) {
        val list = state.releases.toMutableList()
        if (!list.contains("1.20.1")) list.add(0, "1.20.1")
        list
    }
    Box(Modifier.fillMaxSize().background(Color(0x8806101C)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF0101C33))
                .border(1.dp, Color(0xFF3D7AD4), RoundedCornerShape(12.dp))
                .clickable { }
                .padding(10.dp)
        ) {
            Text(
                if (state.releases.isEmpty()) "Загрузка списка версий…"
                else "Сервер Common World ставит 1.20.1 Fabric и моды. Остальные строки — обычный Minecraft.",
                color = Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionRow(
                    title = "Сервер Common World",
                    installed = state.installedVersions.contains("1.20.1"),
                    fabric = null,
                    onFabric = {},
                    onClick = { engine.selectCommonWorld() }
                )
                ids.forEach { id ->
                    val fabric = fabricChecked(id, state, engine)
                    VersionRow(
                        title = "Minecraft $id",
                        installed = state.installedVersions.contains(id),
                        fabric = fabric,
                        onFabric = { engine.toggleFabric(id) },
                        onClick = { engine.selectRelease(id, fabric) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { engine.open(Screen.HOME) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243656), contentColor = TextMain)
                ) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun VersionRow(title: String, installed: Boolean, fabric: Boolean?, onFabric: () -> Unit, onClick: () -> Unit) {
    val fill = if (installed) Color(0xFF2F6FE0) else Color(0xFF173056)
    val line = if (installed) Color(0xFF8EB4FF) else Color(0xFF2A4E78)
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp)).background(fill).border(1.dp, line, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (fabric != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { onFabric() }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(16.dp).border(1.dp, if (fabric) Green else Color(0xFF8EA6CC), RoundedCornerShape(3.dp)).background(Color(0xFF0B1C36)),
                    contentAlignment = Alignment.Center
                ) {
                    if (fabric) Text("✓", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                Text("Fabric", color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (installed) "установлена" else "не установлена", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun FilesPage(title: String, state: UiState, engine: Engine) {
    Page(title, engine) {
        Text(state.browserPath, color = Muted, fontSize = 11.sp)
        if (state.files.isEmpty()) Text("Папка пуста", color = TextMain)
        state.files.forEach { name -> Text(name, color = TextMain, fontSize = 14.sp) }
    }
}

@Composable
private fun SkinPage(engine: Engine) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return@rememberLauncherForActivityResult
        engine.importSkin(bytes)
    }
    Page("Скин", engine) {
        Text("PNG скина сохраняется в папку аккаунта. В игру он попадает вместе с Ely.by, если вход выполнен.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        GhostButton("Выбрать картинку") { pick.launch("image/*") }
    }
}

@Composable
private fun LogPage(state: UiState, engine: Engine) {
    Page("Логи", engine) {
        GhostButton("Обновить") { engine.reloadLog() }
        Spacer(Modifier.height(8.dp))
        Text(state.logText.ifBlank { "Журнал пуст" }, color = TextMain, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Overlay(title: String, subtitle: String, engine: Engine, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xD2122A4E)).border(1.dp, Color(0xFF4C8DFF), RoundedCornerShape(12.dp)).clickable { engine.open(Screen.HOME) }, contentAlignment = Alignment.Center) {
                Text("×", color = TextMain, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun Page(title: String, engine: Engine, content: @Composable () -> Unit) {
    Overlay("CWLauncher", title, engine, content)
}

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CardShape).background(Panel).border(1.dp, Line, CardShape).padding(10.dp),
        content = content
    )
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    value: Boolean?,
    onToggle: ((Boolean) -> Unit)?,
    stretch: Boolean = false,
    extra: @Composable (() -> Unit)? = null
) {
    Glass(Modifier.fillMaxWidth().then(if (stretch) Modifier.fillMaxHeight() else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            if (value != null && onToggle != null) {
                Switch(value, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Blue, uncheckedTrackColor = Color(0xFF243044)))
            }
        }
        if (extra != null) {
            Spacer(Modifier.height(6.dp))
            extra()
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String, badIsRed: Boolean = false) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✕", color = if (ok) Green else if (badIsRed) Red else Amber, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconButton(kind: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC15243C)).border(1.dp, Color(0x553B82F6), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Glyph(kind, Color.White, Modifier.size(16.dp)) }
}

@Composable
private fun ActionTile(kind: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC15243C)).border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Glyph(kind, TextMain, Modifier.size(16.dp))
        Text(label, color = TextMain, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun GhostButton(text: String, icon: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(36.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1A2C4A), contentColor = TextMain)
    ) {
        if (icon != null) {
            Glyph(icon, TextMain, Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (label == "Ширина" || label == "Высота" || label.startsWith("Лимит")) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedBorderColor = Blue,
            unfocusedBorderColor = Line,
            focusedLabelColor = Muted,
            unfocusedLabelColor = Muted,
            cursorColor = TextMain,
            focusedContainerColor = Field,
            unfocusedContainerColor = Field
        )
    )
}

@Composable
private fun Notice(text: String, confirm: String, dismiss: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA06101C)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .clip(CardShape)
                .background(Panel2)
                .border(1.dp, Line, CardShape)
                .padding(16.dp)
                .clickable { }
        ) {
            Text(text, color = TextMain, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (dismiss != null) {
                    TextButton(onClick = onDismiss) { Text(dismiss, color = Muted) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onConfirm) { Text(confirm, color = Blue) }
            }
        }
    }
}

@Composable
private fun Glyph(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (kind) {
            "play" -> drawPath(Path().apply {
                moveTo(w * 0.28f, h * 0.16f)
                lineTo(w * 0.82f, h * 0.5f)
                lineTo(w * 0.28f, h * 0.84f)
                close()
            }, tint)
            "chevron" -> drawPath(Path().apply {
                moveTo(w * 0.32f, h * 0.22f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.32f, h * 0.78f)
            }, tint, style = stroke)
            "send" -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.48f)
                lineTo(w * 0.88f, h * 0.16f)
                lineTo(w * 0.62f, h * 0.88f)
                lineTo(w * 0.48f, h * 0.54f)
                close()
            }, tint)
            "site" -> drawCircle(tint, radius = w * 0.28f, style = stroke)
            "chat" -> drawCircle(tint, radius = w * 0.12f, center = p(0.35f, 0.45f))
            "user" -> {
                drawCircle(tint, radius = w * 0.16f, center = p(0.5f, 0.34f))
                drawPath(Path().apply {
                    moveTo(w * 0.22f, h * 0.82f)
                    quadraticTo(w * 0.5f, h * 0.52f, w * 0.78f, h * 0.82f)
                }, tint, style = stroke)
            }
            "folder" -> drawPath(Path().apply {
                moveTo(w * 0.12f, h * 0.32f)
                lineTo(w * 0.38f, h * 0.32f)
                lineTo(w * 0.48f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.78f)
                lineTo(w * 0.12f, h * 0.78f)
                close()
            }, tint, style = stroke)
            "box" -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.32f)
                lineTo(w * 0.88f, h * 0.7f)
                lineTo(w * 0.5f, h * 0.9f)
                lineTo(w * 0.12f, h * 0.7f)
                lineTo(w * 0.12f, h * 0.32f)
                close()
            }, tint, style = stroke)
            "skin" -> {
                drawRoundRect(tint, topLeft = p(0.28f, 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f), style = stroke)
                drawPath(Path().apply {
                    moveTo(w * 0.18f, h * 0.9f)
                    lineTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.72f, h * 0.52f)
                    lineTo(w * 0.82f, h * 0.9f)
                }, tint, style = stroke)
            }
            "info" -> {
                drawCircle(tint, radius = w * 0.38f, style = stroke)
                drawLine(tint, p(0.5f, 0.42f), p(0.5f, 0.72f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.06f, center = p(0.5f, 0.28f))
            }
            "news" -> drawPath(Path().apply {
                moveTo(w * 0.2f, h * 0.16f)
                lineTo(w * 0.8f, h * 0.16f)
                lineTo(w * 0.8f, h * 0.84f)
                lineTo(w * 0.2f, h * 0.84f)
                close()
            }, tint, style = stroke)
            "gear" -> drawCircle(tint, radius = w * 0.22f, style = stroke)
            "mountain" -> drawPath(Path().apply {
                moveTo(w * 0.08f, h * 0.8f)
                lineTo(w * 0.38f, h * 0.22f)
                lineTo(w * 0.58f, h * 0.55f)
                lineTo(w * 0.72f, h * 0.36f)
                lineTo(w * 0.92f, h * 0.8f)
                close()
            }, tint)
            "cube" -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.1f)
                lineTo(w * 0.9f, h * 0.32f)
                lineTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.1f, h * 0.32f)
                close()
                moveTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.5f, h * 0.9f)
                lineTo(w * 0.1f, h * 0.68f)
                lineTo(w * 0.1f, h * 0.32f)
                moveTo(w * 0.5f, h * 0.54f)
                lineTo(w * 0.9f, h * 0.32f)
                lineTo(w * 0.9f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.9f)
            }, tint, style = stroke)
            "copy" -> drawPath(Path().apply {
                moveTo(w * 0.32f, h * 0.22f)
                lineTo(w * 0.78f, h * 0.22f)
                lineTo(w * 0.78f, h * 0.7f)
                lineTo(w * 0.32f, h * 0.7f)
                close()
                moveTo(w * 0.22f, h * 0.34f)
                lineTo(w * 0.22f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.82f)
            }, tint, style = stroke)
        }
    }
}

@Composable
private fun rememberClock(): String {
    var value by remember { mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    LaunchedEffect(Unit) {
        while (true) {
            value = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            delay(20_000)
        }
    }
    return value
}

@Composable
private fun rememberFps(): Int {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var window = System.currentTimeMillis()
        while (true) {
            withFrameNanos { }
            frames++
            val now = System.currentTimeMillis()
            if (now - window >= 1000) {
                fps = frames
                frames = 0
                window = now
            }
        }
    }
    return fps
}

private fun fabricChecked(id: String, state: UiState, engine: Engine): Boolean {
    if (engine.cfg.fabricVersions.containsKey(id)) return state.fabricOn.contains(id)
    return id == "1.20.1" && engine.cfg.commonWorld
}

private val languageOptions = listOf(
    "" to "",
    "ru_ru" to "Русский",
    "en_us" to "English",
    "uk_ua" to "Українська",
    "be_by" to "Беларуская",
    "kk_kz" to "Қазақша",
    "de_de" to "Deutsch",
    "fr_fr" to "Français",
    "es_es" to "Español",
    "es_mx" to "Español (México)",
    "pt_br" to "Português (Brasil)",
    "pt_pt" to "Português",
    "pl_pl" to "Polski",
    "it_it" to "Italiano",
    "tr_tr" to "Türkçe",
    "nl_nl" to "Nederlands",
    "sv_se" to "Svenska",
    "fi_fi" to "Suomi",
    "cs_cz" to "Čeština",
    "sk_sk" to "Slovenčina",
    "hu_hu" to "Magyar",
    "ro_ro" to "Română",
    "bg_bg" to "Български",
    "da_dk" to "Dansk",
    "nb_no" to "Norsk",
    "el_gr" to "Ελληνικά",
    "ja_jp" to "日本語",
    "ko_kr" to "한국어",
    "zh_cn" to "简体中文",
    "zh_tw" to "繁體中文",
    "id_id" to "Bahasa Indonesia",
    "ar_sa" to "العربية"
)

private fun languageName(code: String): String {
    if (code.isBlank()) return Lang.t("lang_sys")
    return languageOptions.firstOrNull { it.first == code }?.second ?: Lang.t("lang_sys")
}

private fun List<Pair<String, String>>.labelOf(key: String): String = firstOrNull { it.first == key }?.second ?: first().second

@Composable
private fun Split(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Choice(value: String, options: List<String>, onPick: (Int) -> Unit) {
    val index = options.indexOf(value).let { if (it < 0) 0 else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { onPick(if (index == 0) options.lastIndex else index - 1) }.padding(6.dp))
        Text(value, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("›", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { onPick(if (index == options.lastIndex) 0 else index + 1) }.padding(6.dp))
    }
}

@Composable
private fun BackgroundRow(name: String, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) BlueDeep else Color(0x66101A2C))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onClick)
        )
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            Text(Lang.t("del_bg"), color = Color(0xFFFF8D8D), fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDelete))
        }
    }
}

@Composable
private fun MinecraftMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun face(block: Path.() -> Unit) = Path().apply(block)
        drawPath(face {
            moveTo(w * 0.08f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.92f)
            lineTo(w * 0.08f, h * 0.70f)
            close()
        }, Color(0xFF6B4423))
        drawPath(face {
            moveTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.92f)
            lineTo(w * 0.92f, h * 0.70f)
            close()
        }, Color(0xFF8D5A32))
        drawPath(face {
            moveTo(w * 0.08f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.42f)
            close()
        }, Color(0xFF3C8A28))
        drawPath(face {
            moveTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.5f, h * 0.64f)
            lineTo(w * 0.92f, h * 0.42f)
            close()
        }, Color(0xFF4E9A34))
        drawPath(face {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.92f, h * 0.30f)
            lineTo(w * 0.5f, h * 0.52f)
            lineTo(w * 0.08f, h * 0.30f)
            close()
        }, Color(0xFF5CB336))
        drawPath(face {
            moveTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.46f, h * 0.14f)
            lineTo(w * 0.54f, h * 0.22f)
            lineTo(w * 0.38f, h * 0.30f)
            close()
        }, Color(0xFF3E8C28))
        drawPath(face {
            moveTo(w * 0.58f, h * 0.28f)
            lineTo(w * 0.74f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.26f)
            lineTo(w * 0.64f, h * 0.34f)
            close()
        }, Color(0xFF2F7420))
    }
}

@Composable
private fun ScrollPane(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    var viewH by remember { mutableIntStateOf(1) }
    Row(modifier) {
        Column(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewH = it.height }.verticalScroll(scroll).padding(8.dp)
        ) { content() }
        DragScroll(scroll, viewH, Modifier.fillMaxHeight().width(16.dp).padding(vertical = 4.dp, horizontal = 3.dp))
    }
}

@Composable
private fun DragScroll(scroll: androidx.compose.foundation.ScrollState, viewH: Int, modifier: Modifier) {
    val max = scroll.maxValue
    val contentH = (viewH + max).coerceAtLeast(1)
    val thumbFrac = (viewH.toFloat() / contentH).coerceIn(0.18f, 1f)
    Box(
        modifier.pointerInput(max, viewH) {
            detectDragGestures { change, drag ->
                change.consume()
                if (max <= 0) return@detectDragGestures
                val travel = (size.height * (1f - thumbFrac)).coerceAtLeast(1f)
                scroll.dispatchRawDelta(drag.y / travel * max)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val track = size.height
            val thumbH = (track * thumbFrac).coerceAtLeast(22.dp.toPx()).coerceAtMost(track)
            val travel = (track - thumbH).coerceAtLeast(0f)
            val y = if (max <= 0) 0f else travel * (scroll.value.toFloat() / max.toFloat())
            drawRoundRect(Color(0x332F6FE0), cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()))
            drawRoundRect(
                Color(0xFF4C8DFF),
                topLeft = Offset(0f, y),
                size = Size(size.width, thumbH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
        }
    }
}

private fun backgroundDir(): File {
    val dir = File(AppPaths.root, "backgrounds")
    dir.mkdirs()
    return dir
}

private fun backgroundFiles(): List<File> {
    val dir = backgroundDir()
    val saved = dir.listFiles()?.filter { it.isFile && isPicture(it) }?.sortedBy { it.name.lowercase() }.orEmpty()
    val legacy = File(AppPaths.root, "background.jpg")
    return if (legacy.isFile && saved.none { it.absolutePath == legacy.absolutePath }) listOf(legacy) + saved else saved
}

private fun isPicture(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp"
}

private fun copyBackground(context: Context, uri: android.net.Uri): File? {
    val dir = backgroundDir()
    var n = 1
    var dest = File(dir, "bg-$n.jpg")
    while (dest.exists()) {
        n++
        dest = File(dir, "bg-$n.jpg")
    }
    return try {
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        if (!dest.isFile || dest.length() < 32) {
            dest.delete()
            null
        } else dest
    } catch (_: Exception) {
        dest.delete()
        null
    }
}

private fun loadBackground(path: String): BitmapPainter? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (sample < 16 && (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080)) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return BitmapPainter(bmp.asImageBitmap())
}

private fun folderBytes(dir: File): Long {
    if (!dir.exists()) return 0
    var total = 0L
    dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
    return total
}

private fun human(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f МБ", mb)
    return String.format(java.util.Locale.US, "%.2f ГБ", mb / 1024.0)
}

private fun readyCaption(state: UiState): String = when (state.phase) {
    Phase.READY -> Lang.t("ready")
    Phase.OFFLINE -> Lang.t("offline_go")
    Phase.NOT_INSTALLED -> state.detail
    Phase.UPDATE_AVAILABLE -> state.detail
    Phase.ERROR -> state.detail
    Phase.RUNNING -> Lang.t("running")
    Phase.STARTING -> Lang.t("starting")
    else -> state.detail.ifBlank { Lang.t("checking") }
}

private fun buttonLabel(phase: Phase, busy: Boolean, detail: String = ""): String = when {
    busy -> Lang.t("cancel")
    phase == Phase.READY || phase == Phase.OFFLINE -> Lang.t("launch")
    phase == Phase.NOT_INSTALLED -> Lang.t("install")
    phase == Phase.UPDATE_AVAILABLE -> Lang.t("update_btn")
    phase == Phase.ERROR && (detail.startsWith("Не удалось") || detail.startsWith("Игра закрылась")) -> Lang.t("retry")
    phase == Phase.ERROR -> Lang.t("check")
    phase == Phase.CHECKING -> Lang.t("checking")
    phase == Phase.STARTING -> Lang.t("starting")
    phase == Phase.RUNNING -> Lang.t("running")
    else -> Lang.t("go")
}

@Composable
private fun TransferBar(state: UiState) {
    val total = state.xferTotal.coerceAtLeast(1L)
    val done = state.xferDone.coerceIn(0L, total)
    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
    val left = (state.xferTotal - state.xferDone).coerceAtLeast(0)
    Text(state.xferTitle.ifBlank { state.progressText }, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(3.dp))
    LinearProgressIndicator(
        progress = { done.toFloat() / total.toFloat() },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)),
        color = Blue,
        trackColor = Field,
        drawStopIndicator = {}
    )
    Text("$pct%", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text("${formatBytes(done)} / ${formatBytes(state.xferTotal)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    Text("Осталось: ${formatBytes(left)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    Text(
        if (state.xferSpeed > 0L) "Скорость: ${formatSpeed(state.xferSpeed)}" else "Определение скорости...",
        color = Muted,
        fontSize = 11.sp,
        maxLines = 1
    )
    if (state.xferSpeed > 0L) {
        Text("Примерное время: ${formatEta(left / state.xferSpeed)}", color = Muted, fontSize = 11.sp, maxLines = 1)
    }
    if (state.xferExtra.isNotBlank()) {
        Text(state.xferExtra, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun buttonColor(phase: Phase, busy: Boolean): Color = when {
    busy -> Color(0xFF3D4C63)
    phase == Phase.ERROR -> Color(0xFFE0524D)
    else -> BlueDeep
}

private fun statusTone(phase: Phase): Color = when (phase) {
    Phase.READY -> Green
    Phase.ERROR -> Red
    Phase.UPDATE_AVAILABLE, Phase.OFFLINE -> Amber
    else -> Muted
}
