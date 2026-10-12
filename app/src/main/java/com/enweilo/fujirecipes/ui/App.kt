package com.enweilo.fujirecipes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.Screen
import com.enweilo.fujirecipes.ToastMessage
import kotlinx.coroutines.delay

@Composable
fun App(c: AppController) {
    val dark = when (c.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette

    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val listState = rememberLazyListState()
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    val incoming = c.toast
    LaunchedEffect(incoming?.serial) {
        if (incoming != null) {
            shown = incoming
            delay(2200)
            shown = null
        }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            when (val top = c.stack.lastOrNull()) {
                is Screen.Detail -> DetailScreen(c, top.id)
                is Screen.Edit -> EditScreen(c)
                null -> MainScreen(c, listState)
            }
            shown?.let { ToastPill(it.text) }
        }
    }
}
