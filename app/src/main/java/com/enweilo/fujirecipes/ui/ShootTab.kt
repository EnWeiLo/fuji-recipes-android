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
import android.os.Build
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.Tab
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.shoot.FlashMode
import com.enweilo.fujirecipes.shoot.Look
import com.enweilo.fujirecipes.shoot.LookPreview
import com.enweilo.fujirecipes.shoot.LutStore
import com.enweilo.fujirecipes.shoot.ShootController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink = Color(0xFF0A0A0B)
private val OnInk = Color(0xFFF4F1EC)
private val Dim = Color(0x99F4F1EC)
private val Focus = Color(0xFFFFD54A)
private val FocusOk = Color(0xFF5BE08A)

private val Bouncy = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

/** Turns its content upright for the way the phone is held, swapping width and height when sideways. */
private fun Modifier.turned(quarter: Int, angle: () -> Float): Modifier = this.layout { m, c ->
    val swap = quarter % 180 != 0
    val p = m.measure(if (swap) Constraints(c.minHeight, c.maxHeight, c.minWidth, c.maxWidth) else c)
    val w = if (swap) p.height else p.width
    val h = if (swap) p.width else p.height
    layout(w, h) { p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = angle() } }
}

/** Press-down shrink with a spring back, plus a tap. */
@Composable
private fun Modifier.press(enabled: Boolean = true, to: Float = 0.86f, onClick: () -> Unit): Modifier {
    var down by remember { mutableStateOf(false) }
    val k by animateFloatAsState(if (down) to else 1f, Bouncy, label = "press")
    val click by rememberUpdatedState(onClick)
    return this
        .graphicsLayer { scaleX = k; scaleY = k }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    down = true
                    tryAwaitRelease()
                    down = false
                },
                onTap = { click() },
            )
        }
}

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

    // The screen stays upright while shooting; labels turn instead, and the photo follows the phone.
    val activity = context as? ComponentActivity
    DisposableEffect(active) {
        if (active) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) s.onOrientation(o)
            }
        }
        if (active) listener.enable()
        onDispose {
            listener.disable()
            if (active) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val turn by animateFloatAsState(s.uiTurn.toFloat(), spring(dampingRatio = 0.7f, stiffness = 260f), label = "turn")
    val ui = Turn(s.uiTurn) { turn }

    var finder by remember { mutableStateOf(Rect.Zero) }
    var thumbBox by remember { mutableStateOf(Rect.Zero) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            TopControls(c, ui)
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { finder = it.boundsInRoot() },
                contentAlignment = Alignment.Center,
            ) {
                if (!granted) {
                    PermissionAsk { askCamera.launch(Manifest.permission.CAMERA) }
                } else {
                    Viewfinder(c, active, ui)
                }
                ReviewOverlay(s)
            }
            RecipeStrip(c)
            BottomControls(c, ui) { thumbBox = it }
            // room for the tab bar
            Spacer(Modifier.navigationBarsPadding().height(94.dp))
        }
        ThumbFlight(s, finder, thumbBox)
    }
}

/** Which way labels face: the target quarter turn and the animated angle. */
private class Turn(val quarter: Int, val angle: () -> Float)

// ---------------- top row ----------------

@Composable
private fun TopControls(c: AppController, ui: Turn) {
    val s = c.shoot
    Row(
        Modifier.fillMaxWidth().padding(top = topInset() + 6.dp, start = 12.dp, end = 12.dp, bottom = 6.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TopButton(if (s.flash == FlashMode.Off) Glyph.FlashOff else Glyph.Flash, s.flash.label, s.flash != FlashMode.Off, ui) {
            s.nextFlash(); s.feedback.click()
        }
        TopButton(Glyph.Timer, if (s.timer == 0) "關" else "${s.timer}s", s.timer > 0, ui) { s.nextTimer(); s.feedback.click() }
        Box(
            Modifier.size(52.dp).press { s.nextRatio(); s.feedback.click() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.graphicsLayer { rotationZ = ui.angle() }.height(30.dp).clip(CircleShape).border(1.2.dp, Dim, CircleShape)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(s.ratio.label, transitionSpec = { flip() }, label = "ratio") { Txt(it, 13, FontWeight.SemiBold, OnInk) }
            }
        }
        TopButton(Glyph.Grid, "格線", s.grid, ui) { s.toggleGrid(); s.feedback.click() }
        TopButton(Glyph.Level, "水平", s.level, ui) { s.toggleLevel(); s.feedback.click() }
    }
}

private fun flip() = (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f, animationSpec = tween(220))) togetherWith
    (fadeOut(tween(120)) + scaleOut(targetScale = 1.3f, animationSpec = tween(160)))

