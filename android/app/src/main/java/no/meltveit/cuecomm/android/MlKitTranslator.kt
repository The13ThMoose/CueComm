package no.meltveit.cuecomm.android

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import no.meltveit.cuecomm.core.Translator

/** On-device translation. Each language pair downloads a model of about 30 MB the first time it is used. */
class MlKitTranslator : Translator {
    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    private fun tag(code: String): String? {
        val c = when (code) { "nb", "nn" -> "no"; "iw" -> "he"; else -> code }
        return TranslateLanguage.fromLanguageTag(c)
    }

    override fun translate(text: String, source: String, target: String, done: (String?) -> Unit) {
        val s = tag(source)
        val t = tag(target)
        if (s == null || t == null || s == t) { done(null); return }
        val client = clients.getOrPut("$s>$t") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                client.translate(text)
                    .addOnSuccessListener { done(it) }
                    .addOnFailureListener { done(null) }
            }
            .addOnFailureListener { done(null) }
    }
}
