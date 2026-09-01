package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.Vs
import dev.ziggle.vscript.nodes.library
import dev.ziggle.vscript.vm.StructValue

/**
 * A colour, for the tests that need one — [TileFixture]'s sibling and for its reasons.
 *
 * `Color` was `PinType.COLOR`, the last of the four builtins that were really a domain's. It is a data
 * `HostRecord` a drawing host declares now, so the language's own tests can no longer name one.
 *
 * It is here rather than folded into [TileFixture] because the two prove different things. A tile proves
 * a structured type can leave: fields, a literal form, a constructor spelling. A colour proves the two
 * capabilities a tile did not need — a **stored form that is not text** (a packed int) and a **written
 * form of its own** (`0xAARRGGBB`, rather than the record spelling a printer would otherwise reach for).
 */
object ColorFixture {

    val CHANNELS = listOf("r", "g", "b", "a")

    fun of(r: Int, g: Int, b: Int, a: Int): StructValue =
        StructValue("Color", CHANNELS, arrayOf<Any?>(r, g, b, a))

    /** From the packed int, the `#AARRGGBB` text, or one of these already. */
    fun read(value: Any?): StructValue? {
        if (value is StructValue) return value.takeIf { it.type == "Color" }
        val argb = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().removePrefix("#").toLongOrNull(16)?.toInt() ?: return null
            else -> return null
        }
        return of((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
    }

    fun argb(v: StructValue): Int {
        fun ch(n: String) = ((v.get(n) as? Number)?.toInt() ?: 0) and 0xFF
        return (ch("a") shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
    }

    val RECORD = HostRecord(
        "Color",
        CHANNELS.map { HostField(it, TypeRef(PinType.INT)) },
        "a colour",
        isData = true,
        read = { read(it) },
        // The half a tile has no need of: a colour is WRITTEN as its packed form, not as four fields.
        write = { v ->
            when (v) {
                is StructValue -> "0x%08X".format(argb(v))
                is Number -> "0x%08X".format(v.toInt())
                else -> null
            }
        },
    )

    val TYPE: TypeRef get() = RECORD.type

    fun register() = HostRecords.register(RECORD)

    val library = library("paint", "Paint") {
        record(RECORD)
        func("color") {
            title("Color")
            spelledAs("Color")
            doc("A colour by channel — `Color(244, 233, 122)`. Alpha defaults to opaque.")
            param("R", Vs.int)
            param("G", Vs.int)
            param("B", Vs.int)
            param("A", Vs.int, default = 255)
            result("Color", Vs.record<StructValue>(RECORD))
            construct()
        }
        func("toColor") {
            title("To Color")
            doc("A packed `0xAARRGGBB` int as a colour.")
            val packed = receiver("Value", Vs.int)
            val out = result("Color", Vs.record<StructValue>(RECORD))
            query { out.set(read(packed())) }
        }
    }

    /** What a test adds to its catalogue so `Color(…)` resolves. Registers the record — see TileFixture. */
    val DESCRIPTORS: List<dev.ziggle.vscript.model.NodeDescriptor>
        get() {
            register()
            return library.defs.map { it.descriptor }
        }
}
