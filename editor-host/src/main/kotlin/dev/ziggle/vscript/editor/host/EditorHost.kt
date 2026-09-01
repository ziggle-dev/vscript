package dev.ziggle.vscript.editor.host

/**
 * The services a host supplies to the editor, in one place.
 *
 * ### Installed, not injected — for now
 *
 * These are `var`s on an object rather than constructor parameters, and that is a deliberate waypoint
 * rather than the destination. The editor is thirty-odd files deep in places, and threading a host
 * through every panel, canvas, widget and picker that has ever wanted an icon is a large mechanical
 * change that would have to land in one commit to compile. Naming the seam first and installing it is
 * the same boundary with none of that churn: nothing in the editor names a client type any more, which
 * is the property that actually matters, and turning these into parameters afterwards is a refactor the
 * compiler can drive.
 *
 * It also matches what was already here — `EditorSprites.source` was exactly this shape, and this
 * generalises it rather than inventing a second idiom beside it.
 *
 * ### Defaults are a working configuration
 *
 * Every field starts at something that does nothing: no catalogues, no icons. An embedder that installs
 * none of it gets an editor with plain integer fields and no pictures, which is a real answer and not a
 * degraded one — it is what an embedder with a domain that has neither *should* see.
 */
object EditorHost {

    /** Which pin types have searchable values behind them. See [ValueCatalogs]. */
    @Volatile
    var values: ValueCatalogs = ValueCatalogs.NONE

    /**
     * Resolves the [IconRef]s [values] hands out.
     *
     * Separate from [values] because they are answered in different places: a catalogue is a data
     * question and can be answered off-thread from a file, while turning a picture into a texture needs
     * the GL context and therefore the thread that owns it.
     */
    @Volatile
    var icons: IconSource = IconSource { null }

    /**
     * Where typed characters come from. See [TypedText].
     *
     * Not derived from ImGui's own key state, because a keystroke is not a character until the platform
     * has had its say about layouts, dead keys and input methods.
     */
    @Volatile
    var typed: TypedText = TypedText.NONE

    /**
     * How a host's own types are drawn and edited. See [TypeStyles].
     *
     * Optional in the strongest sense: [deriveEditor] answers for every type without it, so a host that
     * installs nothing still gets a working canvas — catalogue pickers where it declared catalogues,
     * field boxes for its small records, and a stable derived hue for everything else.
     */
    @Volatile
    var styles: TypeStyles = TypeStyles.NONE
}
