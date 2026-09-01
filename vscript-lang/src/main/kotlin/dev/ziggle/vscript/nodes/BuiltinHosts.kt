package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.model.Templates
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.Values
import dev.ziggle.vscript.text.ASSERT_COMPARE_HOST
import dev.ziggle.vscript.text.ASSERT_HOST
import dev.ziggle.vscript.vm.VmError
import kotlin.math.pow

/**
 * The host functions the LANGUAGE itself needs — lists, text, time.
 *
 * **Not game verbs, and that is why they live here.** These back builtin nodes: `list.first`, `list.add`,
 * `value.format`, `value.toText`. They touch no avatar, need no actuator and know nothing about a client,
 * so a graph using them is runnable anywhere the VM is — which is what makes the language testable, and
 * what will make it embeddable once it is its own module.
 *
 * They used to live in `ScriptsPanel`, a UI class, purely because that is where the catalog happened to be
 * assembled. Nothing about them was ever editor-shaped.
 *
 * `vscript.log` is deliberately NOT here: it needs the runtime's own sink and its notion of which node is
 * executing, so `ScriptRuntime` registers it onto whatever registry it is handed.
 */
object BuiltinHosts {

    /**
     * The longest list `range` will build.
     *
     * A million ints is a few megabytes and no sane loop is anywhere near it, so this is high enough never
     * to be met by accident and low enough that meeting it costs a script error rather than the client.
     */
    private const val MAX_RANGE = 1_000_000L

    private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    /** The game's own markup, which arrives on any string read off a widget or a chat line. */
    private val MARKUP = Regex("<[^>]*>")

    /**
     * A string cleaned up enough to be a number, without being made into one.
     *
     * Markup, thousands separators and surrounding space are dropped because a widget hands all three out
     * and none changes what the number is. Nothing else is: whatever is left has to parse WHOLE, so
     * "12 of 20" stays a miss rather than quietly becoming 1220.
     */
    private fun numericText(v: Any?): String =
        v?.toString().orEmpty().replace(MARKUP, "").replace(",", "").trim()

