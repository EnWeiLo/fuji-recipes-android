package com.enweilo.fujirecipes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.Screen
import com.enweilo.fujirecipes.Tab
import kotlinx.coroutines.delay

private data class Nav(val screen: Screen?, val depth: Int)

@Composable
fun App(c: AppController) {
    val dark = when (c.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette

    val view = LocalView.current
    // the 拍照 tab is black, so it wants light status bar icons
    val camera = c.tab == com.enweilo.fujirecipes.Tab.Shoot && c.stack.isEmpty()
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark && !camera
            controller.isAppearanceLightNavigationBars = !dark && !camera
        }
    }

    // These live here so the list position and the current page survive opening a recipe and coming back.
    val listState = rememberLazyListState()
    val pager = rememberPagerState(initialPage = c.tab.ordinal) { Tab.entries.size }

    var shownText by remember { mutableStateOf("") }
    var toastVisible by remember { mutableStateOf(false) }
    val incoming = c.toast
    LaunchedEffect(incoming?.serial) {
        if (incoming != null) {
            shownText = incoming.text
            toastVisible = true
            delay(2200)
            toastVisible = false
        }
    }

    CompositionLocalProvider(LocalPalette provides palette, LocalMotion provides c.bgMotion) {
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            val nav = Nav(c.stack.lastOrNull(), c.stack.size)
            AnimatedContent(
                targetState = nav,
                contentKey = { n ->
                    when (n.screen) {
                        null -> "main"
                        is Screen.Detail -> "detail"
                        is Screen.Edit -> "edit"
                    }
                },
                transitionSpec = { navTransition(initialState, targetState) },
                label = "nav",
            ) { n ->
                when (val top = n.screen) {
                    is Screen.Detail -> DetailScreen(c, top.id)
                    is Screen.Edit -> EditScreen(c)
                    null -> MainScreen(c, listState, pager)
                }
            }
            AnimatedVisibility(
                toastVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
            ) {
                Box { ToastPill(shownText) }
            }
        }
    }
}

/** Going deeper slides the new page in from the right and nudges the old one left; going back does the reverse. */
private fun navTransition(from: Nav, to: Nav): ContentTransform {
    val push = to.depth > from.depth
    val spec = tween<androidx.compose.ui.unit.IntOffset>(380, easing = FastOutSlowInEasing)
    val t = if (push) {
        (slideInHorizontally(spec) { it } + fadeIn(tween(260))) togetherWith
            (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(300)))
    } else {
        (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(300))) togetherWith
            (slideOutHorizontally(spec) { it } + fadeOut(tween(240)))
    }
    t.targetContentZIndex = if (push) 1f else 0f
    return t
}