@Composable
private fun TopButton(glyph: Glyph, label: String, on: Boolean, ui: Turn, onClick: () -> Unit) {
    val tint by androidx.compose.animation.animateColorAsState(if (on) Pal.accent else OnInk, tween(200), label = "tint")
    Box(Modifier.size(52.dp).press(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(
            Modifier.graphicsLayer { rotationZ = ui.angle() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(glyph, transitionSpec = { flip() }, label = "glyph") { GlyphView(it, tint, 20.dp) }
            AnimatedContent(label, transitionSpec = { flip() }, label = "label") {
                Txt(it, 10, FontWeight.Medium, if (on) tint else Dim, Modifier.padding(top = 1.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun PermissionAsk(onAsk: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        GlyphView(Glyph.Camera, Dim, 44.dp)
        Txt("需要相機權限才能拍照", 17, FontWeight.SemiBold, OnInk, Modifier.padding(top = 14.dp))
        Txt("照片只會存在這支手機的相簿", 13, color = Dim, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.padding(top = 18.dp).height(44.dp).clip(CircleShape).background(Pal.accent).press(onClick = onAsk)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) { Txt("允許使用相機", 16, FontWeight.SemiBold, Color.White) }
    }
}

// ---------------- viewfinder ----------------

/** Corners as the photographer sees them, whichever way the phone is held. */
private fun cornerTopLeft(q: Int) = when (q) { -90 -> Alignment.BottomStart; 90 -> Alignment.TopEnd; else -> Alignment.TopStart }
private fun cornerTopRight(q: Int) = when (q) { -90 -> Alignment.TopStart; 90 -> Alignment.BottomEnd; else -> Alignment.TopEnd }

@Composable
private fun Viewfinder(c: AppController, active: Boolean, ui: Turn) {
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
    var focusOk by remember { mutableStateOf<Boolean?>(null) }
    var evShownAt by remember { mutableLongStateOf(0L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // connect the camera only while this tab is on screen
    DisposableEffect(active, preview, owner) {
        val v = preview
        if (active && v != null && owner != null) {
            started = false
            s.bind(owner, v)
        }
        onDispose { if (active) s.unbind() }
    }

    // cross-fade from the old look to the new one
    var fadeShot by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(look, preview) {
        val v = preview ?: return@LaunchedEffect
        if (started && v.look !== look) {
            fadeShot = v.snapshot()?.asImageBitmap()
            fade.snapTo(1f)
            v.look = look
            fade.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            fadeShot = null
        } else {
            v.look = look
        }
    }
    // lens / front-back switch: blur the last picture until the new camera shows up
    var switchShot by remember { mutableStateOf<ImageBitmap?>(null) }
    val switchFlip = remember { Animatable(0f) }
    LaunchedEffect(s.cameraSwitchCount) {
        if (s.cameraSwitchCount == 0) return@LaunchedEffect
        switchShot = preview?.snapshot()?.asImageBitmap()
        started = false
        switchFlip.snapTo(0f)
        switchFlip.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(started) {
        if (started && switchShot != null) {
            delay(120)
            switchShot = null
        }
    }
    LaunchedEffect(holdOriginal, preview) { preview?.original = holdOriginal }
    LaunchedEffect(s.lutVersion, preview) { preview?.reloadLut() }

    val aspect by animateFloatAsState(s.ratio.short / s.ratio.long, spring(dampingRatio = 0.85f, stiffness = 380f), label = "ratio")
    SideEffect { preview?.aspect = aspect }

    // The whole camera picture always stays on screen at the same size; the photo shape is shown by
    // dark masks sliding over it (cropping), never by squeezing the picture.
    var frameAspect by remember { mutableFloatStateOf(3f / 4f) }
    LaunchedEffect(preview) { preview?.onFrameAspect = { frameAspect = it } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fw = if (maxWidth / maxHeight < frameAspect) maxWidth else maxHeight * frameAspect
        val fh = fw / frameAspect
        // the photo inside it
        val w = if (aspect < frameAspect) fh * aspect else fw
        val h = if (aspect < frameAspect) fh else fw / aspect
        Box(Modifier.size(fw, fh)) {
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
            fadeShot?.let {
                Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.FillBounds)
            }
            val sw = switchShot
            if (sw != null) {
                Image(
                    sw, null,
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1f - 0.08f * kotlin.math.sin(switchFlip.value * Math.PI).toFloat()
                            scaleY = scaleX
                        }
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier)
                        .graphicsLayer { alpha = 0.92f },
                    contentScale = ContentScale.FillBounds,
                )
            } else if (!started) {
                Box(Modifier.fillMaxSize().background(Ink))
            }

            // masks outside the photo
            val cropW = w
            val cropH = h
            Canvas(Modifier.fillMaxSize()) {
                val cw = cropW.toPx()
                val chh = cropH.toPx()
                val x0 = (size.width - cw) / 2f
                val y0 = (size.height - chh) / 2f
                val m = Ink.copy(alpha = 0.88f)
                if (x0 > 0.5f) {
                    drawRect(m, Offset.Zero, androidx.compose.ui.geometry.Size(x0, size.height))
                    drawRect(m, Offset(size.width - x0, 0f), androidx.compose.ui.geometry.Size(x0, size.height))
                }
                if (y0 > 0.5f) {
                    drawRect(m, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, y0))
                    drawRect(m, Offset(0f, size.height - y0), androidx.compose.ui.geometry.Size(size.width, y0))
                }
                // a fine frame line on the photo edge
                drawRect(Color(0x55FFFFFF), Offset(x0, y0), androidx.compose.ui.geometry.Size(cw, chh), style = Stroke(1.dp.toPx()))
            }

          Box(Modifier.align(Alignment.Center).size(w, h)) {
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
                        var lastEv = s.ev
                        var lastZoomStep = floor(s.shownZoom * 2f)
                        while (true) {
                            val e = awaitPointerEvent()
                            val pressed = e.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                mode = 2
                                zoomAcc *= e.calculateZoom()
                                s.changeZoom(startZoom * zoomAcc)
                                val step = floor(s.shownZoom * 2f)
                                if (step != lastZoomStep) { lastZoomStep = step; s.feedback.tick() }
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
                                if (s.ev != lastEv) { lastEv = s.ev; s.feedback.tick() }
                                evShownAt = System.currentTimeMillis()
                                pressed[0].consume()
                            }
                        }
                        if (mode == 0) {
                            focusAt = start
                            focusStamp = System.currentTimeMillis()
                            focusOk = null
                            s.feedback.tick()
                            preview?.let { v ->
                                val (fx, fy) = v.toFrame(start.x / wPx, start.y / hPx)
                                val stamp = focusStamp
                                s.focusAt(v.display, fx, fy) { ok ->
                                    if (stamp == focusStamp) {
                                        focusOk = ok
                                        if (ok) s.feedback.confirm()
                                    }
                                }
                            }
                        }
                    }
                },
            )

            if (s.grid) GridLines()
            if (s.level) LevelLine(active, s)

            // focus ring: lands with a little bounce, turns green once focused
            val fp = focusAt
            if (fp != null) {
                LaunchedEffect(focusStamp) { delay(2600); if (System.currentTimeMillis() - focusStamp >= 2500) focusAt = null }
                val k = remember(focusStamp) { Animatable(1.45f) }
                LaunchedEffect(focusStamp) { k.animateTo(1f, Bouncy) }
                val color by androidx.compose.animation.animateColorAsState(
                    when (focusOk) { true -> FocusOk; false -> Focus.copy(alpha = 0.5f); null -> Focus }, tween(180), label = "af",
                )
                val ringPx = with(density) { 72.dp.toPx() }
                Canvas(
                    Modifier.offset { IntOffset((fp.x - ringPx / 2).roundToInt(), (fp.y - ringPx / 2).roundToInt()) }.size(72.dp)
                        .graphicsLayer { scaleX = k.value; scaleY = k.value },
                ) {
                    val sw = 1.6.dp.toPx()
                    val l = size.minDimension * 0.22f
                    // corner brackets, like a camera's AF frame
                    for ((x, y) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
                        val dx = if (x == 0f) l else -l
                        val dy = if (y == 0f) l else -l
                        drawLine(color, Offset(x, y), Offset(x + dx, y), sw, StrokeCap.Round)
                        drawLine(color, Offset(x, y), Offset(x, y + dy), sw, StrokeCap.Round)
                    }
                    drawCircle(color, radius = 2.dp.toPx(), center = center)
                }
            }

            // exposure readout while dragging
            var showEv by remember { mutableStateOf(false) }
            LaunchedEffect(evShownAt) { if (evShownAt != 0L) { showEv = true; delay(1200); showEv = false } }
            AnimatedVisibility(showEv, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut()) {
                Box(
                    Modifier.turned(ui.quarter, ui.angle).clip(CircleShape).background(Color(0x99000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Txt(evLabel(s.ev), 18, FontWeight.SemiBold, Focus) }
            }

            // what is being applied — reads upright in the photographer's top-left corner
            Column(
                Modifier.align(cornerTopLeft(ui.quarter)).padding(10.dp).turned(ui.quarter, ui.angle),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (recipe != null) {
                    val missing = look.lutKey != null && look.lutKey !in s.installed
                    AnimatedContent(recipe.name.ifEmpty { "未命名" }, transitionSpec = { flip() }, label = "name") {
                        Chip(it, bold = true)
                    }
                    Chip(
                        when {
                            holdOriginal -> "原圖"
                            missing -> "未下載 LUT"
                            look.approx -> "近似 · ${recipe.filmSim}"
                            else -> recipe.filmSim
                        },
                    )
                }
                AnimatedVisibility(s.ev != 0f, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { Chip(evLabel(s.ev)) }
            }

            // hold to compare with the plain picture
            if (recipe != null) {
                val bg by androidx.compose.animation.animateColorAsState(if (holdOriginal) Pal.accent else Color(0x88000000), label = "orig")
                val k by animateFloatAsState(if (holdOriginal) 1.08f else 1f, Bouncy, label = "origK")
                Box(
                    Modifier
                        .align(cornerTopRight(ui.quarter))
                        .padding(10.dp)
                        .turned(ui.quarter, ui.angle)
                        .graphicsLayer { scaleX = k; scaleY = k }
                        .clip(CircleShape)
                        .background(bg)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                holdOriginal = true
                                s.feedback.tick()
                                while (true) {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { it.consume() }
                                    if (e.changes.none { it.pressed }) break
                                }
                                holdOriginal = false
                                s.feedback.tick()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Txt("按住看原圖", 12, FontWeight.SemiBold, Color.White) }
            }

            ZoomChips(s, ui, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))

            // countdown: each second pops in
            Box(Modifier.align(Alignment.Center).turned(ui.quarter, ui.angle)) {
                AnimatedContent(
                    s.countdown,
                    transitionSpec = {
                        (fadeIn(tween(120)) + scaleIn(initialScale = 1.8f, animationSpec = spring(0.5f, 500f))) togetherWith
                            (fadeOut(tween(150)) + scaleOut(targetScale = 0.6f))
                    },
                    label = "count",
                ) { n -> if (n > 0) Txt("$n", 110, FontWeight.Bold, Color(0xEEFFFFFF)) else Spacer(Modifier.size(1.dp)) }
            }

            // shutter blink
            val blink = remember { Animatable(0f) }
            LaunchedEffect(s.shutterCount) {
                if (s.shutterCount > 0) { blink.snapTo(0.9f); blink.animateTo(0f, tween(300)) }
            }
            if (blink.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = blink.value }.background(Color.Black))

            LutBanner(c, look, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 12.dp, end = 12.dp))
          }
        }
    }
}

private fun evLabel(ev: Float): String {
    val r = (ev * 10).roundToInt() / 10f
    return (if (r > 0) "+" else if (r < 0) "−" else "±") + "%.1f".format(abs(r)) + " EV"
}

@Composable
private fun Chip(text: String, bold: Boolean = false) {
    Box(Modifier.clip(CircleShape).background(Color(0x88000000)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Txt(text, if (bold) 13 else 12, if (bold) FontWeight.SemiBold else FontWeight.Medium, Color.White, maxLines = 1)
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

/** A line that always lies along the real horizon; it turns yellow (with a tap) when the phone is level. */
@Composable
private fun LevelLine(active: Boolean, s: ShootController) {
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    var seeded by remember { mutableStateOf(false) }
    DisposableEffect(active) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                if (!seeded) { roll = a; seeded = true; return }
                // smooth, taking the short way round ±180°
                var d = a - roll
                while (d > 180f) d -= 360f
                while (d < -180f) d += 360f
                roll += d * 0.2f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (active && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    val nearest = (roll / 90f).roundToInt() * 90f
    val off = roll - nearest
    val ok = abs(off) < 1.0f
    LaunchedEffect(ok) { if (ok && seeded) s.feedback.click() }
    val color by androidx.compose.animation.animateColorAsState(if (ok) Focus else Color(0xCCFFFFFF), tween(150), label = "lvl")
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val len = size.minDimension * 0.24f
        // fixed marks show where "level" is for the way the phone is held
        rotate(-nearest, c) {
            drawLine(color.copy(alpha = 0.55f), Offset(c.x - len * 1.45f, c.y), Offset(c.x - len * 1.1f, c.y), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(color.copy(alpha = 0.55f), Offset(c.x + len * 1.1f, c.y), Offset(c.x + len * 1.45f, c.y), 1.5.dp.toPx(), StrokeCap.Round)
        }
        // the moving line: the real horizon
        rotate(-roll, c) {
            drawLine(color, Offset(c.x - len, c.y), Offset(c.x + len, c.y), (if (ok) 2.5.dp else 1.6.dp).toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun ZoomChips(s: ShootController, ui: Turn, modifier: Modifier) {
    if (!s.cameraReady) return
    val lenses = if (s.useFront) emptyList() else s.lenses
    val optical = lenses.size >= 2
    // lenses the phone opens to apps, or plain digital steps on the one camera
    val stops: List<Float> = if (optical) lenses.map { it.ratio }
    else listOfNotNull(1f, 2f.takeIf { s.zoomMax >= 2f }, 5f.takeIf { s.zoomMax >= 5f })
    if (stops.size < 2 && s.zoom == 1f) return
    val realLenses = optical && lenses.any { it.zoomRatio == null }
    val current = if (realLenses) s.lensIndex else stops.indices.minByOrNull { abs(stops[it] - s.shownZoom) } ?: 0
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(!optical && s.shownZoom > 1.01f, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.padding(bottom = 6.dp).clip(CircleShape).background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Txt("數位放大", 10, FontWeight.Medium, Dim)
            }
        }
        Row(
            Modifier.clip(CircleShape).background(Color(0x66000000)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            stops.forEachIndexed { i, z ->
                val on = i == current
                val shown = s.shownZoom
                val label = when {
                    on && abs(shown - z) > 0.05f -> "%.1f×".format(shown)
                    z < 1f -> "%.1f".format(z).trimEnd('0').trimEnd('.')
                    abs(z - z.roundToInt()) > 0.05f -> "%.1f×".format(z)
                    else -> "${z.roundToInt()}×"
                }
                val k by animateFloatAsState(if (on) 1f else 0.82f, Bouncy, label = "z$i")
                val bg by androidx.compose.animation.animateColorAsState(if (on) Color(0x33FFFFFF) else Color.Transparent, label = "zb$i")
                Box(
                    Modifier.size(40.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(bg)
                        .press(to = 0.8f) {
                            s.feedback.click()
                            if (optical) s.selectLens(i) else s.changeZoom(z)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.graphicsLayer { rotationZ = ui.angle() }) {
                        Txt(label, if (on) 12 else 11, FontWeight.SemiBold, if (on) Focus else Color.White, maxLines = 1)
                    }
                }
            }
        }
    }
}

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
            val p by animateFloatAsState(s.lutProgress.coerceIn(0.02f, 1f), label = "lut")
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).clip(CircleShape).background(Pal.accent))
            }
        } else {
            val size = if (s.lutSize > 0) "約 ${s.lutSize / 1_000_000} MB" else "約 45 MB"
            Txt("需要富士官方底片模擬 LUT", 15, FontWeight.SemiBold, OnInk)
            Txt("從富士官網下載（$size，建議用 Wi-Fi），只要下載一次。", 12, color = Dim)
            if (s.lutText.isNotEmpty()) Txt(s.lutText, 12, color = Color(0xFFFF8A80))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.height(38.dp).clip(CircleShape).background(Pal.accent).press { s.feedback.click(); s.downloadLuts() }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("下載", 14, FontWeight.SemiBold, Color.White) }
                Box(
                    Modifier.height(38.dp).clip(CircleShape).border(1.dp, Dim, CircleShape).press { c.tab = Tab.Settings }
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
    val items = listOf<Recipe?>(null) + c.recipes
    val selectedIndex = items.indexOfFirst { it?.id == s.recipeId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)

    fun centreIndex(): Int? {
        val info = state.layoutInfo
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index
    }

    // a light tick for every recipe that passes the middle, like turning a dial
    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrollInProgress) centreIndex() else null }
            .filter { it != null }.distinctUntilChanged().drop(1)
            .collect { s.feedback.tick() }
    }
    // the chip that stops in the middle becomes the recipe
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            val idx = centreIndex() ?: return@collect
            val r = items.getOrNull(idx)
            if (r?.id != s.recipeId) {
                s.feedback.click()
                s.selectRecipe(r?.id)
            }
        }
    }
    // keep the selection centred when it changes elsewhere
    LaunchedEffect(selectedIndex) {
        if (!state.isScrollInProgress) state.animateScrollToItem(selectedIndex)
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
            itemsIndexed(items, key = { _, r -> r?.id ?: "none" }) { i, r ->
                val on = r?.id == s.recipeId
                val scale by animateFloatAsState(if (on) 1f else 0.9f, Bouncy, label = "chip")
                val bg by androidx.compose.animation.animateColorAsState(if (on) Color(0x26FFFFFF) else Color(0x12FFFFFF), label = "chipBg")
                val border by androidx.compose.animation.animateColorAsState(if (on) Pal.accent else Color.Transparent, label = "chipBd")
                Row(
                    Modifier
                        .width(120.dp)
                        .height(42.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)
                        .background(bg)
                        .border(1.5.dp, border, CircleShape)
                        .press(to = 0.92f) {
                            if (!on) {
                                s.feedback.click()
                                s.selectRecipe(r?.id)
                                scope.launch { state.animateScrollToItem(i) }
                            }
                        }
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
private fun BottomControls(c: AppController, ui: Turn, onThumbPlaced: (Rect) -> Unit) {
    val s = c.shoot
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // last photo: pops when a new one lands
        val pop = remember { Animatable(1f) }
        LaunchedEffect(s.lastThumb) {
            if (s.lastThumb != null) { delay(380); pop.snapTo(0.7f); pop.animateTo(1f, Bouncy) }
        }
        Box(
            Modifier.size(52.dp).onGloballyPositioned { onThumbPlaced(it.boundsInRoot()) }
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = ui.angle() }
                .clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF))
                .press(enabled = s.lastUri != null) {
                    val uri = s.lastUri ?: return@press
                    s.feedback.click()
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
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) { Spinner() }
            }
        }

        // shutter: the inner disc sinks while pressed, like a real button
        var down by remember { mutableStateOf(false) }
        val inner by animateFloatAsState(if (down || s.capturing) 0.78f else 1f, spring(0.45f, 700f), label = "shutter")
        val fill by androidx.compose.animation.animateColorAsState(if (s.countdown > 0) Pal.accent else OnInk, label = "fill")
        Box(
            Modifier
                .size(80.dp)
                .border(4.dp, OnInk, CircleShape)
                .pointerInput(s.cameraReady) {
                    if (!s.cameraReady) return@pointerInput
                    detectTapGestures(
                        onPress = { down = true; s.feedback.tick(); tryAwaitRelease(); down = false },
                        onTap = {
                            when {
                                s.review != null -> { s.dismissReview(); s.feedback.click() }
                                s.countdown > 0 -> s.cancelTimer()
                                else -> s.shutter()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(64.dp).graphicsLayer { scaleX = inner; scaleY = inner }.clip(CircleShape).background(fill))
        }

        // front / back: the icon spins round on each switch
        val spin = remember { Animatable(0f) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0x22FFFFFF))
                .press(enabled = s.hasFront) {
                    s.feedback.click()
                    s.switchCamera()
                    scope.launch { spin.animateTo(spin.value + 180f, spring(0.6f, 300f)) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { rotationZ = ui.angle() + spin.value }) { GlyphView(Glyph.Flip, OnInk, 24.dp) }
        }
    }
}

/** The new photo flies from the viewfinder into the thumbnail. */
@Composable
private fun ThumbFlight(s: ShootController, finder: Rect, thumb: Rect) {
    val bmp = s.lastThumb ?: return
    val t = remember(bmp) { Animatable(0f) }
    var done by remember(bmp) { mutableStateOf(false) }
    LaunchedEffect(bmp) {
        t.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        done = true
    }
    if (done || finder == Rect.Zero || thumb == Rect.Zero) return
    val img = remember(bmp) { bmp.asImageBitmap() }
    val density = LocalDensity.current
    val sizePx = with(density) { 52.dp.toPx() }
    val startScale = min(finder.width, finder.height) * 0.55f / sizePx
    Box(
        Modifier
            .offset { IntOffset((thumb.center.x - sizePx / 2).roundToInt(), (thumb.center.y - sizePx / 2).roundToInt()) }
            .size(52.dp)
            .graphicsLayer {
                val p = t.value
                translationX = (finder.center.x - thumb.center.x) * (1 - p)
                translationY = (finder.center.y - thumb.center.y) * (1 - p) - 60f * kotlin.math.sin(p * Math.PI).toFloat()
                val sc = startScale + (1f - startScale) * p
                scaleX = sc; scaleY = sc
                alpha = if (p < 0.92f) 1f else (1f - p) / 0.08f
            }
            .clip(RoundedCornerShape(10.dp)),
    ) { Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(22.dp)) {
        drawArc(Color.White, a, 270f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * The photo just taken, like a camera's image display: a quick version at once, the finished one when ready.
 * Pinch to look closer; tap or swipe down (or touch the shutter) to go back.
 */
@Composable
private fun ReviewOverlay(s: ShootController) {
    val r = s.review
    val context = LocalContext.current
    AnimatedVisibility(r != null, enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.96f), exit = fadeOut(tween(180))) {
        val rv = r ?: return@AnimatedVisibility
        LaunchedEffect(rv.id, s.reviewMode) {
            val ms = when (s.reviewMode) { 1 -> 1500L; 2 -> 3000L; else -> 0L }
            if (ms > 0) { delay(ms); if (s.review?.id == rv.id) s.dismissReview() }
        }
        var scale by remember(rv.id) { mutableFloatStateOf(1f) }
        var pan by remember(rv.id) { mutableStateOf(Offset.Zero) }
        var dragY by remember(rv.id) { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize().background(Ink.copy(alpha = (1f - abs(dragY) / 900f).coerceIn(0.4f, 1f)))
                .pointerInput(rv.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var multi = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val pressed = e.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                multi = true
                                scale = (scale * e.calculateZoom()).coerceIn(1f, 6f)
                                val pc = e.changes.first().let { it.position - it.previousPosition }
                                pan += pc
                                if (scale == 1f) pan = Offset.Zero
                                e.changes.forEach { it.consume() }
                            } else {
                                val d = pressed[0].position - pressed[0].previousPosition
                                if (abs(d.x) + abs(d.y) > 1f) moved = true
                                if (scale > 1f) pan += d else dragY += d.y
                                pressed[0].consume()
                            }
                        }
                        when {
                            !moved && !multi && scale == 1f -> s.dismissReview()
                            scale == 1f && dragY > 160f -> s.dismissReview()
                            else -> dragY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = rv.final ?: rv.quick
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    img, null,
                    Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = pan.x; translationY = pan.y + dragY
                    },
                    contentScale = ContentScale.Fit,
                )
            }
            if (rv.busy) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Color(0x99000000))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner()
                    Txt(if (bmp == null) "拍攝中…" else "處理中…", 12, FontWeight.Medium, Color.White, Modifier.padding(start = 6.dp))
                }
            }
            if (rv.info.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Txt(rv.info, 12, FontWeight.Medium, Color.White, maxLines = 2) }
            }
            val uri = rv.uri
            if (s.reviewMode == 3 && uri != null) {
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(CircleShape).background(Color(0x99000000)).press {
                            s.feedback.click()
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "分享照片",
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Txt("分享", 13, FontWeight.SemiBold, Color.White) }
                    Box(
                        Modifier.clip(CircleShape).background(Color(0xCCB3261E)).press { s.feedback.click(); s.deleteLast(uri) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Txt("刪除", 13, FontWeight.SemiBold, Color.White) }
                }
            }
        }
    }
}