    /**
     * The builtin hosts, with [files] backing the file verbs and [phase] answering Sleep Requested.
     *
     * [files] is defaulted to [FileStore.DENIED] rather than to a real filesystem: a host that never said
     * which files a script may touch has not granted it any, and the language is not the place to decide
     * otherwise. The client passes a store rooted at the script data folder; a test passes
     * [dev.ziggle.vscript.host.MemoryFiles].
     *
     * [phase] is defaulted to a fresh one, which never sleeps — the honest answer for a host that has no
     * notion of handing over. Registered HERE rather than by whatever owns the run, so that a language
     * test can drive the whole sleep protocol without one; `HostRegistry.register` refuses a duplicate
     * name, so there is exactly one place this may happen and this is it.
     */
    @JvmOverloads
    fun registry(
        files: dev.ziggle.vscript.host.FileStore = dev.ziggle.vscript.host.FileStore.DENIED,
        phase: dev.ziggle.vscript.host.RunPhase = dev.ziggle.vscript.host.RunPhase(),
        /**
         * Where "now" comes from, and therefore what a script's notion of *today* is.
         *
         * Injected for the same reason [phase] is: a script that decides anything by the calendar — a
         * daily budget, a bedtime, a task that comes due at a particular hour — cannot be tested at all
         * against the real clock. You would have to wait for midnight. A [java.time.Clock] rather than a
         * `() -> Long` because it carries its own zone, and every question below is a *local* one: the
         * day rolls over at the player's midnight, not at UTC's.
         */
        clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
    ): HostRegistry =
        HostRegistry()
        // ---- the run's own phase -----------------------------------------------------------------------
        //
        // INLINE, so it is answered on the spot with no actuator involved — which is what lets an
        // `on render` overlay read it to say "finishing up" while the loop is still deciding where to stop.
        .register("vscript.sleepRequested", HostKind.INLINE, arity = 0, results = 1) {
            phase.sleepRequested
        }
        // ---- JSON ------------------------------------------------------------------------------------
        //
        // Parsing and DECODING are two hosts, not one. The parse knows nothing about any record — it is
        // text in, nested maps and lists out — and the decode is handed a schema the compiler resolved
        // from the declaration. Fusing them would mean the parser needed the schema, and then `parseJson`
        // with no cast on it (perfectly legal: read a document, poke at it with map verbs) would have
        // nothing to pass.
        .register("vscript.jsonParse", HostKind.INLINE, arity = 1, results = 1) { args ->
            dev.ziggle.vscript.json.Json.parse(args.getOrNull(0)?.toString().orEmpty())
        }
        .register("vscript.jsonText", HostKind.INLINE, arity = 2, results = 1) { args ->
            dev.ziggle.vscript.json.Json.write(args.getOrNull(0), pretty = args.getOrNull(1) != false)
        }
        // The schema is the second argument and it is a CONSTANT the compiler put there — see
        // `JsonSchema`. Never null in emitted code; the check is here because a hand-built graph can
        // reach this host directly and "expected a number, got null" from inside a decoder is a worse
        // thing to debug than being told the schema is missing.
        .register("vscript.jsonDecode", HostKind.INLINE, arity = 2, results = 1) { args ->
            val schema = args.getOrNull(1) as? dev.ziggle.vscript.json.JsonSchema
                ?: throw VmError("this decode has no schema — `as` is what supplies one")
            schema.decode(args.getOrNull(0))
        }
        // ---- files -----------------------------------------------------------------------------------
        //
        // A missing file reads as NOTHING rather than throwing, on both readers. "Is it there" is a
        // question a script asks by trying, and a stop would make the ordinary first-run case — no saved
        // state yet — an error the author has to guard before every read. Bad CONTENT still throws:
        // `jsonParse` is called on the text and says where it gave up.
        .register("vscript.fileReadText", HostKind.INLINE, arity = 1, results = 1) { args ->
            files.read(pathOf(args.getOrNull(0)))
        }
        .register("vscript.fileReadJson", HostKind.INLINE, arity = 1, results = 1) { args ->
            files.read(pathOf(args.getOrNull(0)))?.let { dev.ziggle.vscript.json.Json.parse(it) }
        }
        .register("vscript.fileWriteText", HostKind.INLINE, arity = 2, results = 0) { args ->
            files.write(pathOf(args.getOrNull(0)), args.getOrNull(1)?.toString().orEmpty())
        }
        .register("vscript.fileWriteJson", HostKind.INLINE, arity = 3, results = 0) { args ->
            files.write(
                pathOf(args.getOrNull(0)),
                dev.ziggle.vscript.json.Json.write(args.getOrNull(1), pretty = args.getOrNull(2) != false),
            )
        }
        .register("vscript.fileExists", HostKind.INLINE, arity = 1, results = 1) { args ->
            files.exists(pathOf(args.getOrNull(0)))
        }
        .register("vscript.fileDelete", HostKind.INLINE, arity = 1, results = 1) { args ->
            files.delete(pathOf(args.getOrNull(0)))
        }
        // The one file verb that takes an empty path, because an empty FOLDER path is the data folder
        // itself and listing it is the obvious first thing a script does.
        .register("vscript.fileList", HostKind.INLINE, arity = 1, results = 1) { args ->
            ArrayList<Any?>(files.list(args.getOrNull(0)?.toString()?.trim().orEmpty()))
        }
        .register("vscript.fileFolders", HostKind.INLINE, arity = 1, results = 1) { args ->
            ArrayList<Any?>(files.folders(args.getOrNull(0)?.toString()?.trim().orEmpty()))
        }
        // ---- the clock -------------------------------------------------------------------------------
        //
        // All of these read [clock], so a test can hand the whole language a fixed instant and assert what
        // a script decides on a Tuesday at 23:50 without waiting for one.
        .register("vscript.nowMs", HostKind.INLINE, results = 1) { clock.millis() }
        // The local date as YYYYMMDD, so "is it still the same day" is integer arithmetic rather than
        // string handling — which is the whole reason it is not an ISO string. Ordering works too: a
        // later date is a larger number.
        .register("vscript.localDate", HostKind.INLINE, results = 1) {
            val d = java.time.LocalDate.now(clock)
            (d.year * 10_000L) + (d.monthValue * 100L) + d.dayOfMonth
        }
        .register("vscript.hourOfDay", HostKind.INLINE, results = 1) {
            java.time.LocalTime.now(clock).hour.toLong()
        }
        .register("vscript.minuteOfHour", HostKind.INLINE, results = 1) {
            java.time.LocalTime.now(clock).minute.toLong()
        }
        // ISO numbering: Monday is 1, Sunday is 7. Stated because the alternative convention (Sunday as 0)
        // is just as common and silently shifts every weekday comparison by one.
        .register("vscript.dayOfWeek", HostKind.INLINE, results = 1) {
            java.time.LocalDate.now(clock).dayOfWeek.value.toLong()
        }
        // Milliseconds until the local clock NEXT reads this time — tomorrow's if today's has gone. That
        // rollover is the point: "sleep until 23:40" asked at 23:50 means tonight is over, not that the
        // answer is negative and every comparison against it inverts.
        .register("vscript.msUntilLocal", HostKind.INLINE, arity = 2, results = 1) { args ->
            val now = java.time.LocalDateTime.now(clock)
            var target = now.toLocalDate().atTime(int(args.getOrNull(0)).coerceIn(0, 23), int(args.getOrNull(1)).coerceIn(0, 59))
            if (!target.isAfter(now)) target = target.plusDays(1)
            java.time.Duration.between(now, target).toMillis()
        }
        // Null renders as "null" rather than as nothing. This node's commonest use is a Log message,
        // and an empty string hides the very thing you would want to be told: the value never arrived.
        // Never returns. `results = 1` because the node has an output pin and the calling convention is
        // shaped by the descriptor, not by whether control comes back.
        .register("vscript.error", HostKind.INLINE, arity = 1, results = 1) { args ->
            throw VmError(args[0]?.toString() ?: "error()")
        }
        // ---- `assert`, inside a `test` -----------------------------------------------------------------
        //
        // **Two verbs, not one with a sentinel.** A comparison reports both sides and anything else reports
        // none, and "no sides" would otherwise need a value that means absence — which null does not,
        // because null is a perfectly ordinary thing to assert about (`assert next == null`).
        //
        // Reached only on the FAILING path: the compiler jumps over these when the check passed, so a green
        // suite never calls them and a message costs nothing to have.
        .register(ASSERT_HOST, HostKind.INLINE, arity = 1, results = 0) { args ->
            throw VmError("assertion failed: " + describeAssert(args.getOrNull(0)))
        }
        .register(ASSERT_COMPARE_HOST, HostKind.INLINE, arity = 3, results = 0) { args ->
            throw VmError(
                "assertion failed: " + describeAssert(args.getOrNull(0)) +
                    "\n  left:  " + show(args.getOrNull(1)) +
                    "\n  right: " + show(args.getOrNull(2)),
            )
        }
        // `Skill.of("farming")` — a name read back as a member.
        //
        // **Case-insensitive, and it answers the DECLARED spelling.** A name arriving from a chat line or
        // a saved file rarely carries the enum's own capitalisation, and a member that compared equal to
        // `Skill.Farming` only when it was typed exactly right would be a trap. Null when nothing matches,
        // which is the honest answer and is why the result is optional.
        .register("vscript.enumOf", HostKind.INLINE, arity = 2, results = 1) { args ->
            val members = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val wanted = args.getOrNull(1)?.toString()?.trim().orEmpty()
            members.firstOrNull { it?.toString().equals(wanted, ignoreCase = true) }
        }
        .register("vscript.toText", HostKind.INLINE, arity = 1, results = 1) { args ->
            args.getOrNull(0)?.toString() ?: "null"
        }
        // The way back. Null is the "not a number" answer and the pin is optional, so a miss is a case a
        // script handles rather than a zero it acts on.
        .register("vscript.parseInt", HostKind.INLINE, arity = 1, results = 1) { args ->
            numericText(args.getOrNull(0)).toLongOrNull()
        }
        .register("vscript.parseFloat", HostKind.INLINE, arity = 1, results = 1) { args ->
            numericText(args.getOrNull(0)).toDoubleOrNull()
        }
        // ---- text ---------------------------------------------------------------------------------------
        //
        // Case-SENSITIVE throughout. Every index is 0-based and every out-of-range one is CLAMPED rather
        // than thrown: these are usually fed by `text.indexOf`, whose "not found" answer is -1, so bad
        // bounds are the ordinary case and a VM error in the middle of a run is the worst way to say so.
        .register("vscript.textLength", HostKind.INLINE, arity = 1, results = 1) { args ->
            str(args.getOrNull(0)).length.toLong()
        }
        .register("vscript.textIndexOf", HostKind.INLINE, arity = 3, results = 1) { args ->
            val text = str(args.getOrNull(0))
            val from = int(args.getOrNull(2)).coerceIn(0, text.length)
            text.indexOf(str(args.getOrNull(1)), from).toLong()
        }
        .register("vscript.textLastIndexOf", HostKind.INLINE, arity = 2, results = 1) { args ->
            str(args.getOrNull(0)).lastIndexOf(str(args.getOrNull(1))).toLong()
        }
        .register("vscript.textSlice", HostKind.INLINE, arity = 3, results = 1) { args ->
            val text = str(args.getOrNull(0))
            val from = int(args.getOrNull(1)).coerceIn(0, text.length)
            val toRaw = int(args.getOrNull(2))
            val to = (if (toRaw < 0) text.length else toRaw).coerceIn(0, text.length)
            if (from >= to) "" else text.substring(from, to)
        }
        // Nothing, not "", when a marker is absent — see the node's own doc. An empty [After] anchors at
        // the start and an empty [Before] runs to the end, which is what makes it a prefix/suffix cutter
        // as well as a between.
        .register("vscript.textBetween", HostKind.INLINE, arity = 3, results = 1) { args ->
            val text = str(args.getOrNull(0))
            val after = str(args.getOrNull(1))
            val before = str(args.getOrNull(2))
            val start = if (after.isEmpty()) 0 else {
                val at = text.indexOf(after)
                if (at < 0) -1 else at + after.length
            }
            when {
                start < 0 -> null
                before.isEmpty() -> text.substring(start)
                else -> text.indexOf(before, start).let { if (it < 0) null else text.substring(start, it) }
            }
        }
        .register("vscript.textTrim", HostKind.INLINE, arity = 1, results = 1) { args ->
            str(args.getOrNull(0)).trim()
        }
        .register("vscript.textReplace", HostKind.INLINE, arity = 3, results = 1) { args ->
            // Plain text, never a pattern — `String.replace(String, String)` is literal in Kotlin.
            str(args.getOrNull(0)).replace(str(args.getOrNull(1)), str(args.getOrNull(2)))
        }
        // An empty separator would split into single characters in Kotlin and loop forever in most
        // people's heads; the whole string as one part is the answer that cannot surprise anyone.
        .register("vscript.textSplit", HostKind.INLINE, arity = 2, results = 1) { args ->
            val text = str(args.getOrNull(0))
            val on = str(args.getOrNull(1))
            if (on.isEmpty()) listOf(text) else text.split(on)
        }
        .register("vscript.textJoin", HostKind.INLINE, arity = 2, results = 1) { args ->
            val parts = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            parts.joinToString(str(args.getOrNull(1))) { str(it) }
        }
        .register("vscript.textStartsWith", HostKind.INLINE, arity = 2, results = 1) { args ->
            str(args.getOrNull(0)).startsWith(str(args.getOrNull(1)))
        }
        .register("vscript.textEndsWith", HostKind.INLINE, arity = 2, results = 1) { args ->
            str(args.getOrNull(0)).endsWith(str(args.getOrNull(1)))
        }
        .register("vscript.textUpper", HostKind.INLINE, arity = 1, results = 1) { args ->
            str(args.getOrNull(0)).uppercase()
        }
        .register("vscript.textLower", HostKind.INLINE, arity = 1, results = 1) { args ->
            str(args.getOrNull(0)).lowercase()
        }
        // A run of digits, optionally signed, with thousands commas allowed INSIDE it — the shape the game
        // writes numbers in. Commas come out before parsing so "1,234" is 1234 rather than a miss.
        .register("vscript.textNumberIn", HostKind.INLINE, arity = 2, results = 1) { args ->
            val skip = int(args.getOrNull(1)).coerceAtLeast(0)
            NUMBER_IN.findAll(str(args.getOrNull(0)))
                .drop(skip)
                .firstOrNull()
                ?.value
                ?.replace(",", "")
                ?.toLongOrNull()
        }
        // `x is Int`, and `x is Point`. The RUN-TIME form only — see BuiltinNodes.IS_TYPE for why that
        // is the whole of it. Int and Long are both INT to the language and Float and Double are both
        // FLOAT, so the comparison is against the kind rather than the class; a record answers to the
        // name its own document gave it, which is what makes `p is Point` and `t is Tile` work.
        .register("vscript.isType", HostKind.INLINE, arity = 3, results = 1) { args ->
            val value = args.getOrNull(0)
            val wanted = args.getOrNull(1)?.toString()?.trim().orEmpty()
            val negate = args.getOrNull(2) == true
            val actual = when (value) {
                null -> ""
                is Int, is Long -> "Int"
                is Double, is Float -> "Float"
                is Boolean -> "Bool"
                is String -> "String"
                is List<*> -> "List"
                is dev.ziggle.vscript.vm.StructValue -> value.type
                else -> value.javaClass.simpleName
            }
            actual.equals(wanted, ignoreCase = true) != negate
        }
        // The list reads treat null as empty, matching the opcodes: null is what an unconnected List
        // pin supplies, and "nothing plugged in holds nothing" is the answer an author expects. See
        // the note at Op.APPEND — the writes stay strict for the same reason these are lenient.
        // The conversions. Every one hands back a **Long** rather than an Int, because `PinType.INT` is
        // the language's one integer type and it is Long-wide everywhere else — `now()` alone returns
        // milliseconds that overflow an Int by three orders of magnitude, and `toInt(now())` saturating
        // silently to 2147483647 is precisely the class of bug an explicit cast is supposed to end.
        .register("vscript.floor", HostKind.INLINE, arity = 1, results = 1) { args ->
            kotlin.math.floor(Values.toDouble(args.getOrNull(0))).toLong()
        }
        .register("vscript.ceil", HostKind.INLINE, arity = 1, results = 1) { args ->
            kotlin.math.ceil(Values.toDouble(args.getOrNull(0))).toLong()
        }
        // Ties away from zero, spelled out because neither JDK primitive does it: `Math.round` is half
        // toward positive infinity (so -2.5 gives -2, which reads as a bug beside round(2.5) == 3) and
        // `kotlin.math.round` is `rint`, which is half-to-even (so 2.5 gives 2).
        .register("vscript.round", HostKind.INLINE, arity = 1, results = 1) { args ->
            val v = Values.toDouble(args.getOrNull(0))
            if (v >= 0) kotlin.math.floor(v + 0.5).toLong() else kotlin.math.ceil(v - 0.5).toLong()
        }
        .register("vscript.toInt", HostKind.INLINE, arity = 1, results = 1) { args ->
            Values.toLong(args.getOrNull(0))
        }
        .register("vscript.toFloat", HostKind.INLINE, arity = 1, results = 1) { args ->
            Values.toDouble(args.getOrNull(0))
        }
        .register("vscript.listFirst", HostKind.INLINE, arity = 1, results = 1) { args ->
            (args.getOrNull(0) as? List<*>)?.firstOrNull()
        }
        // A copy, never an edit in place: the list handed in may be a variable's value that other
        // nodes are reading this same pass, and mutating it would change what they see with nothing in
        // the flow to show for it. Out-of-range is a no-op rather than a stop, so a loop over one list
        // can safely write into a shorter one.
        //
        // The no-op path copies too — see [ALLOCATING] in `AppendPass`. Handing the argument straight
        // back is indistinguishable from a copy while nothing mutates a list, and it stopped being so
        // the moment the append pass began emitting `Op.APPEND` in place: a candidate initialised from
        // an identity-returning verb would alias whatever was passed IN, which is exactly the aliasing
        // the pass exists to refuse. Every list verb allocates, without exceptions to remember.
        .register("vscript.listSet", HostKind.INLINE, arity = 3, results = 1) { args ->
            val list = (args.getOrNull(0) as? List<*>) ?: emptyList<Any?>()
            val i = (args.getOrNull(1) as? Number)?.toInt() ?: -1
            list.toMutableList().also { if (i >= 0 && i < it.size) it[i] = args.getOrNull(2) }
        }
        // Appending is the one list edit that changes its LENGTH, so it cannot be expressed through
        // listSet at all — that one deliberately ignores an out-of-range index. Same copy-not-mutate
        // rule: the caller keeps the result or keeps nothing.
        .register("vscript.listAdd", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = (args.getOrNull(0) as? List<*>) ?: emptyList<Any?>()
            list + listOf(args.getOrNull(1))
        }
        // The MUTATING three. Every list in a running script is a real MutableList — `Op.APPEND` has always
        // written into one — so these edit what they are handed rather than allocating beside it. That is the
        // whole point of them, and why they are IMPURE nodes on an exec wire: the edit is visible in the flow.
        //
        // A list that is not mutable (a literal the compiler folded, a host that handed back a fixed list) is
        // left alone rather than crashing the script: a graph gathering things into the wrong list should
        // gather nothing, not stop the world.
        .register("vscript.listAppend", HostKind.INLINE, arity = 2, results = 0) { args ->
            (args.getOrNull(0) as? MutableList<Any?>)?.add(args.getOrNull(1))
        }
        .register("vscript.listClear", HostKind.INLINE, arity = 1, results = 0) { args ->
            (args.getOrNull(0) as? MutableList<Any?>)?.clear()
        }
        .register("vscript.listDeleteAt", HostKind.INLINE, arity = 2, results = 0) { args ->
            val list = args.getOrNull(0) as? MutableList<Any?>
            val i = (args.getOrNull(1) as? Number)?.toInt() ?: -1
            if (list != null && i in list.indices) list.removeAt(i)
        }
        // ---- maps ------------------------------------------------------------------------------------
        //
        // Every one ALLOCATES, unconditionally, including on the no-op paths. That is not defensive
        // tidiness: `AppendPass`'s whole argument is "every map verb hands back a copy", and a host that
        // returns its own argument on the path where nothing changed makes that false — the caller then
        // holds the same object the map came in as, and the next in-place write goes through to it. Three
        // LIST hosts had exactly that bug and it was invisible until `Op.APPEND` existed.
        //
        // `LinkedHashMap` and not `HashMap`: [MAP_KEYS] promises insertion order, and a map that reordered
        // itself would make two runs of the same script disagree about which entry a `for` reached first.
        .register("vscript.mapOf", HostKind.INLINE, arity = 0, results = 1) { LinkedHashMap<Any?, Any?>() }
        .register("vscript.mapWith", HostKind.INLINE, arity = 3, results = 1) { args ->
            LinkedHashMap(asMap(args.getOrNull(0))).also { it[args.getOrNull(1)] = args.getOrNull(2) }
        }
        .register("vscript.mapWithout", HostKind.INLINE, arity = 2, results = 1) { args ->
            // By the VM's own equality, so a key typed in as an Int matches a Long-returning node's value
            // — the same widening EQ does, and the same reason `listContains` does not use `equals`.
            val m = asMap(args.getOrNull(0))
            val wanted = args.getOrNull(1)
            LinkedHashMap<Any?, Any?>().also { out ->
                for ((k, v) in m) if (!Values.eq(k, wanted)) out[k] = v
            }
        }
        .register("vscript.mapAt", HostKind.INLINE, arity = 2, results = 1) { args ->
            val wanted = args.getOrNull(1)
            asMap(args.getOrNull(0)).entries.firstOrNull { Values.eq(it.key, wanted) }?.value
        }
        .register("vscript.mapHas", HostKind.INLINE, arity = 2, results = 1) { args ->
            val wanted = args.getOrNull(1)
            asMap(args.getOrNull(0)).keys.any { Values.eq(it, wanted) }
        }
        .register("vscript.mapSize", HostKind.INLINE, arity = 1, results = 1) { args ->
            asMap(args.getOrNull(0)).size
        }
        // Two-item lists rather than a pair type, because a list is something the VM already iterates and
        // indexes — so `for (k, v) in m` compiles to the ordinary list iterator plus two INDEX ops, and
        // costs no opcode and no new value kind.
        .register("vscript.mapEntries", HostKind.INLINE, arity = 1, results = 1) { args ->
            asMap(args.getOrNull(0)).map { (k, v) -> listOf(k, v) }
        }
        .register("vscript.mapKeys", HostKind.INLINE, arity = 1, results = 1) { args ->
            ArrayList<Any?>(asMap(args.getOrNull(0)).keys)
        }
        .register("vscript.mapValues", HostKind.INLINE, arity = 1, results = 1) { args ->
            ArrayList<Any?>(asMap(args.getOrNull(0)).values)
        }
        .register("vscript.listIsEmpty", HostKind.INLINE, arity = 1, results = 1) { args ->
            (args.getOrNull(0) as? List<*>).isNullOrEmpty()
        }
        // Compared with the VM's own equality, so an Int literal matches a Long-returning node's value
        // — the same widening EQ does. Kotlin's `contains` would use `equals` and quietly say no.
        .register("vscript.listContains", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val wanted = args.getOrNull(1)
            list.any { Values.eq(it, wanted) }
        }
        // The same widening equality `listContains` uses, for the same reason: an Int literal must match
        // the Long a node handed back, or the search says "not there" about something that is.
        .register("vscript.listIndexOf", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val wanted = args.getOrNull(1)
            list.indexOfFirst { Values.eq(it, wanted) }.toLong()
        }
        .register("vscript.listConcat", HostKind.INLINE, arity = 2, results = 1) { args ->
            val a = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val b = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
            a + b
        }
        .register("vscript.listReversed", HostKind.INLINE, arity = 1, results = 1) { args ->
            (args.getOrNull(0) as? List<*> ?: emptyList<Any?>()).reversed()
        }
        // Both clamp. `take` and `drop` are how a script slices a list it has not counted — "the three
        // nearest" — so a count larger than the list, or a negative one, has an obvious right answer and
        // stopping the script instead would make them unusable for the thing they are for.
        .register("vscript.listTake", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            list.take(Values.toInt(args.getOrNull(1) ?: 0).coerceAtLeast(0))
        }
        .register("vscript.listDrop", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            list.drop(Values.toInt(args.getOrNull(1) ?: 0).coerceAtLeast(0))
        }
        // Numbers only, checked rather than coerced: `Values.arith` concatenates when either side is a
        // String, so folding a list of names through it would quietly answer "0abc" instead of saying the
        // list was the wrong kind.
        .register("vscript.listSum", HostKind.INLINE, arity = 1, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            var total: Any = 0
            for (v in list) {
                if (v !is Number) throw VmError("sum needs a list of numbers, found ${Values.typeName(v)}")
                total = Values.arith(Op.ADD, total, v)
            }
            total
        }
        // Nothing when empty, like `listFirst` — "the smallest of nothing" has the same obvious answer as
        // "the first of nothing", and both are asked about lists a scene query may have come back empty on.
        .register("vscript.listMin", HostKind.INLINE, arity = 1, results = 1) { args ->
            (args.getOrNull(0) as? List<*>)?.reduceOrNull { a, b -> if (Values.compare(a, b) <= 0) a else b }
        }
        .register("vscript.listMax", HostKind.INLINE, arity = 1, results = 1) { args ->
            (args.getOrNull(0) as? List<*>)?.reduceOrNull { a, b -> if (Values.compare(a, b) >= 0) a else b }
        }
        // Both copy on the not-found / out-of-range path too, for the reason spelled out at `listSet`.
        .register("vscript.listRemove", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val wanted = args.getOrNull(1)
            val i = list.indexOfFirst { Values.eq(it, wanted) }
            list.toMutableList().also { if (i >= 0) it.removeAt(i) }
        }
        .register("vscript.listRemoveAt", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val i = (args.getOrNull(1) as? Number)?.toInt() ?: -1
            list.toMutableList().also { if (i >= 0 && i < it.size) it.removeAt(i) }
        }
        // Sorted by POSITION in a parallel list, so the sort is one host call over keys measured once.
        // Stable — `sortedWith` is — which is what lets a short key list leave the rest of the order alone
        // rather than scrambling it: an item with no key compares equal to every other item with no key.
        .register("vscript.listSortedBy", HostKind.INLINE, arity = 2, results = 1) { args ->
            val list = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val keys = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
            val n = keys.size
            list.indices.sortedWith { a, b ->
                val ka = a < n
                val kb = b < n
                when {
                    ka && kb -> Values.compare(keys[a], keys[b])
                    ka -> -1
                    kb -> 1
                    else -> 0
                }
            }.map { list[it] }
        }
        // The counted loop. Half-open, so `range(0, count(list: xs))` is exactly the valid indices, and
        // empty rather than an error when it ends before it starts — a count of 0 simply does not loop.
        //
        // Capped, and the cap is not paranoia: every other runaway in this VM is caught by the tick budget
        // because it runs instructions, and this one allocates the whole list inside a SINGLE instruction.
        // `range(0, count)` where `count` came back wrong would be an OutOfMemoryError taking the client
        // with it, rather than a script error naming the line.
        .register("vscript.range", HostKind.INLINE, arity = 2, results = 1) { args ->
            val from = Values.toLong(args.getOrNull(0) ?: 0)
            val to = Values.toLong(args.getOrNull(1) ?: 0)
            val size = to - from
            if (size > MAX_RANGE) {
                throw VmError("range($from, $to) is $size long — more than $MAX_RANGE is a mistake, not a loop")
            }
            if (size <= 0) emptyList<Any?>() else ArrayList<Any?>(size.toInt()).apply {
                // Int where it fits, like a literal — see `Lexer.narrow`. So `i` out of a range and `3`
                // written by hand are the same kind of thing, and neither reads differently in a log line.
                var v = from
                while (v < to) { add(if (v in INT_RANGE) v.toInt() else v); v++ }
            }
        }
        // ---- arithmetic the scripts kept writing by hand ----
        // Kind-preserving, like the arithmetic operators: `abs(-3)` is an Int, not 3.0. `core/math.vs`
        // spelled this `-1 * x`, which widened to Float and then needed a cast back.
        .register("vscript.abs", HostKind.INLINE, arity = 1, results = 1) { args ->
            when (val v = args.getOrNull(0)) {
                is Int -> kotlin.math.abs(v)
                is Long -> kotlin.math.abs(v)
                is Double -> kotlin.math.abs(v)
                else -> throw VmError("abs needs a number, got ${Values.typeName(v)}")
            }
        }
        // The OPERAND is handed back rather than a converted copy, so these keep their argument's kind for
        // free and work on the Strings `Values.compare` already orders.
        .register("vscript.min", HostKind.INLINE, arity = 2, results = 1) { args ->
            val a = args.getOrNull(0)
            val b = args.getOrNull(1)
            if (Values.compare(a, b) <= 0) a else b
        }
        .register("vscript.max", HostKind.INLINE, arity = 2, results = 1) { args ->
            val a = args.getOrNull(0)
            val b = args.getOrNull(1)
            if (Values.compare(a, b) >= 0) a else b
        }
        // 0 for a negative input rather than NaN. NaN is the honest answer and the useless one: it compares
        // false against everything, so it would propagate silently through a distance formula and surface as
        // a walk that never starts. `core/math.vs`'s hand-rolled sqrt made the same choice, and logged.
        .register("vscript.sqrt", HostKind.INLINE, arity = 1, results = 1) { args ->
            val v = Values.toDouble(args.getOrNull(0) ?: 0.0)
            if (v < 0.0) 0.0 else kotlin.math.sqrt(v)
        }
        .register("vscript.pow", HostKind.INLINE, arity = 2, results = 1) { args ->
            Values.toDouble(args.getOrNull(0) ?: 0.0).pow(Values.toDouble(args.getOrNull(1) ?: 0.0))
        }
        .register("vscript.sin", HostKind.INLINE, arity = 1, results = 1) { args ->
            kotlin.math.sin(Values.toDouble(args.getOrNull(0) ?: 0.0))
        }
        .register("vscript.cos", HostKind.INLINE, arity = 1, results = 1) { args ->
            kotlin.math.cos(Values.toDouble(args.getOrNull(0) ?: 0.0))
        }
        .register("vscript.pi", HostKind.INLINE, results = 1) { Math.PI }
        // One cell of an enum's table: (member names, that field's column, the member) -> the value.
        //
        // **A positional lookup, and it is sound because a member IS its name.** The compiler bakes both
        // lists in declaration order, so finding the name gives the row. Compared with `Values.eq` like
        // every other list read, so a member that arrived from somewhere Long-shaped still matches.
        //
        // A member the table does not know gives null rather than throwing. That is not leniency for its
        // own sake: an enum can gain a member while a document that holds one is mid-flight, and "no value
        // for that row" is the honest answer where stopping the script would be a guess about severity.
        .register("vscript.enumField", HostKind.INLINE, arity = 3, results = 1) { args ->
            val names = args.getOrNull(0) as? List<*> ?: emptyList<Any?>()
            val column = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
            val member = args.getOrNull(2)
            val i = names.indexOfFirst { Values.eq(it, member) }
            if (i < 0) null else column.getOrNull(i)
        }
        // Arity varies with the template, so there is no fixed number to declare. Nothing enforces
        // the field — the VM takes its argument count from the bytecode, which the compiler wrote from
        // the node's RESOLVED pins — so this says "it depends" rather than a number that would be a lie.
        .register("vscript.format", HostKind.INLINE, arity = -1, results = 1) { args ->
            val template = args.getOrNull(0)?.toString().orEmpty()
            // The names are re-derived from the template rather than passed alongside: one scanner,
            // so the pins the editor drew and the values substituted here cannot disagree.
            val names = Templates.placeholders(template)
            Templates.render(
                template,
                names.withIndex().associate { (i, n) -> n to args.getOrNull(i + 1) },
            )
        }
}

