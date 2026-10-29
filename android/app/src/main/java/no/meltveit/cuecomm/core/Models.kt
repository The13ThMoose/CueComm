@file:UseSerializers(UUIDSerializer::class, InstantSerializer::class)

package no.meltveit.cuecomm.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

// The JSON written here is read by CueComm for Mac and the other way round: sessions exchange it over the
// network and setup files can be moved between the two. Two details matter to Swift's Codable:
// UUIDs are upper case, and dates are ISO 8601 without fractional seconds.

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(Instant.ofEpochSecond(value.epochSecond).toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Tolerant on input (older or newer files lack or add keys), complete on output. */
val CueJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

// MARK: - Channels & groups

object Palette {
    // Orange (action), green (live) and yellow (cue) are reserved, so channels never use them.
    val hexes = listOf("FF5F4A", "FF4FA3", "E23DFF", "8F7BFF", "4D8DFF", "35D8FF", "22C7B8", "A8B4FF")
    val flash = listOf("FFE14D", "FF4FA3", "39FF88", "35D8FF", "FF9F1C", "FFFFFF")
    val names = mapOf(
        "FF5F4A" to "Coral", "FF4FA3" to "Pink", "E23DFF" to "Magenta", "8F7BFF" to "Violet", "4D8DFF" to "Blue",
        "35D8FF" to "Cyan", "22C7B8" to "Teal", "A8B4FF" to "Periwinkle", "FFE14D" to "Yellow", "39FF88" to "Green",
        "FF9F1C" to "Orange", "FFFFFF" to "White",
    )
}

@Serializable
data class Channel(
    val id: UUID = UUID.randomUUID(),
    val name: String = "Channel",
    val colorHex: String = Palette.hexes[0],
    val groupID: UUID? = null,
    val pinned: Boolean = false,
    val deviceUID: String? = null,          // kept for setup files from the Mac; Android listens to the system input
    val inputChannel: Int = 0,
    val localeID: String = "en-US",         // spoken language
    val keywords: List<String> = emptyList(),
    val translate: Boolean = false,
    val enabled: Boolean = true,            // can be spoken into; off = muted
    val shared: Boolean = false,            // share on the LAN (open sharing, no session)
    val isPrivate: Boolean = false,         // in a session: keep this channel on this device only
    val onDeviceOnly: Boolean = true,
    val isRemote: Boolean = false,          // owned by another machine
    val ownerName: String? = null,
) {
    /** Two letters shown in the channel's colour block: initials of the first two words, or the first two letters. */
    val initials: String
        get() {
            val parts = name.split(" ").filter { it.isNotEmpty() }
            val s = if (parts.size > 1) parts.take(2).map { it.first() }.joinToString("") else name.take(2)
            return s.uppercase()
        }
}

@Serializable
data class ChannelGroup(
    val id: UUID = UUID.randomUUID(),
    val name: String = "Group",
    val shared: Boolean = false,
    val isRemote: Boolean = false,
)

@Serializable
data class Message(
    val id: UUID = UUID.randomUUID(),
    val channelID: UUID,
    val date: Instant = Instant.now(),
    val speaker: String,
    val text: String,
    val translation: String? = null,
    val typed: Boolean = false,
    val remote: Boolean = false,
    val keywordHit: Boolean = false,
)

// MARK: - Settings

@Serializable
data class AppSettings(
    val fontSize: Double = 22.0,
    val filterOffensive: Boolean = false,
    val showTimestamps: Boolean = true,
    val preferredLanguage: String = "en",       // translation target
    val silenceCommit: Double = 1.2,            // seconds of silence that ends a message
    val triggerOnPartials: Boolean = true,      // fire keyword automations before the sentence is finished
    val alwaysOnTop: Boolean = false,           // Mac only
    val displayName: String = "CueComm",
    val midiOutputName: String = "",            // Mac only
    val midiInputEnabled: Boolean = true,       // Mac only
    val oscListenEnabled: Boolean = false,
    val oscListenPort: Int = 9000,
    val networkEnabled: Boolean = false,        // open sharing of flagged channels with every device on the LAN
    val sessionName: String = "",               // non-empty = in a named session
    val oscDefaultHost: String = "127.0.0.1",
    val oscDefaultPort: Int = 8000,
    val historyLimit: Int = 1000,
    val flashOnRemoteCues: Boolean = true,      // flash this screen when a cue fires on another device in the session
    val listenPort: Int = ManualAddress.DEFAULT_PORT,   // TCP port other devices dial to reach this one
    val manualPeers: List<String> = emptyList(),        // "host:port" of devices on other networks, dialled while a session is live
    // Android only; the Mac ignores them.
    val keepScreenOn: Boolean = true,
    val vibrateOnCue: Boolean = true,
    val speakingChannelID: UUID? = null,
)

// MARK: - Automations

@Serializable
enum class MIDIKind(val label: String) {
    @SerialName("noteOn") NoteOn("Note On"),
    @SerialName("noteOff") NoteOff("Note Off"),
    @SerialName("controlChange") ControlChange("Control Change"),
    @SerialName("programChange") ProgramChange("Program Change"),
}

@Serializable
enum class TriggerKind(val label: String) {
    @SerialName("keyword") Keyword("Keyword or phrase heard"),
    @SerialName("message") Message("Any message arrives"),
    @SerialName("midi") Midi("MIDI message received"),
    @SerialName("osc") Osc("OSC message received"),
}

@Serializable
enum class ActionKind(val label: String) {
    @SerialName("sendMIDI") SendMIDI("Send MIDI Command"),
    @SerialName("sendOSC") SendOSC("Send OSC Message"),
    @SerialName("flashWindow") FlashWindow("Flash Screen"),
    @SerialName("showWindow") ShowWindow("Show Window"),
    @SerialName("hideWindow") HideWindow("Hide Window"),
    @SerialName("selectChannel") SelectChannel("Select Channel"),
    @SerialName("scrollTranscript") ScrollTranscript("Scroll Transcript"),
    @SerialName("clearTranscript") ClearTranscript("Clear Transcript"),
    @SerialName("markRead") MarkRead("Mark Transcript As Read"),
    @SerialName("adjustFontSize") AdjustFontSize("Adjust Font Size"),
}

@Serializable
enum class ScrollTarget(val label: String) {
    @SerialName("current") Current("Jump to current"),
    @SerialName("top") Top("Scroll to top"),
}

@Serializable
data class AutomationTrigger(
    val kind: TriggerKind = TriggerKind.Keyword,
    val phrase: String = "",                    // comma separated alternatives
    val wholeWord: Boolean = true,
    val channelID: UUID? = null,                // null = any channel
    val midiKind: MIDIKind = MIDIKind.NoteOn,
    val midiChannel: Int = 0,
    val data1: Int = -1,
    val oscAddress: String = "",                // supports * wildcard
    val includeRemote: Boolean = false,         // also fire for speech heard on other devices in the session
)

@Serializable
data class AutomationAction(
    val id: UUID = UUID.randomUUID(),
    val kind: ActionKind = ActionKind.FlashWindow,
    val midiKind: MIDIKind = MIDIKind.NoteOn,
    val midiChannel: Int = 1,
    val data1: Int = 60,
    val data2: Int = 127,
    val oscHost: String = "127.0.0.1",
    val oscPort: Int = 8000,
    val oscAddress: String = "/cue",
    val oscArgs: String = "",                   // {text} {channel} {keyword} are substituted
    val targetChannelID: UUID? = null,          // null = current selection
    val scrollTarget: ScrollTarget = ScrollTarget.Current,
    val fontDelta: Int = 2,
    val delay: Double = 0.0,
    val flashColorHex: String = "FFE14D",
    val flashEveryone: Boolean = true,          // a flash also fires on every other device in the session
)

@Serializable
data class Automation(
    val id: UUID = UUID.randomUUID(),
    val name: String = "New automation",
    val enabled: Boolean = true,
    val trigger: AutomationTrigger = AutomationTrigger(),
    val actions: List<AutomationAction> = listOf(AutomationAction()),
    val cooldown: Double = 1.0,                 // seconds before it may fire again
    val isCue: Boolean = false,                 // created from the Cue Words panel
)

/** A setup file: what "Export Setup" writes and "Import Setup" reads, on either platform. */
@Serializable
data class ConfigBundle(
    val version: Int = 1,
    val channels: List<Channel>,
    val groups: List<ChannelGroup>,
    val automations: List<Automation>,
    val settings: AppSettings,
)

/** What is kept on disk between launches (same layout as the Mac's config.json). */
@Serializable
data class SavedConfig(
    val instanceID: String,
    val channels: List<Channel> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
    val automations: List<Automation> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val ignoredRemote: List<UUID> = emptyList(),
    val designVersion: Int? = 3,
)

// MARK: - Misc

data class LanguageOption(val code: String, val name: String)

object Languages {
    /** Languages the translator can target. */
    val translation: List<LanguageOption> = listOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "uk", "tr", "ar", "hi", "id", "th", "vi", "ja", "ko",
        "zh", "no", "sv", "da", "fi",
    ).map { LanguageOption(it, java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { it }) }

    /** Spoken languages offered per channel. Android's recogniser accepts BCP-47 tags. */
    val speech: List<LanguageOption> = listOf(
        "en-US", "en-GB", "en-AU", "nb-NO", "sv-SE", "da-DK", "fi-FI", "de-DE", "fr-FR", "es-ES", "es-MX", "it-IT",
        "nl-NL", "pt-BR", "pt-PT", "pl-PL", "ru-RU", "uk-UA", "tr-TR", "ar-SA", "hi-IN", "id-ID", "th-TH", "vi-VN",
        "ja-JP", "ko-KR", "zh-CN", "zh-TW", "cs-CZ", "el-GR", "hu-HU", "ro-RO",
    ).map { LanguageOption(it, java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale.ENGLISH)) }
}

object Offensive {
    private val words = listOf("fuck", "fucking", "shit", "bullshit", "bitch", "asshole", "bastard", "damn", "crap", "piss", "dick", "cunt")
    private val regex = Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    fun mask(s: String): String = regex.replace(s) { "•".repeat(it.value.length) }
}
