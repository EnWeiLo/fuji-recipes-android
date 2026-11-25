package com.enweilo.fujishokudo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

enum class Screen(val label: String, val icon: Ico) {
    Recipes("食譜", Ico.Recipes),
    Connect("連線與同步", Ico.Link),
    Bake("烘焙", Ico.Oven),
    Polaroid("拍立得", Ico.Polaroid),
    Settings("設定", Ico.Gear),
}

/** Everything the window shows. */
class AppState(val scope: CoroutineScope) {
    val settings = Settings()
    val repo = RecipeRepo()
    val camera = CameraManager(scope)
    var toast by mutableStateOf<String?>(null)
        private set
    val bake = BakeModel(scope, camera, repo, settings) { showToast(it) }
    val sync = SyncServer(scope, repo, settings)
    val polaroid = PolaroidModel(scope, settings) { showToast(it) }
    var screen by mutableStateOf(Screen.Bake)
    var holding by mutableStateOf(false)
    var selectedRecipe by mutableStateOf<String?>(repo.recipes.firstOrNull()?.id)
    var update by mutableStateOf<Int?>(null)
        private set
    private var toastSeq = 0

    fun showToast(t: String) {
        val n = ++toastSeq
        toast = t
        scope.launch {
            delay(2600)
            if (toastSeq == n) toast = null
        }
    }

    fun start() {
        camera.start()
        sync.start()
        if (bake.recipeId == null) repo.recipes.firstOrNull()?.let { bake.chooseRecipe(it.id) }
        scope.launch {
            delay(4000)
            val latest = withContext(Dispatchers.IO) { Update.latest() }
            if (latest != null && Update.build in 1 until latest) update = latest
        }
    }

    fun openUrl(u: String) = runCatching { Desktop.getDesktop().browse(URI(u)) }
    fun openFolder(f: File) = runCatching { f.mkdirs(); Desktop.getDesktop().open(f) }
}

object Pick {
    fun folder(start: File?): File? {
        val fc = JFileChooser(start)
        fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        fc.dialogTitle = "選擇放 RAF 的資料夾"
        return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }

    fun file(title: String, start: File?, vararg ext: String): File? {
        val fc = JFileChooser(start)
        fc.dialogTitle = title
        if (ext.isNotEmpty()) fc.fileFilter = FileNameExtensionFilter(ext.joinToString(", ") { it.uppercase() }, *ext)
        return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }

    fun save(title: String, name: String, ext: String): File? {
        val fc = JFileChooser()
        fc.dialogTitle = title
        fc.selectedFile = File(name)
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val f = fc.selectedFile
        return if (f.name.lowercase().endsWith(".$ext")) f else File(f.parentFile, f.name + ".$ext")
    }
}

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        val state = rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition(Alignment.Center))
        val scope = rememberCoroutineScope()
        val app = remember { AppState(scope) }
        LaunchedEffect(Unit) { app.start() }
        DisposableEffect(Unit) { onDispose { app.sync.stop() } }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "富士食堂",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e -> handleKey(app, e) },
        ) {
            window.minimumSize = java.awt.Dimension(1100, 700)
            MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, surface = C.card2, background = C.bg)) {
                AppView(app)
            }
        }
    }
}

private fun handleKey(app: AppState, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (app.screen != Screen.Bake) return false
    if (e.key == Key.Spacebar) {
        app.holding = e.type == KeyEventType.KeyDown
        return true
    }
    if (e.type != KeyEventType.KeyDown) return false
    when (e.key) {
        Key.DirectionRight -> app.bake.next()
        Key.DirectionLeft -> app.bake.prev()
        Key.Enter -> app.bake.bake()
        Key.One -> app.bake.chooseRecipeAt(0)
        Key.Two -> app.bake.chooseRecipeAt(1)
        Key.Three -> app.bake.chooseRecipeAt(2)
        Key.Four -> app.bake.chooseRecipeAt(3)
        Key.Five -> app.bake.chooseRecipeAt(4)
        Key.Six -> app.bake.chooseRecipeAt(5)
        Key.Seven -> app.bake.chooseRecipeAt(6)
        Key.Eight -> app.bake.chooseRecipeAt(7)
        Key.Nine -> app.bake.chooseRecipeAt(8)
        else -> return false
    }
    return true
}

@Composable
fun AppView(app: AppState) {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxSize()) {
            SideBar(app)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (app.screen) {
                    Screen.Bake -> BakeScreen(app)
                    Screen.Recipes -> RecipesScreen(app)
                    Screen.Connect -> ConnectScreen(app)
                    Screen.Polaroid -> PolaroidScreen(app)
                    Screen.Settings -> SettingsScreen(app)
                }
            }
        }
        AnimatedVisibility(app.toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color2.toast).border(1.dp, C.line, RoundedCornerShape(999.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Txt(app.toast ?: "", 14)
            }
        }
    }
}

private object Color2 {
    val toast = androidx.compose.ui.graphics.Color(0xF02A2622)
}

@Composable
private fun SideBar(app: AppState) {
    Column(
        Modifier.width(216.dp).fillMaxHeight().background(C.side).padding(horizontal = 14.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("icon.png"), null, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Txt("富士食堂", 17, FontWeight.Bold)
        }
        for (s in Screen.entries) {
            if (s == Screen.Settings) Spacer(Modifier.weight(1f))
            val on = app.screen == s
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .press { app.screen = s }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconView(s.icon, if (on) C.accentText else C.sub, 18.dp)
                Txt(s.label, 14, if (on) FontWeight.Medium else FontWeight.Normal, if (on) C.accentText else C.sub)
                if (s == Screen.Connect) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(
                        when (app.camera.state) {
                            CamState.Connected -> C.ok
                            CamState.NeedsDriver, CamState.Busy, CamState.NoLibrary -> C.warn
                            CamState.Searching -> C.line
                        },
                    ))
                }
            }
        }
        if (app.update != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.accentSoft).press { app.openUrl(Update.PAGE) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Txt("有新版本（${app.update}）", 13, FontWeight.Medium, C.accentText)
                Txt("按這裡下載", 12, color = C.sub)
            }
        }
    }
}
