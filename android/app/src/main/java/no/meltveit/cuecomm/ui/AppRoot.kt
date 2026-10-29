package no.meltveit.cuecomm.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import no.meltveit.cuecomm.android.CueCommApp
import no.meltveit.cuecomm.core.FlashEvent

@Composable
fun AppRoot(app: CueCommApp, actions: MainActions) {
    val store = app.store
    val nav = app.nav
    val s by store.state.collectAsStateWithLifecycle()

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    val activity = LocalContext.current as? Activity
    val keepOn = s.settings.keepScreenOn && (s.inSession || s.isListening)
    DisposableEffect(keepOn) {
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(Modifier.fillMaxSize()) {
        when (val r = nav.top) {
            Route.Main -> MainScreen(s, store, nav, actions)
            Route.Session -> SessionScreen(s, store) { nav.pop() }
            is Route.ChannelEditor -> ChannelEditorScreen(s, store, r.draft, r.isNew) { nav.pop() }
            is Route.GroupEditor -> GroupEditorScreen(s, store, r.id) { nav.pop() }
            Route.Cues -> CuesScreen(s, store, actions.onListen, onAdvanced = { nav.push(Route.Automations) }, onBack = { nav.pop() })
            Route.Automations -> AutomationsScreen(s, store, onEdit = { nav.push(Route.AutomationEditor(it)) }, onBack = { nav.pop() })
            is Route.AutomationEditor -> AutomationEditorScreen(s, store, r.id) { nav.pop() }
            Route.Settings -> SettingsScreen(s, store, actions.onOpenAppSettings) { nav.pop() }
        }
        FlashOverlay(s.flash)
    }
}

/** A coloured frame and wash that pulses four times, over every screen, without taking touches. */
@Composable
fun FlashOverlay(flash: FlashEvent) {
    val level = remember { Animatable(0f) }
    val ctx = LocalContext.current
    LaunchedEffect(flash.token) {
        if (flash.token == 0) return@LaunchedEffect
        val reduce = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        repeat(if (reduce) 1 else 4) {
            level.animateTo(1f, tween(100))
            delay(90)
            level.animateTo(0f, tween(100))
            delay(90)
        }
    }
    val color = hue(flash.colorHex)
    Canvas(Modifier.fillMaxSize()) {
        val a = level.value
        if (a <= 0f) return@Canvas
        drawRect(color.copy(alpha = 0.28f * a))
        val w = 14.dp.toPx()
        drawRect(color.copy(alpha = a), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
    }
}
