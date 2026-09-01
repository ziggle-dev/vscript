package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.Vs
import dev.ziggle.vscript.nodes.library

/**
 * A second domain, so "domain-agnostic" is a property rather than a claim.
 *
 * ### Why a greenhouse
 *
 * This module is a language. It is supposed to be able to drive anything that registers a native surface,
 * and today exactly one thing does — a game — which means every assumption that leaked from that game
 * into the language typechecks perfectly and nobody finds out until a second host tries. A registration
 * API with one consumer is a registration API in name only.
 *
 * So: greenhouses. Deliberately nothing to do with a game, deliberately not a thin renaming of one —
 * benches and vents and humidity, with a unit (`Bench`) that has structure, a closed vocabulary
 * (`Season`) and a value the host hands back that the language never allocated. If the language can drive
 * this without knowing what a bench is, it can drive anything.
 *
 * ### What it exercises, and why each piece is here
 *
 * - **[Season]** — a [HostEnum]. Proves a domain can add a closed set of names the language will
 *   typecheck, complete and put in the manifest without the language knowing the names.
 * - **[Bench]** — a [HostRecord] whose fields are **live reads**, not a snapshot. `humidity` is a
 *   question about the world right now; the point is that the value travelling through the VM is the
 *   host's own object and a field read calls back into the host.
 * - **[greenhouse]** — the node library: a query, a command and a blocking action, which are the three
 *   shapes a node can have.
 *
 * It is a *fixture*, not a toy: [GreenhouseDomainTest] runs the real catalogue, the real validator and
 * the real text front end against it.
 */
object Greenhouse {

    /** The host's own unit. The language never constructs one and cannot look inside it. */
    class BenchHandle(val id: Int, var humidity: Int, val season: String)

    /** A world the host owns; a test drives it directly to prove the field reads are live. */
    val benches = mutableListOf(
        BenchHandle(1, humidity = 40, season = "Spring"),
        BenchHandle(2, humidity = 75, season = "Summer"),
    )

    /** Watered benches, in order — the observable effect of the command and action nodes. */
    val watered = mutableListOf<Int>()

    /** What a script asked to be recorded. The fixture's own, so it borrows nothing from the builtins. */
    val said = mutableListOf<String>()

    fun reset() {
        benches.clear()
        benches += BenchHandle(1, humidity = 40, season = "Spring")
        benches += BenchHandle(2, humidity = 75, season = "Summer")
        watered.clear()
        said.clear()
    }

    /**
     * A closed vocabulary the host owns.
     *
     * Note what is NOT here: any mention of this enum inside the language. `Skill` used to be seeded
     * from a 24-name constant in `model/GameValues.kt` — the same shape of thing declared in the wrong
     * place. That file is gone; the node pack declares `Skill`, and this fixture is what proved a second
     * domain could do the same. See [GreenhouseDomainTest].
     */
    val Season = HostEnum(
        "Season",
        listOf("Spring", "Summer", "Autumn", "Winter"),
        describe = "The growing season a bench is running.",
    )

    /**
     * A structured value the host provides.
     *
     * [HostField.get] is the whole implementation and it reads through to the live handle, so a script
     * that stores a bench and reads `humidity` twice across a wait sees the second value — which is the
     * behaviour a snapshot would quietly get wrong.
     */
    val Bench = HostRecord(
        "Bench",
        listOf(
            HostField("id", TypeRef(dev.ziggle.vscript.model.PinType.INT), "Which bench.") {
                (it as BenchHandle).id.toLong()
            },
            HostField("humidity", TypeRef(dev.ziggle.vscript.model.PinType.INT), "Right now, not when it was found.") {
                (it as BenchHandle).humidity.toLong()
            },
            HostField("season", Season.type, "The bench's season.") {
                (it as BenchHandle).season
            },
        ),
        describe = "One growing bench in the greenhouse.",
    )

