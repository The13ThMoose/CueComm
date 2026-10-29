package no.meltveit.cuecomm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.meltveit.cuecomm.core.Palette

/** Filled primary button. */
@Composable
fun ActionButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fill: Color = T.orange, ink: Color = T.ink,
    enabled: Boolean = true, icon: ImageVector? = null, size: TextUnit = 16.sp,
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink, disabledContainerColor = T.raised, disabledContentColor = T.faint),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Quiet outlined button; `active` fills it with its tint. */
@Composable
fun QuietButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = T.text, active: Boolean = false,
    enabled: Boolean = true, icon: ImageVector? = null, size: TextUnit = 15.sp,
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) tint else T.raised, contentColor = if (active) T.ink else tint,
            disabledContainerColor = T.raised, disabledContentColor = T.faint,
        ),
        border = if (active) null else BorderStroke(1.dp, T.hairline),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); if (text.isNotEmpty()) Spacer(Modifier.width(7.dp)) }
        if (text.isNotEmpty()) Text(text, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun IconTile(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = T.text, active: Boolean = false) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(if (active) tint else T.raised)
            .border(1.dp, if (active) Color.Transparent else T.hairline, RoundedCornerShape(11.dp)),
        colors = IconButtonDefaults.iconButtonColors(contentColor = if (active) T.ink else tint),
    ) { Icon(icon, description) }
}

/** The signature: a colour block that sits dim while its channel is silent and lights to full hue when it speaks. */
@Composable
fun HueBlock(color: Color, label: String, active: Boolean, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(color.copy(alpha = if (active) 1f else 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.ExtraBold, color = if (active) T.ink else color)
    }
}

/** Solid hue with near-black ink: a channel's name, readable at a glance. */
@Composable
fun HueChip(name: String, color: Color, size: TextUnit = 12.sp) {
    Text(
        name.uppercase(), fontSize = size, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = T.ink,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Four coded strips for "All Channels". */
@Composable
fun StripBlock(colors: List<Color>, size: Dp = 46.dp) {
    Row(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(T.raised).padding(size * 0.14f),
        horizontalArrangement = Arrangement.spacedBy(size * 0.07f),
    ) {
        for (c in colors.take(4)) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c))
    }
}

@Composable
fun SegmentMeter(level: Float, color: Color, modifier: Modifier = Modifier, segments: Int = 14, height: Dp = 6.dp) {
    val lit = (level * segments).toInt()
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until segments) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(1.5.dp)).background(if (i < lit) color else color.copy(alpha = 0.18f)))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, size: TextUnit = 17.sp) {
    Text(text, modifier = modifier, fontSize = size, fontWeight = FontWeight.SemiBold, color = if (size.value >= 17f) T.text else T.dim)
}

@Composable
fun UnreadBadge(count: Int, missed: Boolean) {
    Row(
        Modifier.clip(CircleShape).background(if (missed) T.cue else T.text).padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = if (missed) "$count unread, includes a keyword" else "$count unread" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (missed) { Icon(Icons.Filled.Flag, null, Modifier.size(13.dp), tint = T.ink); Spacer(Modifier.width(2.dp)) }
        Text(count.toString(), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = T.ink)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, borderColor: Color = Color.Transparent, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.raised)
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** A text field that reads clearly on the dark ground, with a small title above it. */
@Composable
fun LabeledField(
    title: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, onDone: (() -> Unit)? = null,
    mono: Boolean = false, fontSize: TextUnit = 17.sp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.dim)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = TextStyle(color = T.text, fontSize = fontSize, fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null),
            cursorBrush = SolidColor(T.orange),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(T.ground)
                        .border(1.dp, T.hairline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = fontSize, color = T.faint, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
fun LabeledIntField(title: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(value.toString()) }
    LabeledField(title, text, { t -> text = t.filter { it.isDigit() }.take(6); text.toIntOrNull()?.let(onChange) }, modifier, keyboardType = KeyboardType.Number)
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true, tint: Color = T.orange) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) T.text else T.faint)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = T.faint)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = T.ink, checkedTrackColor = tint, uncheckedThumbColor = T.dim,
                uncheckedTrackColor = T.hairline, uncheckedBorderColor = T.faint,
            ),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SwatchRow(hexes: List<String>, selected: String, onSelect: (String) -> Unit, size: Dp = 36.dp) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (hex in hexes) {
            val on = selected.equals(hex, ignoreCase = true)
            Box(
                Modifier.size(size + 8.dp).clip(CircleShape).clickable { onSelect(hex) }
                    .semantics { contentDescription = Palette.names[hex] ?: hex; this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(size).clip(CircleShape).background(hue(hex)).then(if (on) Modifier.border(3.dp, T.text, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Filled.Check, null, Modifier.size(size * 0.5f), tint = T.ink) }
            }
        }
    }
}

/** A dropdown that shows its current choice. */
@Composable
fun <V> Picker(title: String, options: List<Pair<V, String>>, selected: V, onSelect: (V) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.dim)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(T.ground)
                    .border(1.dp, T.hairline, RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "", Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null, tint = T.dim)
            }
            DropdownMenu(open, { open = false }, containerColor = T.raised) {
                for ((v, label) in options) DropdownMenuItem(text = { Text(label, fontSize = 16.sp) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

/** A full-screen page with a back arrow, used for every screen except the main one. */
@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(T.ground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f).padding(start = 4.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(T.hairline))
}

/** A text field that keeps what is being typed locally and reports each change, so a store round trip never eats keystrokes. */
@Composable
fun SyncedField(
    title: String, initial: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text, mono: Boolean = false, fontSize: TextUnit = 17.sp,
) {
    var t by remember { mutableStateOf(initial) }
    LabeledField(title, t, { t = it; onChange(it) }, modifier, placeholder, keyboardType, mono = mono, fontSize = fontSize)
}
