package com.enweilo.fujirecipes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.shoot.FlashMode
import com.enweilo.fujirecipes.shoot.Look
import com.enweilo.fujirecipes.shoot.LookPreview
import com.enweilo.fujirecipes.shoot.LutStore
import com.enweilo.fujirecipes.shoot.Ratio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Ink = Color(0xFF0A0A0B)
private val OnInk = Color(0xFFF4F1EC)
private val Dim = Color(0x99F4F1EC)

/** The 拍照 tab: a viewfinder that already shows the chosen recipe. */
@Composable
fun ShootTab(c: AppController, active: Boolean) {
    val s = c.shoot
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(active) { if (active) s.refreshLuts() }

    // Hold the screen upright while shooting; the photo still comes out landscape when the phone is turned.
    val activity = context as? ComponentActivity
    DisposableEffect(active) {
        if (active) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                s.targetRotation = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (active) listener.enable()
        onDispose {
            listener.disable()
            if (active) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        TopControls(c)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!granted) {
                PermissionAsk { askCamera.launch(Manifest.permission.CAMERA) }
            } else {
                Viewfinder(c, active)
            }
        }
        RecipeStrip(c)
        BottomControls(c)
        // room for the tab bar
        Spacer(Modifier.navigationBarsPadding().height(94.dp))
    }
}

// ---------------- top row ----------------

@Composable
private fun TopControls(c: AppController) {
    val s = c.shoot
    Row(
        Modifier.fillMaxWidth().padding(top = topInset() + 6.dp, start = 12.dp, end = 12.dp, bottom = 6.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TopButton(if (s.flash == FlashMode.Off) Glyph.FlashOff else Glyph.Flash, s.flash.label, s.flash != FlashMode.Off) { s.nextFlash() }
        TopButton(Glyph.Timer, if (s.timer == 0) "關" else "${s.timer}s", s.timer > 0) { s.nextTimer() }
        Box(
            Modifier.height(34.dp).clip(CircleShape).border(1.2.dp, Dim, CircleShape).tap { s.nextRatio() }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(s.ratio.label, 14, FontWeight.SemiBold, OnInk) }
        TopButton(Glyph.Grid, "格線", s.grid) { s.toggleGrid() }
        TopButton(Glyph.Level, "水平", s.level) { s.toggleLevel() }
    }
}

@Composable
private fun TopButton(glyph: Glyph, label: String, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) Pal.accent else OnInk
    Column(
        Modifier.width(52.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphView(glyph, tint, 20.dp)
        Txt(label, 10, FontWeight.Medium, if (on) tint else Dim, Modifier.padding(top = 1.dp), maxLines = 1)
    }
}

@Composable
private fun PermissionAsk(onAsk: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        GlyphView(Glyph.Camera, Dim, 44.dp)
        Txt("需要相機權限才能拍照", 17, FontWeight.SemiBold, OnInk, Modifier.padding(top = 14.dp))
        Txt("照片只會存在這支手機的相簿", 13, color = Dim, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.padding(top = 18.dp).height(44.dp).clip(CircleShape).background(Pal.accent).tap(onClick = onAsk)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) { Txt("允許使用相機", 16, FontWeight.SemiBold, Color.White) }
    }
}

// ---------------- viewfinder ----------------

