package dev.ziggle.vscript.editor.host

import dev.ziggle.imgui.EditorKeyboard
/**
 * Characters the host has collected since the last drain.
 *
 * ### Why the editor cannot just read the keyboard
 *
 * ImGui reports keys, not text. Turning a keystroke into a character is the platform's job — dead keys,
 * compose sequences, an IME, a keyboard layout that puts `@` somewhere else — and the editor is in no
 * position to do any of it. So the host collects typed characters however it collects them (this client
 * pumps them off AWT) and the editor drains the buffer.
 *
 * ### Draining is destructive, and that is the contract
 *
 * There is ONE buffer and more than one thing that would like to read it — a value picker's query, a
 * node's text field, the code view. Whichever drains first gets the characters, which is why the editor
 * has an explicit notion of who owns the keyboard (`EditorKeyboard`) and why the losers call [clear]
 * rather than reading: a widget that is not focused must actively discard, or the next thing to gain
 * focus receives everything typed while it was not looking.
 *
 * [NONE] never returns anything, which is what an embedder that has not wired a keyboard should see —
 * a read-only editor rather than a crash.
 */
interface TypedText {

    /** Take the characters typed since the last call, and empty the buffer. */
    fun drain(): String

    /** Discard whatever has accumulated, without reading it. */
    fun clear()

    companion object {
        val NONE: TypedText = object : TypedText {
            override fun drain(): String = ""
            override fun clear() {}
        }
    }
}