/** For the settings page: shooting options, LUTs and what the phone's cameras allow. */
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
        ToggleRow("RAW 成像（支援的手機）", s.rawEnabled) { s.changeRaw(it) }
        Divider()
        ToggleRow("快門聲", s.shutterSoundOn) { s.changeShutterSound(it) }
        Divider()
        ToggleRow("音量鍵當快門", s.volumeShutter) { s.changeVolumeShutter(it) }
    }
    Spacer(Modifier.height(10.dp))
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("拍完顯示照片", 15, FontWeight.SemiBold)
            SegmentedControl(listOf("關", "1.5 秒", "3 秒", "持續"), s.reviewMode) { s.changeReviewMode(it) }
            Txt("「持續」會一直顯示到你點一下或往下滑，並提供分享和刪除。", 12, color = p.sub)
        }
    }
    Spacer(Modifier.height(10.dp))
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueRow("富士官方 LUT", if (s.installed.isEmpty()) "尚未下載" else "已安裝 ${s.installed.size} 種")
            if (s.installed.isNotEmpty() && s.installedFlog.size < s.installed.size) {
                Txt("RAW 成像需要再下載一次 LUT（舊版只保存了手機用的版本）。按下面的「重新下載」即可。", 12, color = p.danger)
            }
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
    Spacer(Modifier.height(10.dp))
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("相機資訊", 15, FontWeight.SemiBold)
            if (s.cameraReport.isEmpty()) {
                Txt("打開一次拍照分頁後，這裡會列出偵測到的鏡頭。", 12, color = p.sub)
            } else {
                s.cameraReport.forEach { Txt(it, 12, color = p.sub) }
            }
            if (s.lensMode.isNotEmpty()) Txt(s.lensMode, 13, FontWeight.Medium)
            if (s.rawReport.isNotEmpty()) Txt(s.rawReport, 13, FontWeight.Medium)
            if (s.scanning) Txt("正在偵測鏡頭…", 12, color = p.sub)
            Spacer(Modifier.height(4.dp))
            SoftButton("重新偵測鏡頭", { s.rescanLenses() })
            Txt("換了系統版本，或鏡頭切換怪怪的時候可以按。下次打開拍照分頁會重新找一次。", 12, color = p.sub)
        }
    }
}