@Composable
private fun Viewfinder(c: AppController, active: Boolean) {
    val s = c.shoot
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val recipe = c.recipes.firstOrNull { it.id == s.recipeId }
    val look = remember(recipe) { recipe?.let { Look.of(it) } ?: Look.NONE }
    var preview by remember { mutableStateOf<LookPreview?>(null) }
    var started by remember { mutableStateOf(false) }
    var holdOriginal by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusStamp by remember { mutableLongStateOf(0L) }
    var evShownAt by remember { mutableLongStateOf(0L) }

    // connect the camera only while this tab is on screen
    DisposableEffect(active, preview, owner) {
        val v = preview
        if (active && v != null && owner != null) {
            started = false
            s.bind(owner, v)
        }
        onDispose { if (active) s.unbind() }
    }
    LaunchedEffect(look, preview) { preview?.look = look }
    LaunchedEffect(holdOriginal, preview) { preview?.original = holdOriginal }
    LaunchedEffect(s.ratio, preview) { preview?.ratio = s.ratio }
    LaunchedEffect(s.lutVersion, preview) { preview?.reloadLut() }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = s.ratio.short / s.ratio.long // width / height, upright
        val w = if (maxWidth / maxHeight < aspect) maxWidth else maxHeight * aspect
        val h = w / aspect
        Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp))) {
            AndroidView(
                factory = { ctx ->
                    LookPreview(ctx, s.luts).also {
                        it.onFirstFrame = { started = true }
                        preview = it
                    }
                },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
            if (!started) Box(Modifier.fillMaxSize().background(Ink))

            val density = LocalDensity.current
            val wPx = with(density) { w.toPx() }
            val hPx = with(density) { h.toPx() }
            val evRange = rememberUpdatedState(s.evMax - s.evMin)

            // gestures: tap = focus, drag up/down = exposure, pinch = zoom; sideways is left for the tab pager
            Box(
                Modifier.fillMaxSize().pointerInput(wPx, hPx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var mode = 0 // 0 undecided, 1 exposure, 2 zoom, 3 sideways
                        val startEv = s.ev
                        val startZoom = s.zoom
                        var zoomAcc = 1f
                        while (true) {
                            val e = awaitPointerEvent()
                            val pressed = e.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                mode = 2
                                zoomAcc *= e.calculateZoom()
                                s.changeZoom(startZoom * zoomAcc)
                                e.changes.forEach { it.consume() }
                                continue
                            }
                            val d = pressed[0].position - start
                            if (mode == 0) {
                                if (abs(d.y) > slop && abs(d.y) > abs(d.x)) mode = 1
                                else if (abs(d.x) > slop) mode = 3
                            }
                            if (mode == 1 && evRange.value > 0f) {
                                s.changeEv(startEv - d.y / hPx * 4f)
                                evShownAt = System.currentTimeMillis()
                                pressed[0].consume()
                            }
                        }
                        if (mode == 0) {
                            focusAt = start
                            focusStamp = System.currentTimeMillis()
                            preview?.let { v ->
                                val (fx, fy) = v.toFrame(start.x / wPx, start.y / hPx)
                                s.focusAt(v.display, fx, fy)
                            }
                        }
                    }
                },
            )

            if (s.grid) GridLines()
            if (s.level) LevelLine(active)

            // focus ring
            val fp = focusAt
            if (fp != null) {
                LaunchedEffect(focusStamp) { delay(2500); if (System.currentTimeMillis() - focusStamp >= 2400) focusAt = null }
                val k by animateFloatAsState(1f, tween(220), label = "ring")
                val ringPx = with(density) { 70.dp.toPx() }
                Canvas(
                    Modifier.offset { IntOffset((fp.x - ringPx / 2).roundToInt(), (fp.y - ringPx / 2).roundToInt()) }.size(70.dp),
                ) {
                    drawRect(Color(0xFFFFD54A), style = Stroke(1.5.dp.toPx()), alpha = k)
                }
            }

            // exposure readout while dragging
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(evShownAt) { now = evShownAt; delay(1300); now = 0L }
            AnimatedVisibility(now != 0L, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Txt(evLabel(s.ev), 14, FontWeight.SemiBold, Color(0xFFFFD54A))
                }
            }

            // what is being applied
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (recipe != null) {
                    val missing = look.lutKey != null && look.lutKey !in s.installed
                    Chip(
                        when {
                            holdOriginal -> "原圖"
                            missing -> "未下載 LUT"
                            look.approx -> "近似 · ${recipe.filmSim}"
                            else -> recipe.filmSim
                        },
                    )
                }
                if (s.ev != 0f) Chip(evLabel(s.ev))
            }

            // hold to compare with the plain picture
            if (recipe != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(if (holdOriginal) Pal.accent else Color(0x88000000))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                holdOriginal = true
                                while (true) {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { it.consume() }
                                    if (e.changes.none { it.pressed }) break
                                }
                                holdOriginal = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Txt("按住看原圖", 12, FontWeight.SemiBold, Color.White) }
            }

            ZoomChips(c, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))

            // countdown
            AnimatedVisibility(s.countdown > 0, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
                Txt("${s.countdown}", 96, FontWeight.Bold, Color(0xE6FFFFFF))
            }

            // shutter blink
            val blink = remember { Animatable(0f) }
            LaunchedEffect(s.shutterCount) {
                if (s.shutterCount > 0) { blink.snapTo(0.85f); blink.animateTo(0f, tween(260)) }
            }
            if (blink.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = blink.value }.background(Color.Black))

            LutBanner(c, look, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp, start = 12.dp, end = 12.dp))
        }
    }
}

