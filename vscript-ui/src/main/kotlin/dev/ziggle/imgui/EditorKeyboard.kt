package dev.ziggle.imgui

/**
 * Who currently owns the keyboard.
 *
 * ### Why this exists at all
 *
 * ImGui has no notion of a focused widget for anything hand-drawn, and this editor draws nearly all of
 * its fields by hand. So typing has to be arbitrated explicitly: a text field claims the keyboard while
 * it is being edited, and everything with a single-key shortcut asks whether anyone holds it before
 * acting. Without that, typing `d` into a node's Label pin also triggers whatever `d` is bound to.
 *
 * ### Why it is in the widget kit
 *
 * It is sixteen lines with no dependencies — an owner reference and three verbs — and everything that
 * draws asks it: the canvas, the code view, the palette, the value picker, the panels, and the client's
 * own ImGui frame loop. Anywhere else it would be a thing every surface imports from one particular
 * surface, which is precisely the shape the split exists to remove.
 *
 * A single owner rather than a stack: two things holding the keyboard at once is not a state this editor
 * has, and a stack would make "who has it" a question with a list for an answer.
 */
object EditorKeyboard {

    var owner: Any? = null
        private set

    val busy: Boolean get() = owner != null

    fun claim(who: Any) {
        owner = who
    }

    /** Ignored unless [who] is the current owner, so a widget cannot release someone else's claim. */
    fun release(who: Any) {
        if (owner === who) owner = null
    }

    fun holds(who: Any): Boolean = owner === who
}
