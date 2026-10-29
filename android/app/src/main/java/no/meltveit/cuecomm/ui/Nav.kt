package no.meltveit.cuecomm.ui

import androidx.compose.runtime.mutableStateListOf
import no.meltveit.cuecomm.core.Channel
import java.util.UUID

sealed interface Route {
    data object Main : Route
    data object Session : Route
    data class ChannelEditor(val draft: Channel, val isNew: Boolean) : Route
    data class GroupEditor(val id: UUID?) : Route
    data object Cues : Route
    data object Automations : Route
    data class AutomationEditor(val id: UUID?) : Route
    data object Settings : Route
}

/** A tiny back stack: the Main screen is always at the bottom. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    val top: Route get() = stack.last()
    fun push(r: Route) { if (top != r) stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