private fun evLabel(ev: Float): String {
    val r = (ev * 10).roundToInt() / 10f
    return (if (r > 0) "+" else if (r < 0) "−" else "±") + "%.1f".format(abs(r)) + " EV"
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.clip(CircleShape).background(Color(0x88000000)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Txt(text, 12, FontWeight.Medium, Color.White, maxLines = 1)
    }
}

@Composable
private fun GridLines() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x66FFFFFF)
        val sw = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), sw)
            drawLine(c, Offset(0f, y), Offset(size.width, y), sw)
        }
    }
}

@Composable
private fun LevelLine(active: Boolean) {
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(active) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                roll = roll * 0.8f + a * 0.2f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (active && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    // deviation from the nearest upright / sideways position
    val nearest = (roll / 90f).roundToInt() * 90f
    val off = roll - nearest
    val ok = abs(off) < 1.2f
    val color = if (ok) Color(0xFFFFD54A) else Color(0xCCFFFFFF)
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val len = size.minDimension * 0.22f
        drawLine(color.copy(alpha = 0.5f), Offset(c.x - len * 1.5f, c.y), Offset(c.x - len * 1.1f, c.y), 1.5.dp.toPx())
        drawLine(color.copy(alpha = 0.5f), Offset(c.x + len * 1.1f, c.y), Offset(c.x + len * 1.5f, c.y), 1.5.dp.toPx())
        rotate(-off, c) {
            drawLine(color, Offset(c.x - len, c.y), Offset(c.x + len, c.y), (if (ok) 2.5.dp else 1.5.dp).toPx())
        }
    }
}

@Composable
private fun ZoomChips(c: AppController, modifier: Modifier) {
    val s = c.shoot
    if (!s.cameraReady) return
    val stops = listOfNotNull(
        s.zoomMin.takeIf { it < 0.95f }, 1f, 2f.takeIf { s.zoomMax >= 2f }, 5f.takeIf { s.zoomMax >= 5f },
    )
    if (stops.size < 2 && s.zoom == 1f) return
    val nearest = stops.minByOrNull { abs(it - s.zoom) }
    Row(
        modifier.clip(CircleShape).background(Color(0x66000000)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (z in stops) {
            val on = z == nearest
            val label = if (on && abs(s.zoom - z) > 0.05f) "%.1f×".format(s.zoom) else zoomLabel(z)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (on) Color(0x33FFFFFF) else Color.Transparent).tap { s.changeZoom(z) },
                contentAlignment = Alignment.Center,
            ) { Txt(label, if (on) 12 else 11, FontWeight.SemiBold, if (on) Color(0xFFFFD54A) else Color.White) }
        }
    }
}

private fun zoomLabel(z: Float): String = if (z < 1f) "%.1f".format(z).trimEnd('0').trimEnd('.') else "${z.roundToInt()}×"

@Composable
private fun LutBanner(c: AppController, look: Look, modifier: Modifier) {
    val s = c.shoot
    val need = s.installed.isEmpty() || (look.lutKey != null && look.lutKey !in s.installed && s.recipeId != null)
    if (!need && !s.lutBusy) return
    LaunchedEffect(Unit) { s.probeLutSize() }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xD9141416)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.lutBusy) {
            Txt(s.lutText.ifEmpty { "處理中…" }, 14, FontWeight.Medium, OnInk)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(s.lutProgress.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Pal.accent))
            }
        } else {
            val size = if (s.lutSize > 0) "約 ${s.lutSize / 1_000_000} MB" else "約 45 MB"
            Txt("需要富士官方底片模擬 LUT", 15, FontWeight.SemiBold, OnInk)
            Txt("從富士官網下載（$size，建議用 Wi-Fi），只要下載一次。", 12, color = Dim)
            if (s.lutText.isNotEmpty()) Txt(s.lutText, 12, color = Color(0xFFFF8A80))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.height(38.dp).clip(CircleShape).background(Pal.accent).tap { s.downloadLuts() }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("下載", 14, FontWeight.SemiBold, Color.White) }
                Box(
                    Modifier.height(38.dp).clip(CircleShape).border(1.dp, Dim, CircleShape).tap { c.tab = com.enweilo.fujirecipes.Tab.Settings }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("手動匯入", 14, FontWeight.Medium, OnInk) }
            }
        }
    }
}

// ---------------- recipe strip ----------------

