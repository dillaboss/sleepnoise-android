package ca.sleepnoise

/** Wake word + command matching on recogniser output. Pure logic, so it is unit-tested off-device. */
class WakeLogic(wakePhrase: String, var minConf: Double) {
    data class Word(val w: String, val conf: Double)
    data class Command(val id: String, val phrases: List<String>, val say: String)

    sealed class Decision {
        data class Run(val cmd: Command) : Decision()
        object OpenWindow : Decision()
        object Nothing : Decision()
    }

    var wake: List<String> = split(wakePhrase)
        private set
    val wakePhrase get() = wake.joinToString(" ")

    fun setWake(phrase: String) { wake = split(phrase) }

    /** Phrases the recogniser is allowed to hear; anything else becomes [unk]. */
    fun grammar(): List<String> = (listOf(wakePhrase) + COMMANDS.flatMap { it.phrases }).distinct() + "[unk]"

    fun partialHasWake(partial: String) = " ${partial.trim()} ".contains(" $wakePhrase ")

    fun decide(words: List<Word>, windowOpen: Boolean): Decision {
        var wakeAt = -1
        var i = 0
        while (i + wake.size <= words.size) {
            var ok = true; var conf = 0.0
            for (j in wake.indices) { if (words[i + j].w != wake[j]) { ok = false; break }; conf += words[i + j].conf }
            if (ok && conf / wake.size >= minConf) { wakeAt = i; break }
            i++
        }
        if (wakeAt >= 0) {
            val cmd = findCommand(words.drop(wakeAt + wake.size).map { it.w })
            return if (cmd != null) Decision.Run(cmd) else Decision.OpenWindow
        }
        if (windowOpen) findCommand(words.map { it.w })?.let { return Decision.Run(it) }
        return Decision.Nothing
    }

    companion object {
        val COMMANDS = listOf(
            Command("start", listOf("start", "play", "resume", "good night", "goodnight"), "Playing"),
            Command("stop", listOf("stop", "pause", "good morning"), "Paused"),
            Command("louder", listOf("louder", "volume up"), "Louder"),
            Command("quieter", listOf("quieter", "softer", "volume down"), "Quieter"),
            Command("deeper", listOf("deeper"), "Deeper"),
            Command("brighter", listOf("brighter"), "Brighter"),
        )

        fun split(p: String) = p.lowercase().replace(Regex("[^a-z' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        fun findCommand(words: List<String>): Command? {
            val text = " " + words.joinToString(" ") + " "
            for (c in COMMANDS) for (p in c.phrases) if (text.contains(" $p ")) return c
            return null
        }
    }
}
