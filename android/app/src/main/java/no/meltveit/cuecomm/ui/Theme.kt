package no.meltveit.cuecomm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Matte black ground, one vibrant hue per channel. Green is reserved for "live", yellow for "cue". */
object T {
    val ground = Color(0xFF09090B)
    val panel = Color(0xFF131318)
    val raised = Color(0xFF1C1C23)
    val hairline = Color(0xFF2C2C35)

    val text = Color.White
    val dim = Color(0xFFC7CAD4)         // 12:1 on ground
    val faint = Color(0xFF9EA3B0)       // 7.8:1 on ground, the lowest text colour allowed
    val ink = Color(0xFF0B0B0E)         // text on a hue fill

    val orange = Color(0xFFFF9F1C)      // primary action (from the Meltveit mark)
    val live = Color(0xFF39FF88)        // connected / listening
    val cue = Color(0xFFFFE14D)         // keyword hit, cue fired
    val pink = Color(0xFFFF4FA3)
    val danger = Color(0xFFFF5F4A)
}

fun hue(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrDefault(T.faint)

@Composable
fun CueCommTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.orange, onPrimary = T.ink,
            secondary = T.live, onSecondary = T.ink,
            background = T.ground, onBackground = T.text,
            surface = T.panel, onSurface = T.text,
            surfaceVariant = T.raised, onSurfaceVariant = T.dim,
            surfaceContainer = T.panel, surfaceContainerHigh = T.raised, surfaceContainerHighest = T.raised,
            outline = T.hairline, outlineVariant = T.hairline,
            error = T.danger,
        ),
    ) {
        // The root surface sets the default text colour; without it Text falls back to black.
        Surface(color = T.ground, contentColor = T.text, content = content)
    }
}