    /** The library a host installs. Three node shapes, one enum, one record. */
    val library = library("green", "Greenhouse") {
        enum(Season)
        record(Bench)

        func("benches") {
            title("All Benches")
            doc("Every bench in the greenhouse, driest first.")
            // `LIST<Bench>`, not a bare LIST. Declared loosely first, and the front end was right to
            // refuse `benches()[0].humidity` -- an element of an unstated list is WILDCARD and has no
            // fields. `VsType.list()` carries the element decoder through, which is what makes the
            // subscript typed.
            val out = result("Benches", Vs.record<BenchHandle>(Bench).list())
            query { out.set(benches.sortedBy { it.humidity }) }
        }

        func("humidityOf") {
            title("Humidity Of")
            val bench = param("Bench", Vs.record<BenchHandle>(Bench))
            val out = result("Humidity", Vs.int)
            query { out.set((bench().let { it?.humidity ?: 0 }).toLong()) }
        }

        func("water") {
            title("Water Bench")
            doc("Raises a bench's humidity. Idempotent it is not — this is the point.")
            val bench = param("Bench", Vs.record<BenchHandle>(Bench))
            val amount = param("Amount", Vs.int, default = 10L)
            command {
                val b = bench() ?: return@command null
                b.humidity += (amount() ?: 0L).toInt()
                watered += b.id
                null
            }
        }

        func("waitForDry") {
            title("Wait For Dry")
            doc("Blocks until a bench falls below a humidity. The blocking node shape.")
            val bench = param("Bench", Vs.record<BenchHandle>(Bench))
            val below = param("Below", Vs.int, default = 50L)
            action {
                val b = bench() ?: return@action null
                b.humidity = ((below() ?: 0L).toInt() - 1).coerceAtLeast(0)
                null
            }
        }

        func("benchAt") {
            title("Bench At")
            doc("The bench with this number — written on the number, as a conversion should be.")
            // Declared through the DSL rather than hand-built: this is the path a real domain takes, and
            // it is what replaces a language intrinsic like `toItem`.
            val n = receiver("Value", Vs.int)
            val out = result("Bench", Vs.record<BenchHandle>(Bench))
            query { out.set(benches.getOrNull(((n() ?: 0L).toInt() - 1)) ) }
        }

        // Two verbs of the SAME NAME on DIFFERENT receivers -- `isDry` on a bench and on a number. A
        // document extension may do this; the question is whether the node catalogue's global
        // short-name rule allows a host to.
        func("isDry") {
            title("Bench Is Dry")
            val b = receiver("Bench", Vs.record<BenchHandle>(Bench))
            val out = result("Dry", Vs.bool)
            query { out.set((b()?.humidity ?: 0) < 50) }
        }

        func("asBenchNumber") {
            title("As Bench Number")
            doc("An INT read as a bench number. Written, never executed — see cast().")
            val n = receiver("Value", Vs.int)
            result("Number", Vs.int)
            cast()
        }

        func("say") {
            title("Say")
            doc("Records a line, so a test can see what a script did.")
            val text = param("Text", Vs.string, default = "")
            command { said += text().orEmpty(); null }
        }

        func("seasonName") {
            title("Season Name")
            val s = param("Season", Vs.enum(Season), default = "Spring")
            val out = result("Name", Vs.string)
            query { out.set(s().orEmpty()) }
        }
    }

    /**
     * A SECOND library whose `isDry` collides with the greenhouse's on the short name.
     *
     * Two domains, or two areas of one domain, may reasonably each have an `isDry` on their own type —
     * `green.isDry` on a Bench and `num.isDry` on an INT. A document extension may do exactly this. The
     * question this fixture asks is whether the node catalogue's GLOBAL short-name rule lets a host.
     */
    val numbers = library("num", "Numbers") {
        func("isDry") {
            title("Humidity Is Dry")
            val n = receiver("Value", Vs.int)
            val out = result("Dry", Vs.bool)
            query { out.set((n() ?: 0L) < 50L) }
        }
    }
}