@Composable
private fun RecipeStrip(c: AppController) {
    val s = c.shoot
    val items = listOf<com.enweilo.fujirecipes.data.Recipe?>(null) + c.recipes
    val selectedIndex = items.indexOfFirst { it?.id == s.recipeId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // the chip that stops in the middle becomes the recipe
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val centre = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) } ?: return@collect
            val r = items.getOrNull(centre.index)
            if (r?.id != s.recipeId) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                s.selectRecipe(r?.id)
            }
        }
    }
    // keep the selection centred when it changes elsewhere
    LaunchedEffect(selectedIndex) {
        if (!state.isScrollInProgress) state.animateScrollToItem(selectedIndex)
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp)) {
        val side = maxWidth / 2 - 60.dp
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side.coerceAtLeast(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, r -> r?.id ?: "none" }) { _, r ->
                val on = r?.id == s.recipeId
                val scale by animateFloatAsState(if (on) 1f else 0.92f, label = "chip")
                Row(
                    Modifier
                        .width(120.dp)
                        .height(42.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)
                        .background(if (on) Color(0x26FFFFFF) else Color(0x12FFFFFF))
                        .border(if (on) 1.5.dp else 0.dp, if (on) Pal.accent else Color.Transparent, CircleShape)
                        .tap { s.selectRecipe(r?.id) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (r == null) Color(0x66FFFFFF) else Tables.simColor(r.filmSim)),
                    )
                    Txt(
                        r?.name?.ifEmpty { "未命名" } ?: "不套用",
                        13, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) OnInk else Dim,
                        Modifier.padding(start = 8.dp), maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------- shutter row ----------------

@Composable
private fun BottomControls(c: AppController) {
    val s = c.shoot
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // last photo
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF))
                .tap(enabled = s.lastUri != null) {
                    val uri = s.lastUri ?: return@tap
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            s.lastThumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (s.processing > 0) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Spinner()
                }
            }
        }

        // shutter
        val pressScale by animateFloatAsState(if (s.capturing) 0.88f else 1f, label = "shutter")
        Box(
            Modifier
                .size(78.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .border(4.dp, OnInk, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(if (s.countdown > 0) Pal.accent else OnInk)
                .tap(enabled = s.cameraReady) { if (s.countdown > 0) s.cancelTimer() else s.shutter() },
        )

        // front / back
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0x22FFFFFF)).tap(enabled = s.hasFront) { s.switchCamera() },
            contentAlignment = Alignment.Center,
        ) { GlyphView(Glyph.Flip, OnInk, 24.dp) }
    }
}

@Composable
private fun Spinner() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "a",
    )
    Canvas(Modifier.size(22.dp)) {
        drawArc(Color.White, a, 270f, false, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** For the settings page: LUT status and manual import. */
@Composable
fun ShootSettings(c: AppController) {
    val s = c.shoot
    val p = Pal
    LaunchedEffect(Unit) { s.refreshLuts() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        s.importLuts(uris)
    }
    Panel {
        ToggleRow("同時保存原圖", s.saveOriginal) { s.changeSaveOriginal(it) }
        Divider()
        ToggleRow("音量鍵當快門", s.volumeShutter) { s.changeVolumeShutter(it) }
    }
    Spacer(Modifier.height(10.dp))
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueRow("富士官方 LUT", if (s.installed.isEmpty()) "尚未下載" else "已安裝 ${s.installed.size} 種")
            if (s.installed.isNotEmpty()) {
                Txt(LutStore.NAMES.filterKeys { it in s.installed }.values.joinToString("、"), 12, color = p.sub)
            }
            if (s.lutBusy) {
                Txt(s.lutText, 13, color = p.sub)
            } else {
                if (s.lutText.isNotEmpty()) Txt(s.lutText, 12, color = p.danger)
                SoftButton(if (s.installed.isEmpty()) "從富士官網下載" else "重新下載", { s.downloadLuts() })
                SoftButton("手動匯入（zip 或 .cube）", { picker.launch(arrayOf("*/*")) })
                if (s.installed.isNotEmpty()) SoftButton("刪除已下載的 LUT", { s.removeLuts() }, danger = true)
            }
            Txt(
                "拍照分頁用富士官方的 F-Log2 底片模擬 LUT。沒有官方 LUT 的模擬（懷舊負片、PRO Neg. Hi、黑白濾鏡、棕褐）會用近似效果。" +
                    "手動匯入時請選富士官網下載的 GFX ETERNA 55 LUT zip，或其中 F-Log2 資料夾的 .cube 檔。",
                12, color = p.sub,
            )
        }
    }
}