/**
 * A path argument, refused when it is empty.
 *
 * An unconnected Path pin is `""`, and an empty path is the script's data folder itself — so a `write` with
 * a forgotten pin would try to write a directory, and a `delete` with one would try to remove the whole
 * folder. Named here, once, rather than in five hosts or in every [dev.ziggle.vscript.host.FileStore].
 */
private fun pathOf(v: Any?): String =
    v?.toString()?.trim().orEmpty().ifEmpty { throw VmError("this file verb needs a path") }

/** A map argument, tolerating the null an unconnected Map pin produces — "nothing plugged in holds nothing". */
@Suppress("UNCHECKED_CAST")
private fun asMap(v: Any?): Map<Any?, Any?> = (v as? Map<Any?, Any?>) ?: emptyMap()

/**
 * A text argument.
 *
 * Null becomes `""`, not `"null"` — an unconnected STRING pin is null, and "nothing plugged in holds
 * nothing" is the rule the rest of these hosts already follow. `To Text` renders null as the word `null`
 * on purpose, because there the null IS the value being shown; here it is an absent argument.
 */
private fun str(v: Any?): String = if (v == null) "" else v.toString()

/** A whole-number argument, tolerating the null an unconnected pin gives and any Number the VM hands over. */
private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0

/**
 * A whole number as the game writes one: an optional minus, then digits with thousands commas allowed
 * INSIDE the run — see `vscript.textNumberIn`. The commas are stripped before parsing, so "1,234" is 1234.
 */
private val NUMBER_IN = Regex("-?\\d[\\d,]*")

/** What an `assert` was written as, or the author's own words when they gave some. */
private fun describeAssert(v: Any?): String = v?.toString()?.ifBlank { "this check" } ?: "this check"

/**
 * A value in a failure report — quoted when it is a string.
 *
 * Without the quotes `left: 3` is ambiguous between the number and the text, and an assert that compares a
 * name against a number is exactly the mistake the report exists to explain.
 */
private fun show(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"" + v + "\""
    else -> v.toString()
}
