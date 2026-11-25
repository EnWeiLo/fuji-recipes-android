package com.enweilo.fujishokudo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/** Draws each screen to a PNG without a window (for checking the layout during development). */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "preview" }).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val app = runBlocking(Dispatchers.Main) { AppState(scope) }
    args.getOrNull(1)?.let { dir -> runBlocking(Dispatchers.Main) { app.bake.openFolder(File(dir)) } }
    Thread.sleep(2500)
    for (s in Screen.entries) {
        runBlocking(Dispatchers.Main) { app.screen = s }
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, surface = C.card2, background = C.bg)) { AppView(app) }
        }
        scene.render(0)
        Thread.sleep(600)
        scene.render(700_000_000)
        val img = scene.render(1_400_000_000)
        File(out, "${s.name}.png").writeBytes(img.encodeToData()!!.bytes)
        scene.close()
    }
    System.exit(0)
}
