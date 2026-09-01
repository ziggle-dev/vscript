# The vs language

A small scripting language for driving a host application.

This is the reference for **writing a script**: every construct, what it means, and the vocabulary that
comes with the language. It says nothing about how a document is stored, printed or compiled — for that see
`CANONICAL_FORM.md`, and for what the language cannot do yet see `GAPS.md`.

Every example below is compiled by `DocsTest` as part of the build. If one of them is wrong, the build
fails.

---

## 1. A first script

```vs
graph "greeter"

var Greeted: INT = 0

on start {
    log(message: "hello")
    Greeted = Greeted + 1
}
```

Three things are already visible and are worth naming.

**A file declares its own name.** `graph "greeter"` is what the document is called; the file name is not.
Imports resolve by that name, so renaming the file changes nothing.

**`on start` is where a run begins.** A script is not a top-to-bottom program: declarations may appear in
any order, and only the bodies of `on` blocks run.

**Arguments are labelled.** `log(message: …)` names the pin it fills. Labels may be left off where the order
is obvious, but they survive a node gaining a parameter — write them.

---

## 2. Documents and imports

A document is one `.vs` file. It may declare types, variables, constants, functions and event handlers,
and it may import other documents.

```vs
graph "geometry"

export type Point { x: INT, y: INT }

/** Straight-line distance in the larger axis — the number of diagonal steps between two squares. */
export fn chebyshev(a: Point, b: Point) -> INT =
    max(a: abs(value: a.x - b.x), b: abs(value: a.y - b.y))

/** No `export`: this document's own business, and nobody else can name it. */
fn clamp(n: INT) -> INT = max(a: 0, b: n)
```

**Nothing crosses a boundary unless it says `export`.** TypeScript's rule, and for TypeScript's reason: a
document's public surface should be a decision somebody made rather than everything they happened to
write. The polarity used to be the other way, with `private` opting out — which in practice meant 135 of
143 declarations in this project's own library folder were public without anyone having chosen that.

Another document reaches it by name:

```vs
graph "walker"

import * as geo from "geometry"

var Steps: INT = 0

on start {
    val here = geo::Point { x: 0, y: 0 }
    val there = geo::Point { x: 3, y: 7 }
    Steps = geo::chebyshev(a: here, b: there)
}
```

**A namespace import carries its name at every use.** `geo::chebyshev`, `geo::Point`. That is not
ceremony — it is why the namespace form needs no collision rules at all. There is no shadowing question,
no "which `chebyshev` did you mean", and no significance to import order.

The one exception is an **extension function**, which an import brings in unqualified whatever form was
written — see §4.8.

### The import forms

```vs
graph "unqualified"

import { chebyshev as distance, Point } from "geometry"

var Steps: INT = 0

on start {
    val here = Point { x: 0, y: 0 }
    val there = Point { x: 3, y: 7 }
    Steps = distance(a: here, b: there)
}
```

| Form | What it brings in |
|---|---|
| `import * as geo from "geometry"` | nothing locally — every name is written `geo::x` |
| `import { a, b } from "geometry"` | exactly `a` and `b`, under their own spelling |
| `import { a as x } from "geometry"` | `a`, called `x` here — and **not** under `a` |
| `import run from "geometry"` | the document's `export default`, under whatever you call it |
| `import run, { a } from "geometry"` | the default and a named list |
| `import run, * as geo from "geometry"` | the default and a namespace |
| `import "geometry"` | **everything it exports**, under its own name — see below |

**`import "core/list"` is the one form that names nothing.** It exists because the utility documents are
entirely EXTENSIONS — `core/list` declares eleven verbs and no plain functions at all — and an extension
has to be asked for by name (§4.8), so using a list library meant a list of eleven words that told a reader
nothing:

```vs
graph "list-verbs"

export fn LIST<T>.second(self) -> T = self[1]

export fn LIST<T>.lastOne(self) -> T = self[_listCount(list: self) - 1]
```

```vs
graph "star"

import "list-verbs"

var Total: INT = 0

on start {
    Total = [1, 2, 3].second() + [1, 2, 3].lastOne()
}
```

It plays by a different collision rule from the forms above, and deliberately:

- **It never takes a name that is already spoken for.** A node, an explicit import, or this document's own
  declaration keeps its meaning and the name simply does not arrive — no error, because one unlucky word
  (`contains` is a node) would otherwise poison a line wanted for the other twenty.
- **Two of them offering one name bind neither**, and that is reported where the name is USED, naming both
  documents and the named import that settles it. At the import there is nothing wrong yet.

That is `import java.util.*`'s rule, for its reason. The forms that NAME something keep the stricter one
below, because there you did say the word.

**A collision is an error, at the import, whether or not the name is used.** That is the same bargain the
mandatory alias always made: a name in this language cannot quietly mean two things. Three cases, and the
difference between them is what you can see by reading the file:

- **Another import offers it too** — an error. Neither has a better claim.
- **A node is already called that** — an error. The catalogue is a vocabulary nobody wrote down in your
  file, so a silent capture there is the one you could not have found by reading.
- **This document declares it** — a **warning**, and your own declaration wins, as an innermost scope does
  everywhere. The library's is still reachable by renaming it.

Each is cured the same way — say which one you meant:

```
'half' is already imported from "math" — rename one of them:
'import {half as <name>} from "more"'
```

In the IDE that error carries an Alt+Enter fix that renames every colliding name in the import at once.

**Reach for the namespace when a document IS one** — `banking::withdraw` reads better than `withdraw` and
says where it came from. Reach for the named form when a document is a bag of helpers you use constantly.

### Re-exporting

A document may pass names straight on, which is how a folder gets one front door:

```vs
graph "core"

export * from "geometry"
export { chebyshev as distance } from "geometry"
```

| Form | What it does |
|---|---|
| `export * from "y"` | everything `y` offers, under its own spelling |
| `export { a, b as c } from "y"` | exactly those, `b` renamed to `c` on the way through |
| `export { default as x } from "y"` | `y`'s default, under the name `x` |
| `export { a, b }` | this document's OWN `a` and `b`, said in one place instead of on each |

**A re-export is not an import followed by an export.** The names go *past* the document rather than into
it, so a barrel cannot call what it forwards:

```
nothing here is called 'twice'
```

That is TypeScript's rule and it is the one that keeps a barrel from quietly acquiring a vocabulary. It
also means a barrel costs nothing at run time — a call through one resolves straight to the document that
really declares the function.

**Chains and rings are fine.** A barrel may re-export a barrel, and two documents may re-export each other;
the export map is walked with a guard rather than assumed to be a tree.

**Two `export *` offering one name is an error**, not last-wins:

```
'twice' is offered by two of the documents this one re-exports — say which with
'export { twice } from "…"', or stop forwarding one of them
```

Ambiguity is refused for the same reason the unqualified import forms refuse it — a name here does not
quietly mean two things — and the reason it matters more for `export *` is that nobody wrote the name
down: a barrel acquires the collision when a library it forwards grows a symbol, and the file that breaks
is not the file that changed. A local declaration wins over a `*` without complaint, which is what makes a
barrel safe to add to.

### A type is its declaration, not its spelling

**A record is the same type wherever it is reached from, however many documents it crossed to get there,
and by however many routes.** You do not have to know what your imports import.

<!-- text -->
```vs
graph "geo"

export type Point { x: Int = 0, y: Int = 0 }
```

<!-- text -->
```vs
graph "mid"

import { Point } from "geo"

export type Leg { from: Point = Point { }, to: Point = Point { } }
```

<!-- text -->
```vs
graph "probe"

import { Leg } from "mid"

fn startX(l: Leg) -> Int = l.from.x
```

`probe` never imports `geo` and never needs to. `Leg.from` is a `Point` — *that* `Point`, the one `geo`
declares — and reading `.x` off it works.

This was not always true and the workarounds are still visible in older scripts, so it is worth being
plain about what changed. A type used to travel as a NAME that meant something only relative to the
document reading it, and that failed in both directions at once:

- **One type could become none.** The example above reported `Point has no fields`, because `probe` had no
  spelling for `geo`. Anything published widely therefore had to be strings and numbers — see the note at
  the head of `scheduler/goal.vs`, which called it "not optional".
- **Two types could silently become one.** Two documents each declaring a different `Point` were
  interchangeable, so a function taking one would happily read a field off the other.

Both are gone. A type now carries the module that declared it, decided once at the declaration and never
re-derived from whoever is asking, so:

- a record may hold a record from any document, at any depth, and an importer three hops away can read
  through it;
- two documents may each declare a `Config` and they are **two types**, refused for each other with a
  message naming both;
- importing one name by two routes — directly and through a barrel — is one type, and the two wire
  together.

**What is still refused is an import CYCLE**: two documents that need each other. That is a different
problem — it is about what order initialisers run in, not about what a name means — and a shared
vocabulary still has to move into a third document both import.

### Where a document lives

**A document is named by its file.** The folders below the scripts root are the package and the file is
the name, exactly as a class relates to its directory:

```
scripts/core/random.vs        is   core/random
scripts/hunter/falconry.vs    is   hunter/falconry
```

So `import * as rand from "core/random"` finds the first. Nothing is written twice, moving a folder
re-qualifies every document in it at once, and renaming a file renames the document — which is the point,
because those are the same act.

The reference is a string, so a file name with a space in it resolves (`import "my notes"`). It is not the
convention and nothing stops you.

#### `mod.vs` — a folder's front door

**A file called `mod.vs` is named by its FOLDER**, so `core/loadout/mod.vs` is imported as `core/loadout`:

```
scripts/core/loadout/mod.vs        is   core/loadout
scripts/core/loadout/wants.vs      is   core/loadout/wants
scripts/core/loadout/carrier.vs    is   core/loadout/carrier
```

That is `index.ts`, and `mod.rs`, for their reason. A barrel's whole job is to be the one name a caller
has to know, and `core/loadout/mod` would mean the caller has to know the folder *and* the convention. Put
the re-exports in it and the folder has a front door:

```
// scripts/core/loadout/mod.vs
export * from "core/loadout/wants"
export * from "core/loadout/carrier"
```

...and one import reaches everything behind it:

```
// anywhere else
import * as kit from "core/loadout"
```

Two rules go with it, and both exist to stop a convention becoming a lottery:

- **The bare name `mod` never resolves.** Every barrel in the tree would answer to it and the index is
  first-wins, so `import "mod"` would quietly mean whichever folder was scanned first.
- **An explicit sibling wins.** If both `core/loadout.vs` and `core/loadout/mod.vs` exist, `core/loadout`
  is the sibling — a file that spells the name exactly beats one that earns it by convention. Having both
  is worth cleaning up rather than relying on.

#### `graph "…"` is optional, and only means something without a file

A document may open with a name of its own:

```vs
graph "falconry"

export fn hood() -> INT = 1
```

For a `.vs` on disk this is **redundant** and can be left out — the file already said. It exists for a
document that has no file to be named by: a canvas document, an example in this reference, a snippet
compiled from memory. It was mandatory when text had to round-trip through the canvas, and the trap it
left behind was that a `falcon.vs` declaring `graph "falconry"` was imported as `hunter/falconry` and
`hunter/falcon` found nothing. A file with no header cannot disagree with itself.

Both spellings still resolve for a file that has one, so adding or removing the line breaks no import.


### Visibility

`export` on a `type`, `enum`, `var`, `val`, `single`, `const` or `fn` is what puts it within an importer's
reach. Without it, the declaration is this document's own business.

```vs
graph "counter"

var seed: INT = 7

/** The whole point of the document, and the only thing it offers. */
export fn next() -> INT = seed * 3

fn helper() -> INT = 1
```

Reaching a name that is there but unexported says exactly that, rather than sending you hunting for a
typo in a name that is spelled correctly:

```
'hidden' is declared in 'p' but not exported — add 'export' to that fn
```

**An entry takes `always`, not `export`.** An entry has no name for anyone to say, so there is nothing for
`export` to make sayable; the question is whether it RUNS for a document that imports this one.

```vs
graph "overlay"

/** Runs only when this document is the one being run — a debug overlay while building the library. */
on render {
    log(message: "building")
}

/** Runs however this document was reached, imports included. */
always on render {
    log(message: "shipping")
}
```

A document being run **directly** runs all of its own entries whatever they say; `always` decides only
what an importer inherits.

### Saying the surface in one place

`export` may also be written as a list, which lets a file read as code with its API on the last line:

```vs
graph "listed"

fn twice(n: INT) -> INT = n * 2

fn hidden() -> INT = 0

export { twice }
```

Alongside the declaration form or instead of it — a file may use both, and they add up. Saying it both
ways about one declaration is harmless; it is one fact, and prints once. No `as` here: renaming a name on
its way out of the document that declares it would give it two names in one file, and the error says so.

### `export default` — the one thing a document is

A document may name one declaration as its default, and an importer then calls it whatever it likes:

```vs
graph "method"

export default fn run(n: INT) -> INT = n * 2
```

```vs
graph "caller"

import twice from "method"

var Out: INT = 0

on start {
    Out = twice(n: 5)
}
```

Any declaration may be the default — a `type`, a `var`, an `enum`. There is one per document, because an
importer writing `import x from …` names one thing, and a second is refused where it is written.

**A default keeps its own name too.** `export default` adds a second way to say a declaration; it does not
take the first one away, so a document that imports the whole surface still sees it as itself:

```vs
graph "roster-source"

export default single Roster { size: INT = 3 }
```

```vs
graph "roster-reader"

import "roster-source"

var Size: INT = 0

on start { Size = Roster.size }
```

**...and a `type` or a `single` may have no name at all:**

```vs
graph "anon-source"

export default single { size: INT = 3 }
```

```vs
graph "anon-reader"

import Kit from "anon-source"

var Size: INT = 0

on start { Size = Kit.size }
```

Only those two. A `fn`, `var`, `val`, `const` or `enum` still needs a name, because those are reachable by
name from inside the document that declares them and an anonymous one could not be called, read or written.
A `type` and a `single` are a SHAPE, and a default export's shape is named by whoever imports it — so a name
on the declaration is a name nobody says, and one more thing to collide with. Which is the next paragraph.

**A declaration may not take a name an import already claims.** Two explicit claims on one name, and neither
has a better one:

```
'Hooks' is already imported from "core/activity" — rename one of them:
'import {Hooks as <name>} from "core/activity"'
```

A local declaration still wins over a bare `import "x"` or an `export *` without complaint — that is the
bargain that makes a barrel safe to add to. What is refused is the pair where somebody wrote *both* names
down. Unreported, it does not read as a collision at all: a `single Hooks` beside an `import { Hooks }`
shadowed the import silently, so a literal meant for the imported record was checked against the local one
and reported a missing field the author never mentioned — and worse across a boundary, since a value carries
its type as a *spelling* rather than an origin, so an importer resolving that single's type looked `Hooks`
up in its own scope and quietly found the other one. Dropping the name is usually the better fix than
renaming: that is what the anonymous form above is for.

**A default may also be a bundle**, and then it is a record you can hold:

```vs
graph "bundled"

export fn run(n: INT) -> INT = n * 2

export fn half(n: INT) -> INT = n / 2

export default { run, half }
```

```vs
graph "user"

import m from "bundled"

var Out: INT = 0

on start {
    Out = m.run(5) + m.half(4)
}
```

The bundle is a VALUE — it can be passed around and stored in a table — and that is what its one cost buys:
a function reached through a field is called **positionally**, `m.run(5)` rather than `m.run(n: 5)`, because
a function value carries the shape of its parameters and not their names. A named import keeps the labels.

### Cycles

**Import cycles are refused**, naming the path:

```
import cycle: a -> b -> a
```

A library that imports its own importer is not a library. The rule is at the DOCUMENT level, which is a
far simpler one than mutual recursion would need, and the way out of a genuine mutual dependency is to
move what both sides need into a third document they both import.

---

## 3. Values and types

| Type | What it holds | Written as |
|---|---|---|
| `INT` | a whole number (64-bit) | `42`, `-7` |
| `FLOAT` | a fractional number | `0.5`, `2.0` |
| `BOOL` | true or false | `true`, `false` |
| `STRING` | text | `"hello"` |
| `TILE` | a square in the world | `tile(3200, 3200, 0)` |
| `COLOR` | a colour, as four channels | `0xFF00FFFF` — and in text, `Color(0, 255, 255)` |
| `SKILL` | a skill, by name | `"Agility"` |
| `LIST<T>` | several of something, in order | `[1, 2, 3]` |
| `MAP<K, V>` | a lookup from one value to another | `_newMap()` |
| `fn(A) -> B` | a function that computes, as a value | the name of a function, uncalled |
| `fn(A) -> B` | a function that *does something*, as a value | the name of a function, uncalled |
| `ENTITY` `ITEM_REF` `WIDGET` | a reference to one particular thing | never written out — obtained from a node |
| a record | whatever you declared | `Point { x: 1, y: 2 }` |
| `T?` | a `T`, or nothing | `null` |

Type names are case-insensitive: `INT`, `Int` and `int` are the same type. The upper-case spelling is
conventional for the built-ins.

### 3.1 Records

```vs
graph "records"

type Point { x: INT, y: INT }

type Leg {
    from: Point,
    to: Point,
    label: STRING,
}

var Moved: INT = 0
var Label: STRING = ""

on start {
    val a = Point { x: 0, y: 0 }
    val b = Point { x: 10, y: 4 }
    val leg = Leg { from: a, to: b, label: "first" }

    // `with` gives a COPY with one field replaced. A record is never written through.
    val shifted = b with { x: 99 }

    Moved = shifted.x
    Label = leg.label
}
```

Records are **values**, not references: passing one to a function and changing a field there cannot be
observed by the caller, because `with` produced a copy.

**Field shorthand.** A bare name in a record literal means "the field of that name, from the variable of
that name" — `Vec2 { x, y }` is `Vec2 { x: x, y: y }`.

```vs
graph "shorthand"

type Vec2 { x: INT, y: INT }

var Out: INT = 0

on start {
    val x = 5
    val y = 3

    val v = Vec2 { x, y }
    val w = Vec2 { x, y: 9 }

    Out = v.y + w.y
}
```

#### `s.f = v` — the same thing, as a statement

A record held by a **mutable** name can have one field assigned:

```vs
graph "field-assign"

type Course { name: STRING, laps: INT }

var Run: Course = Course { name: "Draynor", laps: 0 }
var Seen: INT = 0

on start {
    Run.laps = Run.laps + 1

    var c = Course { name: "Canifis", laps: 0 }
    c.laps = 4
    Seen = c.laps
}
```

`Run.laps = 1` means exactly `Run = Run with { laps: 1 }`. Use whichever reads better: `with` is an
expression, so it can sit inside a larger one; the assignment is a statement that avoids repeating the name.

Nested targets rebuild outward, which is what value semantics means: `a.b.c = v` is
`a = a with { b: a.b with { c: v } }`, and it is written just like that.

**A parameter cannot be assigned:**

```
fn bump(c: Course) {
    c.laps = c.laps + 1
}
// 'c' is a `val`, which names a value once — write 'var c = …' instead if it has to change
```

That is the honest answer for a value type. Even if it compiled, `c` would be this call's own copy and the
caller would never see the change — a lap counter written that way would silently never count. Return the
new record, or keep the counter in a variable the function can reach.

#### `==` compares records field by field

Two records are equal when they are the same type and every field is equal.

```vs
graph "equality"

type Point { x: INT, y: INT }

var Same: BOOL = false

on start {
    val a = Point { x: 1, y: 2 }
    val b = Point { x: 1, y: 2 }

    Same = a == b
}
```

It nests, so a record holding records compares all the way down, and it covers `TILE` and `COLOR` for the
same reason they are records: `here == home` is the tile comparison, and there is no need for a helper that
reads `.x`, `.y` and `.plane` off both sides. Numbers inside a record compare across widths, so a `1`
written by hand equals a `1` a node returned.

### 3.2 Generic records

A record can be generic in one or more types:

```vs
graph "generic-records"

type Pair<A, B> { first: A, second: B }

type Box<T> { held: T, tries: INT }

fn firstOf(p: Pair<INT, STRING>) -> INT = p.first

on start {
    // The arguments are never written at a construction — the values say what they are, so this is a
    // `Pair<INT, STRING>` and `p.first` is an INT downstream.
    val p = Pair { first: 1, second: "a" }
    log(message: text("{n}", n: firstOf(p: p)))

    // A parameter may be bound to anything, containers included.
    val b = Box { held: [1, 2], tries: 0 }
    log(message: text("{n}", n: _listCount(list: b.held)))
}
```

- **The arguments are inferred where the record is BUILT**, from the field values. There is no
  `Pair<INT, STRING> { … }` spelling.
- **Generic records are erased.** The arguments are checked where the record is made and read, and are gone
  by run time — so `p is Pair` works and `p is Pair<INT, STRING>` is refused, as is `as` with arguments.

Unlike a function, a record **says** what its parameters are, so there is no spelling convention to follow:
`type Holder<Element> { … }` is as good as `type Holder<T> { … }`.

### 3.3 A host's record types

A host can register a **data record**: a type with named, typed fields whose values the language builds
and reads directly. It behaves as a record you declared yourself — `Name { … }` constructs one, `v.field`
reads one — while keeping its own identity, so a lookalike record cannot be wired where it belongs.

`Tile` and `Color` below are exactly that. Neither is a language type: a host node pack declares them,
which is why a document that imports nothing still sees them and a different host would see its own
instead. A host may also give one a **constructor spelling** — `tile(x, y, plane)` is the positional form
of `Tile { x: …, y: …, plane: … }`, and the two build the same value.

```vs
graph "tiles"

fn eastOf(spot: TILE, by: INT) -> TILE = Tile { x: spot.x + by, y: spot.y, plane: spot.plane }

fn describe(spot: TILE) -> STRING = "at " + spot.x + "," + spot.y + " on plane " + spot.plane

fn brightness(ink: COLOR) -> INT = ink.r + ink.g + ink.b

var Where: STRING = ""
var Bright: INT = 0
var X: INT = 0

on start {
    Where = describe(spot: eastOf(spot: tile(3200, 3210, 1), by: 5))
    Bright = brightness(ink: 0x80FF8040)

    // A literal bound to a local carries its type, so its fields are there too.
    val spot = tile(3200, 3210, 1)
    X = spot.x
}
```

`Tile` has `x`, `y`, `plane` — **`plane`, not `z`**: it is the game's word, and it is a floor rather than a
height. Two tiles with the same x and y on different planes are nowhere near each other. `Color` has `r`,
`g`, `b`, `a`, each 0–255.

`tile(x, y, plane)` is a literal spelling rather than a call, and it is the language's one reserved word —
which is why the drawing node has to be written `draw.tile` in full.

### 3.4 Lists

```vs
graph "lists"

var Total: INT = 0
var First: INT = 0

on start {
    val sizes = [2, 5, 1]

    First = sizes[0]
    Total = _listCount(list: sizes)

    for size in sizes {
        Total = Total + size
    }
}
```

A list carries what it holds — `LIST<INT>` is not `LIST<TILE>`, and wiring one into the other is refused.
The element type survives being taken apart, so `ps[0].x` and `for p in ps { p.x }` are both checked.

**A list is a value.** Every verb that changes one hands back a **copy** — the names say so — so an
accumulator is `xs = xs.add(value: v)`, never `xs.add(value: v)` on its own:

| Ask | | Make a new one | |
|---|---|---|---|
| `_listCount(list:)` | how many | `_listWithItemAdded(list:, value:)` | one more on the end |
| `_listIsEmpty(list:)` | nothing in it? | `_listWithItemAt(list:, index:, value:)` | one position replaced |
| `_listFirst(list:)` | the first, or nothing | `_listWithout(list:, value:)` | the first match gone |
| `xs[i]` | the item at a position | `_listWithoutAt(list:, index:)` | one position gone |
| `_listContains(list:, value:)` | is it in there | `_listConcat(list:, other:)` | both, end to end |
| `_listIndexOf(list:, value:)` | where, or `-1` | `_listReversed(list:)` | last first |
| `_listSum(list:)` | added together | `_listTake(list:, count:)` | the first few |
| `_listSmallest(list:)` `_listLargest(list:)` | the extremes | `_listDrop(list:, count:)` | all but the first few |
| | | `_listSortedBy(list:, keys:)` | ordered by a parallel key list |
| | | `range(from:, to:)` | the whole numbers between |

`sortedBy` takes a second list holding one number per item rather than a rule you write, which is what keeps
it cheap: measure each thing once into a parallel list, then sort by what you measured.

```vs
graph "verbs"

var Nearest: INT = 0

on start {
    val sizes = [7, 2, 9]
    val keys = [70, 20, 90]

    // Smallest key first, so the item measured 20 comes out in front.
    Nearest = _listFirst(list: _listSortedBy(list: sizes, keys: keys))
}
```

**Writing the accumulator the obvious way does not cost what it looks like it costs.** `withItemAdded` is a
copy in the language, and one instruction per item in practice — nothing needs hand-optimising around it.

An empty list literal takes its element type from where it is going, so a local that starts empty needs the
type written on it:

```vs
graph "empty-lists"

var Total: INT = 0

on start {
    var xs: LIST<INT> = []
    for i in range(from: 0, to: 4) {
        xs = _listWithItemAdded(list: xs, value: i)
    }
    Total = _listCount(list: xs)
}
```

### 3.5 Maps

A `MAP<K, V>` is a lookup, and it is a value exactly as a list is: every verb hands back a copy.

```vs
graph "maps"

var Counts: MAP<STRING, INT> = _newMap()
var Distinct: INT = 0
var Commonest: INT = 0

on start {
    for word in ["a", "b", "a"] {
        // `valueAt` is OPTIONAL — a key that was never set is the ordinary case, not a mistake.
        Counts = _mapWith(map: Counts, key: word, value: (_mapAt(map: Counts, key: word) ?: 0) + 1)
    }

    Distinct = _mapCount(map: Counts)
    Commonest = _mapAt(map: Counts, key: "a") ?: 0

    // Entries come back in the order they were first put in, key and value together.
    for (k, v) in Counts {
        log(message: k + " " + v)
    }
}
```

| | |
|---|---|
| `_newMap()` | a map with nothing in it |
| `_mapWith(map:, key:, value:)` | a copy with one entry set |
| `_mapWithout(map:, key:)` | a copy with one key taken out |
| `_mapAt(map:, key:)` | what a key maps to — **optional** |
| `_mapHas(map:, key:)` | whether the key is there at all |
| `_mapCount(map:)` | how many entries |
| `_mapKeys(map:)` `_mapValues(map:)` | every key / every value, in the same order |

**Walk one with `for (k, v) in m`** — one pass per entry, in insertion order, key and value together. It is
the same binder the list loop uses for element-and-index, because it is the same idea: the container says
what the two names are. Reach for `keysOf` only when the values are genuinely not wanted.

`valueAt` answering an optional is the whole ergonomic point: pair it with `?:` for a default, or with
`if val` when "there is no entry" is a case of its own. Ask `hasKey` when the *value* may legitimately be
nothing and you have to tell the two apart.

An empty map takes its types from where it is going, so — like a list — a local that starts empty writes
them out: `var seen: MAP<TILE, INT> = _newMap()`.

### 3.6 Functions as values

A function can be passed as a value, written as its **name with no call**:

```vs
graph "higher-order"

fn double(n: INT) -> INT = n * 2

fn big(n: INT) -> BOOL = n > 2

on start {
    // The three list verbs that take a function.
    val doubled = mapped(list: [1, 2, 3], using: double)      // LIST<INT> — [2, 4, 6]
    val some = filtered(list: [1, 2, 3], keeping: big)        // LIST<INT> — [3]
    val one = firstWhere(list: [1, 2, 3], matching: big) ?: 0 // stops at the match

    log(message: text("{n} doubled, {m} kept, first big {f}",
        n: _listCount(list: doubled), m: _listCount(list: some), f: one))
}
```

Its type is written `fn(A) -> B` — `fn(TILE) -> BOOL` is "something that answers a question about a tile" —
and it is an ordinary type: it goes in a parameter, in a result, in a `var`, and inside a `LIST` or a `MAP`
like anything else.

**Any function may be a value** — one written `= …` and one with a body of steps alike. There is one word
for both, and one shape: `fn(A) -> B`, or `fn(A)` when it hands nothing back.

There is a cost to knowing about, and it is not a rule the language enforces. `mapped`, `filtered` and
`firstWhere` are **expressions**, and an expression is worked out again at every place it is read. A
function that only computes gives the same answer each time, so that is invisible. One that *acts* would do
its work once per read — so if you pass a step-bodied function to one of those three, bind the result to a
`val` and read the name instead of the call.

Generics work through a function type the way they work everywhere else, and a function type can introduce
a variable of its own — which is how `map` says "whatever this hands back is what you get":

```vs
graph "mapping"

fn LIST<T>.map(self, f: fn(T) -> U) -> LIST<U> = mapped(list: self, using: f)

fn double(n: INT) -> INT = n * 2

var Out: INT = 0

on start {
    Out = _listCount(list: [1, 2, 3].map(f: double))
}
```

`T` comes from the receiver and `U` from the function's own type; both are bound at the call site from what
was actually passed.

### 3.7 `fn(T)` — a function that hands nothing back

A function that clicks, walks or waits often has no answer to give. Its type is `fn(T)` — the same word,
with the arrow left off, because there is nothing to point at:

```vs
graph "actions"

var Log: INT = 0

fn bump(by: INT) {
    Log = Log + by
}

fn handler() -> fn(INT) = bump

on start {
    val f = handler()
    f(2)                    // a name holds it, so it is called like any function
    invoke(bump, 3)         // `invoke` for when what you are calling is an expression
    invoke(handler(), 4)
}
```

Either way it is a **statement**, and the arguments are positional — a function value carries the shape of
its parameters and not their names, so there is nothing to label them with.

`f(2)` only means "call the value" when `f` holds a function. A name holding anything else still means the
function of that name, so nothing that used to resolve to a declaration stops doing so.

**There is one kind of function.** There were two for a while — `act(T)` for one with a body of steps,
`fn(T) -> U` for one that only computes — and the distinction rode in the type so that `mapped`, `filtered`
and `firstWhere` could refuse anything that acts. It bought one real thing (see the note in §3.6 about
binding their result to a `val`) and cost two spellings of "function" in every signature that carried one.
The word is `fn` either way now, and the arrow is what varies.

This is what a **table of handlers** is built out of — and an enum's row can hold one, which is what makes
the table a declaration rather than a branch:

```vs
graph "handlers"

var Ran: STRING = ""

fn pit(n: INT) {
    Ran = "pit"
}

fn net(n: INT) {
    Ran = "net"
}

enum Method(run: fn(INT), label: STRING) {
    PitTrap(pit, "a pit"),
    NetTrap(net, "a net"),
}

on start {
    val m = Method.NetTrap
    m.run(3)
}
```

Adding a method is a **row**. Nothing dispatches on the member by hand.

#### A function that might not be there

A table of handlers with no row for this key hands back **nothing**, and that is an ordinary thing to write.
Marking a function type optional needs one thing said first: after a RESULT, a bare `?` belongs to the
**result**. `fn(INT) -> INT?` is a function that returns an optional, not an optional function.

So there are two spellings, and which one you need depends on whether there is a result to compete with:

```vs
graph "maybe-a-handler"

fn bump(n: INT) {
    log(message: "" + n)
}

fn twice(n: INT) -> INT = n * 2

var Actions: MAP<INT, fn(INT)> = _newMap()

var Choices: MAP<INT, fn(INT) -> INT> = _newMap()

// No result, so nothing competes for the mark and it needs no parentheses.
fn actionFor(key: INT) -> fn(INT)? = _mapAt(map: Actions, key: key)

// A result, so the mark has to be told which one it is for.
fn choiceFor(key: INT) -> (fn(INT) -> INT)? = _mapAt(map: Choices, key: key)

on start {
    Actions = _mapWith(map: Actions, key: 1, value: bump)
    Choices = _mapWith(map: Choices, key: 1, value: twice)

    if val a = actionFor(key: 1) {
        a(7)
    }
    if val c = choiceFor(key: 1) {
        log(message: "" + c(3))
    }
}
```

The parentheses are Kotlin's answer to the same ambiguity, for the same reason. They are a **type**, so
they nest anywhere a type goes — `MAP<INT, (fn(INT) -> INT)?>` is a map of maybe-functions — and they are
allowed without a mark too, where they mean nothing at all.

Taking the value out is `if val` like any other optional (§7): a function you might not have is not a
special kind of optional, it is an optional whose value happens to be callable.

#### A record may hold a handler that takes ONE OF ITSELF

```vs
graph "self-handler"

type Rumor { n: INT, runnable: fn(Rumor)? }

fn show(r: Rumor) {
    log(message: "" + r.n)
}

on start {
    val r = Rumor { n: 3, runnable: show }
    if val go = r.runnable {
        go(r)
    }
}
```

This is the shape a dispatch column has when the thing being dispatched on is what gets passed, and it is
legal even though a record **may not contain one of its own kind**. The two are different claims: a
function value is a pointer, one word wide whatever it points at, so a record holding a handler that takes
its own type has a perfectly finite size. `type Node { next: Node }` does not, and is still refused.

**A lambda that hands nothing back may ACT.** `fn(T)` is the type of a function that exists for its
effects, so its body is allowed to have them:

```vs
graph "acting"

fn LIST<T>.each(self, f: fn(T)) {
    for item in self {
        f(item)
    }
}

var Seen: INT = 0

fn note(n: INT) {
    Seen += n
}

on start {
    [1, 2, 3].each { note(n: it) }
}
```

A lambda that hands a value BACK is an expression, and may not. Those are what `mapped`, `filtered` and
`firstWhere` take — pure nodes, re-expanded at every read of their result — so a body that acted there
would run its effects once per read. That is the same hazard §3.6 states for a named function passed the
same way; the difference is that a named one is a deliberate act at the call site, and an inline `{ … }`
is the easy thing to write by accident.

### 3.8 Lambdas — a function written where it is used

Naming a one-line predicate somewhere else and referring to it there is a lot of ceremony for `n > 2`.
A **lambda** is the same function written at the call site, in braces, after the call:

```vs
graph "lambdas"

fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)

var Kept: INT = 0

on start {
    // `it` is the parameter when you do not name one.
    val some = [1, 2, 3].filter { it > 2 }
    // Name it with `->` when `it` does not read well.
    val odd = [1, 2, 3].filter { n -> n % 2 == 1 }
    Kept = _listCount(list: some) + _listCount(list: odd)
}
```

The lambda goes **after** the call's parentheses, and the parentheses disappear when it is the only
argument. It binds to the last parameter typed `fn(…) -> …`:

```vs
graph "trailing"

var Found: INT = 0

on start {
    Found = firstWhere(list: [1, 3, 5]) { it > 2 } ?: 0
}
```

**Its parameter types come from the pin it goes into**, so there is nothing to write down: wired into
`keeping: fn(T) -> BOOL` on a `LIST<INT>`, `it` is an `INT` and the body must answer a `BOOL`. That is also
why a lambda may only be written in that position — on its own there is nothing to read them from.

**A lambda may read the locals around it**, which a named function cannot:

```vs
graph "capture"

fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)

var Near: INT = 0

on start {
    val range = 3
    Near = _listCount(list: [1, 2, 5, 9].filter { it < range })
}
```

What it reads is **copied when the lambda is reached**, not looked up later — so reassigning `range`
afterwards cannot be seen through one that was already built. A graph variable is not copied: there is one
cell for the whole run and the body reads it where it stands, so a lambda always sees the current value of
one.

#### Where one may be written

A lambda carries an arity it inferred and parameter types it does not have, so it needs a **destination**
that says what it must be. There are four, and a call's argument is only the first:

```vs
graph "destinations"

type Hooks {
    ready: fn(INT) -> BOOL,
    step: fn(INT),
}

fn apply(n: INT, f: fn(INT) -> BOOL) -> BOOL = f(n)

// A record field, and `{ }` — the do-nothing body, which fits a `fn(T)` and nothing else.
val Wired: Hooks = Hooks { ready: { it > 0 }, step: { } }

// A declared graph variable.
var Predicate: fn(INT) -> BOOL = { it > 0 }

on start {
    // A call's argument, trailing or named...
    val a = apply(n: 1) { it > 0 }
    val b = apply(n: 1, f: { it > 0 })
    // ...and a local with a written type.
    val over: fn(INT) -> BOOL = { it > 0 }
    log(message: "" + a + b + over(1))
}
```

A **field default** and a function's **declared result** are destinations too, so `type H { ready: fn(INT)
-> BOOL = { it > 0 } }` and `fn pick() -> fn(INT) -> BOOL = { it > 0 }` both read as one.

An enum's data column, an enum column's default and a function parameter's default take one too. Those are
document data rather than code, so what gets stored is the NAME of a function written for you — the same
thing the column held when you named one yourself — and it prints back as the lambda you wrote.

`val f = { it > 0 }` is refused, and that is the rule rather than an omission: nothing there says what `it`
is.

---

## 4. Declarations

### 4.1 `var` — state that outlives a call

Declared at the top level, typed, and shared by the whole run.

```vs
graph "vars"

var Trips: INT = 0
var Name: STRING = "unnamed"
var Ready: BOOL = false

/** A default may be COMPUTED, not just written out — it runs once, before `on start` does. */
var Seed: INT = 40 + 2

on start {
    Trips = Trips + 1
}
```

A graph variable is **one cell for the whole run**, shared by every call. That makes it the right home for
configuration and for state a *second* handler reads, and the wrong home for anything else: a counter only
`on start` touches belongs in a local `var` declared just above the loop that uses it (§5.1), and a
recursive function that kept its accumulator in a graph variable would find the inner call had overwritten
it. §14 has the rule and the test for applying it.

**A non-optional variable must start with a value.** `var Home: TILE` is refused — give it one, or declare
it `TILE?`. A record is exempt: it starts as its own zero, field by field.

### 4.2 `val` — a name bound once

```vs
graph "vals"

val MaxTrips = 12
val Home = tile(3200, 3200, 0)

var Left: INT = 0

on start {
    Left = MaxTrips
}
```

`val` is the immutable half of `var`, and it spans both scopes exactly as `var` does — this section and
§5.1 are the same word. Nothing may assign to one.

A **computed** `val` runs once, before anything else, and then stays. It has to say its type, because a
value that must be worked out is a graph variable underneath and a graph variable is always typed:

```vs
graph "computed"

val Started: INT = now()

on start {
    log(message: "" + Started)
}
```

That fills the gap the language used to have between the two: a `const` could not run, and a `var` could
not say it never changes.

**A `val` written out folds to a literal**, which is what lets it stand where only a value will do — an
enum row, a parameter default:

```vs
graph "folded"

val Needed = 30

enum Quarry(count: INT = Needed) { Kebbit, Graahk }

fn scaled(n: INT, by: INT = Needed) -> INT = n * by

on start {
    log(message: "" + scaled(n: Quarry.Kebbit.count))
}
```

A computed one cannot go there — there is nowhere in a declaration to hang something that runs — and the
message says so.

> **`let` and `const` are the older spellings** of what `val` now covers, and are still read. Only `val` is
> printed, so a file converges the first time a tool writes it back. Do not write new ones.

### 4.3 `type` — a record

Covered in §3.1. Fields may be typed through an import (`from: geo::Point`) — from any document, at any
depth, and you do not have to have imported it yourself (§2).

**A record may hold one of its own kind**, which is how a list, a tree or a parent pointer is written:

<!-- text -->
```vs
graph "recursive"

type Node { value: Int = 0, next: Node? = null }

type Tree { label: String = "", kids: LIST<Tree> = [] }

on start {
    val tail = Node { value: 2 }
    val head = Node { value: 1, next: tail }
    val root = Tree { label: "root", kids: [Tree { label: "leaf" }] }
}
```

The one shape refused is the one that cannot be built. `type Loop { me: Loop }` would need a value of
itself before one could be made, so it is an error rather than a field that quietly holds null:

```
'Loop' cannot be built: Loop -> Loop is a cycle of required fields, so a value of it would have to exist
before it could be made. Make one of those fields optional ('Loop?'), or hold them in a LIST or a MAP.
```

**An optional, a `LIST` and a `MAP` each break the cycle**, because each has a finite value of its own —
null, empty, empty. That is the same rule Rust reaches with `Box` and Kotlin with a nullable. Two records
that hold each other are fine on the same terms: one of the steps has to be boxed.

**A field may have a default**, and then a literal can leave it out:

```vs
graph "field-defaults"

type Options {
    reachable: BOOL = true,
    within: INT = 10,
    note: STRING = "",
}

var Reach: INT = 0

on start {
    val loose = Options {}
    val tight = Options { within: 2 }

    Reach = loose.within + tight.within
}
```

**A field default may be worked out**, unlike a parameter's or an enum column's — and it is worked out at
each construction, not once:

```vs
graph "computed-defaults"

fn six() -> INT = 6

type Row {
    n: INT = six() * 2,
    tag: STRING = "",
}

var Total: INT = 0

on start {
    Total = Row {}.n + Row { n: 1 }.n
}
```

A field with no default is its type's zero.

**The consequence, stated plainly: a record literal can be a STEP.** A default that has to run makes every
literal omitting that field something that runs, and therefore makes the function containing it a step —
derived, as every other body is, so it cannot disagree with what the literal needs. The printer says so by
giving the braces back. A literal default emits nothing at all and leaves the literal an expression, so
the common case is untouched.

**A `single` is an instance, so this is what makes one hold anything but data** — a call, another
variable, a function reference:

```vs
graph "run-state"

fn tick(n: INT) -> INT = n + 1

single Run {
    budget: INT = 12,
    step: fn(INT) -> INT = tick,
}

var Out: INT = 0

on start {
    Out = Run.step(Run.budget)
}
```

### 4.4 `single` — a record with one of it

```vs
graph "state"

enum Phase { Gather, Store }

single Run {
    phase: Phase = Phase.Gather,
    laps: INT = 0,
}

on start {
    Run.laps += 1

    if Run.laps > 5 {
        Run.phase = Phase.Store
    }

    log(message: "" + Run.laps)
}
```

One declaration for the state a script keeps. It registers a **record** and the one **variable** of it under
the same name, so `Run.laps` is the ordinary field read and `Run.laps = …` the ordinary field assignment —
there is nothing new to learn beyond the word.

Written by hand it is the two declarations it stands for:

```vs
graph "state-by-hand"

enum Phase { Gather, Store }

type Run { phase: Phase, laps: INT }

var Run2: Run = Run { phase: Phase.Gather, laps: 0 }

on start {
    Run2.laps += 1
}
```

— which is a pair that has to be kept in step by hand, and the reason to prefer the one word.

Its fields start at their defaults, and at their type's zero where there is none. `single` behaves
like `type`. There are no type parameters: there is one instance, so there is nothing to bind them
to.

**It is a real instance, so its fields may be worked out** — the same rule §4.3 states for `type`, and
worth saying twice because a `single` is where state lives and state is rarely a literal:

```vs
graph "computed-single"

fn budgetFor() -> INT = 6 * 2

fn twice(n: INT) -> INT = n * 2

single Run {
    budget: INT = budgetFor(),
    step: fn(INT) -> INT = twice,
    laps: INT = 0,
}

on start {
    log(message: "" + Run.step(Run.budget))
}
```

A field may hold a call, another variable, an expression or a function reference — anything a `val` could
be bound to. An all-literal `single` still costs nothing: it emits no initialiser and is the declaration
it always was.

### 4.5 `enum` — a closed set of names

```vs
graph "phases"

enum Phase { Gather, Store, Move }

var State: Phase = Phase.Gather

on start {
    State = Phase.Store

    if State == Phase.Store {
        log(message: "off to the bank")
    }
}
```

A member **is its name** at run time — `Phase.Gather` is the string `"Gather"`. That is why a saved document
stays readable, why comparison is ordinary equality, and why inserting a member in the middle changes
nothing. The cost is the other side of the same coin: *renaming* a member breaks documents that stored the
old spelling.

An enum is not a record: a record is every field at once, an enum is exactly one member. So `Phase { … }`
does not construct anything.

Two things you cannot do with one:

- **`x is Phase` is refused.** A member is a bare string at run time, indistinguishable from any other
  string and from every other enum's members, so the answer would mean nothing. The compiler says so and
  asks you to compare instead — `x == Phase.Store`.
- **`x as Phase` is refused.** `as` reads one record's fields as another's, and an enum has no fields.

#### An enum can be a table

Declare fields after the name and every member carries a row. Read one with a dot:

```vs
graph "targets"

enum Target(count: INT, anchor: TILE, quarry: STRING) {
    WildKebbit(30, tile(2308, 3575, 0), "Wild kebbit"),
    HornedGraahk(30, tile(2771, 3000, 0), "Horned graahk"),
    RedSalamander(5000, tile(2452, 3223, 0), "Red salamander"),
}

var Needed: INT = 0

fn describe(t: Target) -> STRING = t.quarry

on start {
    val t = Target.RedSalamander

    Needed = t.count
    log(message: describe(t: t))
    log(message: toText(value: t.anchor.x))
}
```

**This is the alternative to a `when` with an arm per member returning constants** — which is a table
written as a branch. The columns carry their declared types, so `t.count + 1` is arithmetic and `t.anchor`
wires into anything expecting a tile.

A column may have a **default**, and then a member can leave it off:

```vs
graph "tiers"

enum Tier(rank: INT, reward: INT = 0) {
    Novice(1),
    Master(5, 250),
}

var Prize: INT = 0

on start {
    Prize = Tier.Novice.reward + Tier.Master.reward
}
```

A row is **data**, so a value has to be something the document can hold:

| Allowed | |
|---|---|
| literals | `30`, `"Wild kebbit"`, `true`, `0xFF0000FF` |
| tiles | `tile(2308, 3575, 0)` |
| record literals, nested | `Vec2 { x: 2, y: 7 }`, `Box { origin: Vec2 { x: 3, y: 0 } }` |
| members of other enums | `Phase.Gather`, `vars::Phase.Gather` |
| lists | `[207, 211]` |
| a `val`, local or imported | `MaxTrips`, `vars::MaxTrips` |
| a function, as a value | `pit` in the handler table above |

What is refused is anything that has to **run** — a function call, a `var`, arithmetic. A graph `var` is
where a computed value belongs.

Three more things follow from a member still being its name:

- **The row travels with the name, not with the position.** Reordering the declaration changes nothing.
- **Every column must be answered**, by a value or by its default. A row that leaves one unanswered is
  refused at the member, naming the column.
- **Declaration order does not matter.** A member's row may name a record or a const declared further down.

#### `Type.values()` — every member, in order

```vs
graph "every-target"

enum Quarry(count: INT, name: STRING) {
    WildKebbit(30, "Wild kebbit"),
    HornedGraahk(30, "Horned graahk"),
    RedSalamander(5000, "Red salamander"),
}

var Total: INT = 0

on start {
    for q in Quarry.values() {
        Total = Total + q.count
        log(message: q.name)
    }
}
```

The list's elements carry the **enum's** type, so the loop variable can read a column — which is what makes
"do this for every member" a loop over the table rather than a list literal repeating the declaration. Add a
member and the loop picks it up; a hand-written `[Quarry.WildKebbit, …]` goes stale silently.

It compiles to one constant, so there is no cost to reaching for it.

A function you declare on the type wins over the builtin, so `fn Quarry.values()` of your own is yours. A
value in scope wins over both — the ordinary rule for every name here.

`enum` behaves like `type`.

### 4.6 `fn` — a function

```vs
graph "functions"

/** An EXPRESSION body: no steps, just a value. */
fn double(n: INT) -> INT = n * 2

/** A BLOCK body: statements, and `return` to hand something back. */
fn describe(n: INT) -> STRING {
    if n > 10 {
        return "big"
    }
    return "small"
}

/** Two results, and the shorthand for naming them. */
fn bounds(a: INT, b: INT) -> (low: INT, high: INT) {
    return a < b ? a : b, a < b ? b : a
}

/** No results at all is fine for something that only acts. */
fn announce(what: STRING) {
    log(message: what)
}

/**
 * A parameter may carry a DEFAULT, and then callers may leave it out.
 *
 * This is the answer to "I want two versions of this function": one signature that covers both, rather
 * than overloading, which the language does not have.
 */
fn scaled(n: INT, by: INT = 10) -> INT = n * by

var Out: INT = 0
var Text: STRING = ""

on start {
    Out = double(n: 21)
    Text = describe(n: 50)
    val (low, high) = bounds(a: 9, b: 2)
    Out = high * 100 + low
    announce(what: "done")

    Out = scaled(n: 3)          // 30 — `by` defaulted
    Out = scaled(n: 3, by: 2)   // 6
}
```

**`= e` and `{ return e }` are the same function.** The short form is a spelling, exactly as it is in
Kotlin — the compiler reads one as the other and no rule anywhere keys off which you typed. So a one-line
wrapper is legal whatever it wraps, and you can add a line to a body without rewriting its callers.

**With no result declared, `= e` RUNS `e`** rather than returning it — `{ e }`, not `{ return e }`. That is
what makes a one-line body legal for something that hands nothing back, which is most of what a script
does:

```vs
graph "one-liners"

type Rumor { count: INT, runnable: fn(Rumor) }

/** Leaving the arrow off says it hands nothing back; the body runs the call rather than returning it. */
fn Rumor.run(self) = self.runnable(self)

on start {
    log(message: "ready")
}
```

There is no `Unit` or `Void` to write: a function that hands nothing back says so by leaving the arrow off,
and a function TYPE says it the same way — `fn(Rumor)`. A named type meaning "nothing" would be a second
spelling for what the omission already says.

If the expression does produce a value, you have to say what it is — `fn f() = twice(n: 1)` is told to add
`-> INT` rather than silently throwing the answer away.

**What the body CONTAINS decides whether a function is an expression or a step**, and nothing else does. A
body that is a single `return` of something which only computes is an expression: it has no statements to
sequence, so it is re-evaluated wherever it is read, like `a + b`. Anything else is a step — it happens in
order, once, where it is written. Derived rather than declared, so it cannot disagree with the body:

```vs
graph "bodies"

fn noisy() -> INT {
    log(message: "working")
    return 1
}

fn twice(n: INT) -> INT = n * 2                  // an expression — nothing here has to run
fn loud(n: INT) -> INT = noisy() + n             // a STEP, because `noisy` has to run
fn quiet(n: INT) -> INT { return n * 2 }         // an expression too — the braces are spelling
```

Adding a `log` to a one-line function therefore changes how it is called, which is the honest consequence
of it no longer being just a value. Nothing refuses it, and nothing has to change at the call site.

Two shapes stay steps whatever they contain, because the short form could not mean the same thing: a
function with **several results** (an expression is read once per result, so `val (a, b) = pair()` would
call `pair` twice), and a mutating extension.

Printing gives back the canonical spelling rather than the one you typed, since the graph holds no record
of it: an expression prints as `= e`, a step prints with braces. Which is itself useful — the printed form
tells you which one you have.

A parameter default has to be **written out**: a literal, or a `val` that folds to one — not an
expression. A record FIELD default does not; see §4.3.

Functions may recurse, and two functions may call each other.

**One document, one meaning per name.** Two `describe`s in one document are refused however different their
parameters are:

```
two functions here are both called 'describe' — a different receiver is not enough, because a call names
its function by name. Two documents may each extend 'describe'; one document may not do it twice
```

### 4.7 `on` — event handlers

```vs
graph "events"

var Ticks: INT = 0
var Frames: INT = 0

on start {
    Ticks = 0
}

on tick {
    // Once per game tick (600ms). Where a state machine belongs.
    Ticks = Ticks + 1
}

on render {
    // Every frame, inside the frame budget. Reading and drawing only.
    Frames = Frames + 1
}

on stop {
    log(message: "ran for " + Ticks + " ticks")
}
```

| | |
|---|---|
| `on wake` | get ready, before the loop starts |
| `on start` | a run begins here. A graph with none of these will not run |
| `on sleep` | the host has asked for the account back; hand it over |
| `on tick` | once per game tick |
| `on render` | once per frame |
| `on stop` | when everything has finished |

### The shape of a run

Three of those are one story told in order — **get ready, do the work, put yourself away**:

```
on wake    walk to where the work is, draw what you need, read back anything you saved
on start   the loop
on sleep   finish up and write down whatever the next wake will want
```

`on wake` runs **every** time the script starts, not only after a sleep. A script that has never run is
asleep; running it wakes it. So this is where "ready to begin" belongs even for a script that never
sleeps and saves nothing:

```vs
graph "organiser"

on wake {
    // Everything that has to be true before the work can start. Runs once, and Start does not
    // begin until it has finished — so the loop never has to ask whether it is ready yet.
    log(message: "walking to the bank")
}

on start {
    log(message: "organising")
}
```

That script needs no `on sleep` at all: the two entries are independent, and neither implies the other.

### Handing over

Nothing interrupts a loop. The host raises a flag, `sleepRequested()` goes true, and **the loop decides
where to stop** — which is the only way "finish the round first" can mean anything:

```vs
graph "worker"

single Run {
    laps: INT = 0,
}

on wake {
    // A missing file is null, not an error, so the same handler covers the first run and the thousandth.
    if val saved = readJson(path: "worker/state.json") as Run {
        Run = saved
    }
    log(message: "starting from lap " + Run.laps)
}

on start {
    while true {
        Run.laps = Run.laps + 1
        // Asked HERE, at the end of a lap, rather than at the top of the loop: this is the moment the
        // work is in a state somebody else could take over from.
        if sleepRequested() {
            break
        }
        delay(ms: 600)
    }
}

on sleep {
    // Reached once the loop above has left. It may wait — this is where a bank trip goes.
    writeJson(path: "worker/state.json", value: Run, pretty: true)
}
```

**Whatever `on sleep` does not write down is gone.** Waking re-runs the script from the top with every
variable back at its declared default, which is what lets a sleep survive the client being closed: a
sleep and a crash resume by exactly the same path. Keeping the state in one `single` is what keeps those
two handlers one line each.

`on sleep` is not the same event as `on stop`. A stop is "you are done, now" — it fires on a crash and
when somebody presses the button, and by then the avatar has already been told to stop, so it can write
a file but it cannot bank. A sleep is a request to wind down, and the script is still able to act.

A script that declares `on sleep` and never reads `sleepRequested()` is warned about: the host asks,
nothing answers, and it gets stopped on a timeout instead — with the wind-down never run.

**`on tick` and `on render` may not wait.** Each rides a clock the client owns and runs synchronously, so a
wait there is a wait for everything. Anything that blocks — walking, interacting, `delay` — is refused
before the script runs, and the check follows through function calls and through function values:

```
Delay inside On Render: a frame has nothing to wait for, and the pass is abandoned if it does not finish.
```

Decide in the handler; act in the loop.

**`on wake` and `on sleep` may.** They are pumped by the host too, but at a moment of their own rather
than inside somebody else's budget — nothing else is running while they are — and waiting is the point
of both.

A document may declare more than one of a kind, and all of them run.

**An imported document's handlers run too.** A library that declares `on tick` keeps its own clock the
moment something imports it — which is what makes a document a self-contained component rather than a bag
of functions. Mark one `on` to keep it working while denying importers the name.

```vs
graph "heartbeat"

var Beats: INT = 0

on start {
    Beats = 0
}

on tick {
    Beats += 1
}
```

### 4.8 `fn Type.name` — an extension

A function may be written ON a type, and called with a dot:

```vs
graph "extensions"

fn INT.double(self) -> INT = self * 2

fn INT.plus(self, n: INT) -> INT = self + n

type Point { x: INT, y: INT }

fn Point.sum(self) -> INT = self.x + self.y

var Out: INT = 0

on start {
    val p = Point { x: 1, y: 2 }
    Out = 3.double().double() + 1.plus(n: 2) + p.sum()
}
```

**An extension is an ordinary function whose first parameter was written somewhere unusual.**
`fn INT.double(self)` IS `fn double(self: INT)` — the receiver arrives as `self`, and nothing new runs.

**`self` is written out, and it takes no type** — the type is already in the head, before the dot, where it
cannot disagree with a second spelling. It must come first.

#### A FIELD of the same name wins

`recv.name(…)` can be two things — an extension called on `recv`, or a function-valued FIELD of it being
invoked — and when both exist the field is chosen, however the receiver was written:

```vs
graph "precedence"

export type Hooks { at: fn(INT) -> INT }

export type Row { impl: Hooks }

export fn twice(n: INT) -> INT = n * 2

export fn Hooks.at(self, n: INT) -> INT = 99

export val Impl: Hooks = Hooks { at: twice }

export var Out: INT = 0

on start {
    val r = Row { impl: Impl }
    // Both of these are the field — `twice` — not the extension.
    Out = Impl.at(4) + r.impl.at(4)
}
```

The reason it is that way round outlives the tie-break: an extension's name can be changed where it is
imported — `import { at as elsewhere }` — and a record's field name cannot be aliased at all. The field is
the name with no way out; the extension is the one that can move.

#### No `self` means a function on the TYPE

Leaving `self` off is not an omission — it says the function belongs to the type rather than to a value of
it, and it is called on the type:

```vs
graph "constructors"

type Vec2 { x: INT, y: INT }

/** No `self` — called as `Vec2.new(x: 5, y: 3)`. */
fn Vec2.new(x: INT, y: INT) -> Vec2 = Vec2 { x, y }

/** `self` — called on a value, as `v.lengthSq()`. */
fn Vec2.lengthSq(self) -> INT = self.x * self.x + self.y * self.y

var Out: INT = 0

on start {
    val v = Vec2.new(x: 5, y: 3)
    Out = v.lengthSq()
}
```

This is what a constructor looks like here, and it is the natural home for the normalising ones a script
otherwise writes by hand — `Tile.of(x, y)` defaulting the plane, `Color.rgb(r, g, b)` defaulting alpha — as
well as for named alternates like `Vec2.zero()`.

Two rules: **a variable of the same name wins**, and **the type is reached the ordinary way**, so an
imported one is written `geo::Vec2.new(x: 5, y: 3)`.

#### Rules worth knowing

```vs
graph "list-helpers"

fn LIST<T>.add(self, value: T) -> LIST<T> = _listWithItemAdded(list: self, value: value)

var Out: INT = 0

on start {
    var xs: LIST<INT> = []
    xs = xs.add(value: 1)
    xs = xs.add(value: 2)
    Out = _listCount(list: xs)
}
```

- **Dot form only.** `double(self: 3)` is refused.
- **A node wins the name.** `draw.text(…)` names the node type `draw.text` — a dot separates the parts of a
  node's name — so a dotted call is looked up as a node first.
- **Extensions cross imports, unqualified.** This is the one name an import brings in without an alias, so
  it is also the one that can collide. Two imports extending the same name **on the same type** make a call
  ambiguous and it is refused naming both — declare your own (a local extension wins) or call it qualified:

  ```
  'twice' is extended by 'a' and 'b' — say which: 'a::twice(self: …)'
  ```

#### `LIST<Type>` — a receiver that carries its element type

A receiver is a full type, so it may be parameterized — and **the receiver is what picks between functions
of the same name**:

```vs
graph "int-lists"

export fn LIST<INT>.describe(self) -> STRING = text("{n} numbers", n: _listCount(list: self))
```

```vs
graph "string-lists"

export fn LIST<STRING>.describe(self) -> STRING = text("{n} words", n: _listCount(list: self))
```

```vs
graph "described"

import { describe } from "int-lists"
import { describe } from "string-lists"

on start {
    log(message: [1, 2, 3].describe())
    log(message: ["a", "b"].describe())
}
```

**The most specific receiver wins** — `LIST<INT>` beats a bare `LIST` — and a tie is broken the way every
other name is: this document's own declaration wins.

**An extension has to be ASKED for by name**, as above: it is the one thing an import brings in that has
nowhere to put an alias, so the `{ … }` list is the only place a document can say it wants one. Importing
a document for its types does not bring its verbs with it, and two documents may both offer `describe`
under that name — the receiver tells them apart, and the import list is what put them both in reach.

It does NOT become a bare name: `describe(self: xs)` is not a call you can write, exactly as it is not for
an extension declared here. That is also what lets `import { isEmpty } from "core/list"` sit beside the
`isEmpty` NODE without either changing what the other means.

#### `LIST<T>` — one type variable, bound by the receiver

```vs
graph "generic-helpers"

fn LIST<T>.head(self) -> T? = _listFirst(list: self)

fn LIST<T>.has(self, value: T) -> BOOL = _listContains(list: self, value: value)

var Where: STRING = ""

on start {
    // `T` is INT here, so `head` gives an `INT?` and `has` wants an INT.
    if val n = [1, 2, 3].head() {
        Where = text("{n}", n: n)
    }
    // and STRING here, from the same declaration.
    if val s = ["a", "b"].head() {
        Where = s
    }
}
```

- **The receiver is the binding site.** `T` is worked out from what the call is made on, then substituted
  through the parameters and the result. There is no inference across arguments — so `["a"].has(value: 1)`
  is refused because `T` is already `STRING`.
- **`T?` substituted with `INT` is `INT?`.** The `?` belongs to the position, so a generic result narrows
  through `if val` and `?:` the ordinary way.
- **Nothing is bounded or variant.** You cannot say "any `T` that has a `name`", and a `LIST<INT>` does not
  flow into a `LIST<STRING>` in either direction.

Outside a receiver, a type variable must be spelled like one — a single capital letter, optionally with
digits (`T`, `K`, `V`, `T2`) — so that `p: Tille` stays a typo for a record rather than quietly becoming a
name that accepts anything.

#### A body that assigns `self` — `xs.add(v)` as a statement

An extension whose body assigns `self` is **mutating**, and it may be called as a statement:

```vs
graph "mutating"

fn LIST<T>.add(self, value: T) {
    self = _listWithItemAdded(list: self, value: value)
}

var Total: INT = 0

on start {
    var seen: LIST<INT> = []
    for n in range(from: 0, to: 4) {
        seen.add(value: n)
    }
    Total = _listCount(list: seen)
}
```

**Nothing is mutated.** `seen.add(value: n)` *means* `seen = seen.add(value: n)` — a write-back at the call
site — so two names holding the same list stay independent, exactly as they were before this existed.

- **It is derived from the body, never declared.** A function is mutating because it assigns `self`.
- **The receiver has to be somewhere you can write.** A `var` local or a graph variable; a `val` is refused
  by name.
- **It cannot also return a value.** There is one place to write back to.
- **It costs nothing.** The body is spliced into the call site, so the loop above is O(n), not O(n²).

---

### 4.9 `test` — a check

A `test` is a named block that a test runner runs and that nothing else ever does. Write as many as you
like, anywhere a declaration goes.

```vs
graph "arith"

fn double(n: Int) -> Int = n * 2

test "doubling two is four" {
    assert double(n: 2) == 4
}

test "doubling nothing is nothing" {
    assert double(n: 0) == 0, "zero is the one that catches an off-by-one"
}
```

**`assert` is a statement, not a call**, and that is what makes a failure worth reading. The condition is
kept as you wrote it, and when it is a comparison both sides are evaluated once and reported:

```
FAIL  scheduler/goal - a recipe with two inputs keeps both quantities
        assertion failed: got[1].per == 3
  left:  2
  right: 3
```

Strings come back quoted, so `3` and `"3"` cannot be confused for one another. `assert x, "why"` replaces
the quoted condition with your own words.

**A test never runs as part of a script.** `on start` and the other handlers are fired by the host; this
kind is not, so a script carries none of its tests' code. It is still *checked*: a test that stops
compiling fails an ordinary `graph check`, which is the thing conditional compilation gives up.

**Where tests live is up to you.** They can sit beside the code, and the convention is a separate document
whose name ends in `_test` — `scheduler/goal_test` tests `scheduler/goal`. That suffix is not decoration:
a test tree beside a source tree would otherwise have two documents claiming the name `scheduler/goal`,
and one of them would win silently.

A `_test` document may use the **un-exported** names of the one module it tests, and of nothing else. So
the thing under test does not have to be exported to be tested, and a test cannot reach into a third
module's internals on the way past.

**A test that touches the world fails.** A runner supplies fakes for the game verbs a test declares, and
any verb that has not been faked is refused rather than run — which is what lets the pure parts of a
script (a planner, a draw, a weighting) be exercised with no game attached at all. Time is faked too: a
`delay(ms: 60000)` inside a test costs nothing, because the clock is advanced rather than waited on.

## 5. Statements

### 5.1 Bindings

```vs
graph "bindings"

type Point { x: INT, y: INT }

fn bounds(a: INT, b: INT) -> (low: INT, high: INT) {
    return a < b ? a : b, a < b ? b : a
}

var Out: INT = 0

on start {
    // `val` names a value ONCE. It cannot be assigned again.
    val fixed = 10

    // `var` inside a body is a local you CAN assign — and it belongs to this call, so a
    // recursive or re-entrant function keeps its own.
    var running = 0
    running = running + fixed

    // Several results at once.
    val (low, high) = bounds(a: 9, b: 2)

    // A record's fields, by name.
    val here = Point { x: 3, y: 4 }
    val {x, y} = here

    Out = running + low + high + x + y
}
```

A local must start with a value: `var n: INT` on its own is refused.

**`val` is immutable, `var` is not.** Reach for `val` by default; a name that never changes is one less
thing to follow. It is the same word as §4.2 — one spelling at both scopes, as `var` already was.

A local's type comes from its initialiser, and may be **written out** when the initialiser would say the
wrong thing:

```vs
graph "typed-locals"

var Out: FLOAT = 0.0

on start {
    // Without the type this is an INT, because `0` is — and a float accumulator that starts as an
    // integer stays one.
    var total: FLOAT = 0
    total = total + 0.5

    val limit: FLOAT = 2
    if total < limit {
        Out = total
    }
}
```

#### Taking several values apart

Parens take a call's **outputs**; braces take a record's **fields**. The outputs can be taken three ways,
and the difference matters more than it looks:

```vs
graph "destructuring"

fn clicked() -> (Id: INT, Kind: STRING, Name: STRING, Age: INT) {
    return 1, "npc", "Cow", 3
}

var Out: STRING = ""

on start {
    // BY POSITION — the first two outputs, whatever they are called.
    val (a, b) = clicked()

    // BY NAME, because every bare name IS an output's name. Order is irrelevant here.
    val (name, id) = clicked()

    // BY NAME with a rename, for when the pin's name is not the one you want locally.
    val (clickedName: name, since: age) = clicked()

    Out = "" + a + b + name + id + clickedName + since
}
```

**A list whose names are all output names binds by those outputs.** A list whose names are *not* all
outputs — `let (a, b)` — is positional.

Reach for the named form when a node has more outputs than you want, or when you cannot remember their
order: positional binding follows the pin ORDER, so a node that gains an output or has two reordered changes
what an existing script binds, and nothing reports it.

**One rename makes the whole list by-name**, and bare entries in it are shorthand for `x: x`. Taking fewer
than a node gives is allowed — you get a prefix, and a warning naming what was skipped. Taking more is an
error.

### 5.2 Assignment

```vs
graph "assign"

var Total: INT = 0

on start {
    Total = 5
    Total = Total + 1

    var local = 0
    local = local + Total
    Total = local
}
```

**Compound assignment.** `+=`, `-=`, `*=`, `/=` and `%=` read the target, apply the operator and write it
back — `n += 1` *is* `n = n + 1`:

```vs
graph "compound"

type Course { laps: INT }

var Total: INT = 1

on start {
    var i = 0
    while i < 3 {
        i += 1
    }

    Total *= 2

    // A field works too, on a local or on a graph variable.
    var course = Course { laps: 0 }
    course.laps += 1
}
```

The right-hand side is a whole expression, so `n += a * 2` means `n = n + (a * 2)`.

### 5.3 `if` / `else`

```vs
graph "branching"

var Grade: STRING = ""

on start {
    val score = 72

    if score > 90 {
        Grade = "high"
    } else if score > 50 {
        Grade = "middling"
    } else {
        Grade = "low"
    }
}
```

**Braces are optional**, on both arms, and so are parentheses around the condition:

```vs
graph "short-branches"

var Ready: BOOL = true

var Count: INT = 0

on start {
    if Ready log(message: "yes")
    else log(message: "no")

    if (Count > 10) log(message: "many")
    else if Count > 0 log(message: "some")
    else log(message: "none")
}
```

A bare body holds exactly ONE statement. Add a second and it needs braces, which the compiler will tell you.

### 5.4 `when` — the first arm that matches

Two forms. **With a subject**, each case is a value to equal:

```vs
graph "when-subject"

enum Phase { Gather, Store, Move }

var State: Phase = Phase.Gather

on start {
    when State {
        Phase.Gather -> log(message: "gathering")
        Phase.Store -> log(message: "storing")
        else -> {
            log(message: "walking")
            log(message: "still walking")
        }
    }
}
```

**Without one**, each case is a condition — which is what to reach for when the arms ask about different
things:

```vs
graph "when-conditions"

var Count: INT = 0

on start {
    when {
        Count > 10 -> log(message: "many")
        Count > 0 -> log(message: "some")
        else -> log(message: "none")
    }
}
```

Only the FIRST matching arm runs. Arms are tested in order, so `Count > 10` above is reachable only because
it is written above `Count > 0`. `else` must come last. Nothing matching and no `else` runs nothing and
carries on.

**Exhaustiveness.** A `when` over an enum with no `else` is checked, and a missing member is reported:

```
'Phase' also has Move — with no 'else', that value falls straight through
```

A warning rather than an error, because falling through is legal. Its real value is the day you add a member
to an enum: every `when` that no longer covers it says so.

`break` and `continue` work inside an arm, and leave the enclosing loop.

### 5.5 `while`

```vs
graph "loops"

var Total: INT = 0

fn overTen(n: INT) -> INT {
    if n > 10 {
        return 1
    }
    return 0
}

on start {
    var i = 0
    while i < 10 {
        Total = Total + i
        i = i + 1
    }

    // A condition may call something with steps in it. It is re-asked every time round.
    var j = 0
    while overTen(n: j) == 0 {
        j = j + 1
    }
    Total = Total + j
}
```

A loop that never waits is stopped — see §11.

### 5.6 `for`

Iteration is always **over a list**. To count, iterate over `range(from:, to:)`, which is the list of whole
numbers from one up to — but not including — the other:

```vs
graph "counting"

var Total: INT = 0

on start {
    val xs = [2, 5, 1]

    // 0 … 15
    for i in range(from: 0, to: 16) {
        Total = Total + i
    }

    // Exactly the valid indices of xs
    for i in range(from: 0, to: _listCount(list: xs)) {
        Total = Total + xs[i]
    }
}
```

Excluding the end is what makes that second line right without a `- 1`, and a range that ends before it
starts is simply empty — so a count of 0 does not loop rather than failing.

```vs
graph "foreach"

var Total: INT = 0
var Weighted: INT = 0

on start {
    val sizes = [2, 5, 1]

    for size in sizes {
        Total = Total + size
    }

    // With the index, which is 0-based. The parens are only for this two-name form.
    for (size, i) in sizes {
        Weighted = Weighted + size * (i + 1)
    }
}
```

The loop variable carries the list's element type, so its fields are checked. To walk a map, iterate
`_mapKeys(map:)`.

### 5.7 `break` and `continue`

`break` leaves the innermost loop; `continue` skips to the next pass. Both work in a `for` as well as a
`while`.

```vs
graph "jumps"

var Total: INT = 0

on start {
    var n = 0
    while n < 100 {
        n = n + 1
        if n == 3 {
            continue
        }
        if n > 6 {
            break
        }
        Total = Total + n
    }

    for size in [1, 2, 3, 4] {
        if size == 2 {
            continue
        }
        if size == 4 {
            break
        }
        Total = Total + size
    }
}
```

### 5.8 `sequence`

Two or more blocks, run in order. Useful on the canvas, where it replaces a long chain of wires; in text it
mostly documents that a run of statements belongs together.

Every block runs, in the order written, and then the statement after the `sequence` runs once:

```vs
graph "sequenced"

var Steps: INT = 0
var Done: BOOL = false

on start {
    sequence {
        Steps = Steps + 1
    } {
        Steps = Steps + 2
    }
    Done = true
}
```

A block that `return`s takes control with it, so the blocks after it — and the statement after the
`sequence` — do not run. That is the same rule `break` and `continue` follow.

### 5.9 `try` / `catch`

What a script does when something goes wrong is a decision, and until now the only answer was "stop". A
`try` is the other one:

```vs
graph "guarded"

var Handlers: MAP<INT, fn(INT) -> INT> = _newMap()

fn handlerFor(key: INT) -> fn(INT) -> INT =
    _mapAt(map: Handlers, key: key) ?: error(message: "no handler for that key")

on start {
    try {
        val f = handlerFor(key: 9)
        log(message: "" + f(1))
    } catch e {
        log(message: "carrying on without it: " + e)
    }
    log(message: "still running")
}
```

**There is nothing to match on, and that is the design.** vs has no classes, so there is no exception
hierarchy, no clause ordering and no question of which handler a failure belongs to. A `try` catches
*everything* raised underneath it, and what the `catch` binds is a **STRING** — the message, which is the
whole of what there is to know.

The name takes no parentheses and no type, like `if val x = …` and `for i in …`.

**"Underneath it" means underneath**, including inside anything the body calls, however deep:

```vs
graph "deep"

fn inner() {
    error(message: "three frames down")
}

fn outer() {
    inner()
}

on start {
    try {
        outer()
    } catch e {
        log(message: e)          // "three frames down"
    }
}
```

It catches more than a deliberate `error(…)`: a division by zero, a host that is not there, a value that
was not the number something needed. Those are the failures worth surviving in a script that has been
running for two hours.

Four rules worth stating, each of which falls out of the same mechanism:

- **The innermost `try` wins**, and an outer one still guards what comes after the inner one finishes.
- **A handler does not catch itself.** An `error(…)` raised inside a `catch` block goes out to the next
  handler, or stops the script. A failing handler cannot loop.
- **Leaving the body any way at all disarms it** — `return`, `break` and `continue` out of a `try` leave
  nothing behind that could catch a later, unrelated failure.
- **Nothing catches it** is still the default, and still the right one for a mistake. An uncaught failure
  stops where the wrongness is, carrying the message, the node and a stack trace, and a debug session
  pauses ON the failing instruction with the frames standing.

---

## 6. Expressions

| | |
|---|---|
| arithmetic | `+` `-` `*` `/` `%` |
| comparison | `==` `!=` `<` `<=` `>` `>=` |
| logic | `&&` `\|\|` `!` |
| conditional | `cond ? a : b` |
| field | `p.x` |
| index | `xs[i]` |
| call | `f(name: a)` |
| record | `Point { x: 1, y: 2 }`, `p with { x: 5 }` |
| type test | `x is Int`, `x !is Point` |
| cast | `p as Vec2i`, `t as Vec3i { z: plane }` |
| optional | `a ?: b`, `a?.b` |

`&&` and `||` **short-circuit** — the right side is not evaluated when the left decides the answer. So does
`? :`, and so does `?:`.

Comparison works on numbers and on strings; `==` also works on records, lists, tiles and enum members.

### 6.1 `as` — reading one record as another

```vs
graph "casting"

type Vec3i { x: INT, y: INT, z: INT }
type Vec2i { x: INT, y: INT }

var Flat: INT = 0
var Deep: INT = 0

fn asVec(spot: TILE) -> Vec3i = spot as Vec3i { z: plane }

on start {
    val full = Vec3i { x: 3, y: 4, z: 5 }

    // Narrowing: `z` is simply dropped.
    val flat = full as Vec2i
    Flat = flat.x

    // A Tile is x/y/plane and a Vec3i is x/y/z, so the one pair whose names differ is written out.
    Deep = asVec(spot: tile(3200, 3210, 2)).z
}
```

**Fields are matched by NAME.** Every field of the target must be satisfied: a same-named field of the
source, or a rename saying which one it comes from. The source may be **wider** — the extra fields are
dropped — and may never be **narrower**, because a cast reinterprets what you have rather than conjuring
what you do not. Nothing is zero-filled.

A missing field is an error that names the cure:

```
'Vec2i' has nothing called 'z' to fill 'Vec3i.z' — name the field it comes from:
    as Vec3i { z: … }
```

### 6.2 `is` — asking what something is at run time

```vs
graph "sorting"

type Point { x: INT, y: INT }

var Numbers: INT = 0
var Words: INT = 0
var Placed: BOOL = false

on start {
    var numbers = 0
    var words = 0
    for item in [1, "a", 2, "b"] {
        if item is Int {
            numbers = numbers + 1
        }
        if item !is Int {
            words = words + 1
        }
    }
    Numbers = numbers
    Words = words

    val p = Point { x: 1, y: 2 }
    Placed = p is Point
}
```

**A test, never a narrowing.** `if x is Int { … }` does not make `x` an `INT` inside the branch.

It works for `Int`, `Float`, `Bool`, `String`, `List` and **any record**, including `Tile` and `Color`. It
is **refused** for `Item`, `NPC`, `Object`, `Skill` and any enum: those share a run-time form with `Int` or
`String`, so the answer would be about the underlying kind rather than the type you asked about.

Reach for it where a value's type is genuinely unknown. Everywhere else the static types already answer.

---

## 7. Optionals

**A plain `T` is never nothing.** That is the rule, and everything else here follows from it. A `Tile` in
hand is a tile; if a thing might not be there, its type says so with a `?`:

```vs
graph "optionals"

var Home: TILE? = null

fn nearestPost(to: TILE) -> TILE? = Home

on start {
    log(message: "ready")
}
```

**`T` flows into `T?`, and `T?` does not flow into `T`.** So the wire that used to carry a null into
something that could not take one is refused where it is drawn.

### 7.1 `if val` — take the value when it is there

```vs
graph "iflet"

fn nearest() -> TILE? = null

on start {
    if val t = nearest() {
        log(message: "at " + t.x)
    } else {
        log(message: "nothing there")
    }
}
```

**`t` is a `TILE`, not a `TILE?`** — inside the block it needs no further checking. The `else` is optional.

It binds one name. A second value means a second `if val`, or a `?:` for the ones that have an obvious
answer when they are missing.

### 7.2 `!= null` — narrowing a name you already have

When the value is already bound, comparing it to null narrows it in place, with no second name:

```vs
graph "nullcheck"

fn nearest() -> TILE? = null

on start {
    val spot = nearest()
    if spot != null {
        log(message: "at " + spot.x)
    }
    // `== null` proves it on the other side, so the narrowing goes to the else.
    if spot == null {
        log(message: "nothing there")
    } else {
        log(message: "at " + spot.x)
    }
}
```

It narrows across an `&&` chain too, and in an expression:

```vs
graph "nullcheck2"

fn nearest() -> TILE? = null

fn needs(t: TILE) -> INT = t.x

on start {
    val spot = nearest()

    // Everything after the test in the same condition sees the proved value.
    if spot != null && needs(t: spot) > 3 {
        log(message: "far")
    }

    // And in expression position.
    val a = spot != null ? needs(t: spot) : 0
    log(message: toText(value: a))
}
```

A **guard clause** narrows what comes after it, which is how a long function stays flat:

```vs
graph "nullcheck3"

fn nearest() -> TILE? = null

on start {
    val spot = nearest()
    if spot == null {
        return
    }
    // Reached only the other way, so `spot` is proved down here — for the rest of the body.
    log(message: "at " + spot.x)
}
```

An arm that cannot fall through — `return`, `break`, `continue` — is an arm the code below is never
reached from, so whatever the *other* arm proved is proved below. It works either way round: an `else`
that leaves proves the whole condition held, and an `if` that leaves proves its negation.

`||` narrows its right side, the way `&&` narrows its right side, because it is the same reasoning
inverted — the right of an `||` is only reached when the left came out false:

```vs
graph "orelse"

fn nearest() -> TILE? = null

on start {
    val spot = nearest()
    // `spot` is proved on the right of the `||`, and below the block because that block returns.
    if spot == null || spot.x > 3 {
        return
    }
    log(message: "at " + spot.x)
}
```

**It narrows a bound NAME, and only that.** A `val` or `var` local, yes. A **graph variable, no** — its cell
can be written from anywhere, including by something the branch itself calls, so a name proved here would
not stay proved. An **expression, no** — `if f() != null` has no name to rebind. Both of those are what
`if val` is for.

**And only when the whole condition is that one test**, for the `else` side: `if a != null && b` is reached
on its else when *either* half failed, so nothing is proved there — and for the same reason, an early
return under an `&&` chain proves nothing after it.

### 7.3 `?:` — or else

```vs
graph "orelse"

fn nearest() -> TILE? = null

on start {
    val where = nearest() ?: tile(3200, 3200, 0)
    log(message: "at " + where.x)
}
```

The result is never nothing, which is the point: a `TILE?` goes in and a `TILE` comes out. **Only the side
that is needed is worked out**, so an expensive fallback costs nothing on the passes where the value was
there.

It binds tighter than a comparison and looser than arithmetic, and chains rightwards — so `a ?: b + 1` adds
first, `a ?: b == 3` compares the result, and `a ?: b ?: c` takes the first of the three that is there.

#### `error(…)` — when there is no sensible fallback

Sometimes the answer to "and what if it is not there" is that the script is wrong and should stop.
`error(…)` is what goes on the right of `?:` in that case — an expression that never hands anything back,
so the whole thing is a plain value:

```vs
graph "must-be-there"

fn twice(n: INT) -> INT = n * 2

var Handlers: MAP<INT, fn(INT) -> INT> = _newMap()

// Declared to return a plain function, not an optional — which is only honest because the miss stops here
// rather than being passed on to every caller.
fn handlerFor(key: INT) -> fn(INT) -> INT =
    _mapAt(map: Handlers, key: key) ?: error(message: "no handler is registered for that key")

on start {
    Handlers = _mapWith(map: Handlers, key: 1, value: twice)
    val f = handlerFor(key: 1)
    log(message: "" + f(5))
}
```

Without it, a lookup that must succeed leaves you two bad options: invent a fallback that means nothing, or
declare the result optional and make every caller ask a question you already know the answer to. This is
the third one — and it is exactly Kotlin's `?: error("…")`, for the same reason.

**It is not an exception, and nothing catches it.** The script stops where the wrongness is, carrying the
message, the node it happened at and a stack trace; a debug session pauses ON the failing instruction with
the frames still standing, rather than unwinding to somewhere that has lost the context. For a script
driving a game client that is the useful behaviour — the alternative to a wrong value is not a caught
exception, it is stopping.

It costs nothing when it is not taken, because `?:` reaches the right-hand side only when the value really
was absent — so one in a hot path is free on every pass that had a value.

And it is not only an expression. **On its own line it is a statement**, which is the plain "this cannot
happen" guard:

```vs
graph "guard"

var Phase: STRING = "start"

on start {
    if Phase != "start" {
        error(message: "the phase was already " + Phase)
    }
    log(message: "going")
}
```

Nothing after it runs, exactly as with a `return`.

### 7.4 `?.` — read through something that might not be there

```vs
graph "safecall"

type Site { anchor: TILE, radius: INT }

fn siteFor(name: STRING) -> Site? = null

fn Site.tripled(self) -> INT = self.radius * 3

on start {
    val r = siteFor(name: "graahk")?.radius ?: 0
    val t = siteFor(name: "graahk")?.tripled() ?: 0
    log(message: "radius " + r + " " + t)
}
```

**The receiver is worked out once.** Written by hand as `siteFor(…) != null ? siteFor(…).radius : 0` that
call happens twice, and a query asked twice can answer differently; `?.` asks once.

The guard covers **one** step. `a?.b.c` reads `.c` off something that may be nothing — chain the guards
instead: `a?.b?.c`.

---

## 8. Numbers and text

**`INT` widens to `FLOAT` on its own, and the value really is converted.** Narrowing never happens by
itself, because it loses data and there are four different right answers:

```vs
graph "numbers"

var A: INT = 0
var B: INT = 0
var C: INT = 0
var D: INT = 0
var Ratio: FLOAT = 0.0

on start {
    A = floor(value: 2.9)        // 2   — the whole number at or below
    B = ceil(value: 2.1)         // 3   — at or above
    C = round(value: 2.5)        // 3   — nearest; a tie goes AWAY from zero
    D = toInt(value: 0 - 2.9)    // -2  — drop the fraction, keep the sign: the `(int)` cast

    // `a / b` on two INTs is INTEGER division. This is how you get the rest of the answer.
    Ratio = toFloat(value: 2) / 3
}
```

A whole number written into a float slot is fine — `Point { x: 4 }` where `x` is a `FLOAT` — because a
literal has no type until it is placed. It is stored as `4.0`.

**Arithmetic carries its operands' type.** `a * b` on two `INT`s is an `INT`, either operand being a `FLOAT`
makes it a `FLOAT`, and `%` is the remainder.

### Building text

`+` concatenates when a string is involved, which is what makes short messages pleasant:

```vs
graph "strings"

var Message: STRING = ""

on start {
    val n = 3
    Message = "found " + n + " of them"
}
```

Floats concatenate too — `"rate " + 0.75 + " each"` — but for anything past a couple of terms use
`text(…)`, which takes a template with `{name}` holes and one argument per hole. It reads better and it
puts the sentence in one place:

```vs
graph "formatting"

var Message: STRING = ""

on start {
    val kept = 4
    val rate = 0.75

    Message = text("kept {n} at {r} each", n: kept, r: rate)
}
```

`toText(value:)` turns one value into a string on its own, and renders nothing as `"null"` rather than as
an empty string — so a value that never arrived says so.

---

## 9. Files and JSON

A script can read and write files in a data folder of its own, and JSON is how it stores anything with a
shape. There is a verb for each direction and **one idea** for turning a document into your own types: `as`,
the same word that reads one record as another.

### 9.1 Reading a document as records

Declare the shape as ordinary records, then read the file as the outermost one.

```vs
graph "read-layout"

type Item { id: INT, name: STRING, qty: INT }
type Tab { tab: INT, title: STRING, items: LIST<Item> }
type Layout { version: INT, tabs: LIST<Tab> }

var Tabs: INT = 0

on start {
    if val doc = readJson(path: "bank/layout.json") as Layout {
        Tabs = _listCount(list: doc.tabs)
        for t in doc.tabs {
            log(message: text("{name} holds {n}", name: t.title, n: _listCount(list: t.items)))
        }
    }
}
```

What comes back is a real record. `doc.tabs[0].items[0].name` reads, `==` compares field by field, and
`is Item` answers yes — there is no separate "parsed" kind of value to convert out of.

**The cast is checked when the script is compiled**, against the declaration. A field whose type has no
JSON form is refused there and then, naming the field, rather than becoming a null halfway through a run.

**The rules are the record cast's rules**, because it is the record cast:

- **The document may be wider.** Keys no field names are dropped, exactly as `p as Vec2i` drops the fields
  `Vec2i` does not have.
- **It may never be narrower.** A key the record needs and the file has not got is an error that names the
  path to it. Nothing is zero-filled — a `0` you meant is indistinguishable from a `0` you forgot.
- **`T?` is how a field is allowed to be missing.** An optional field takes both a missing key and an
  explicit `null`.

```vs
graph "optional-fields"

type Row { id: INT, note: STRING? }

var Note: STRING = ""

on start {
    if val r = readJson(path: "rows/one.json") as Row {
        Note = r.note ?: "none"
    }
}
```

**Reading nothing gives nothing.** A file that is not there is an ordinary case — the first run, before
anything has been saved — so `readJson` hands back no document and the cast's type says so: the example
above is a `Row?`, which is why it is written with `if val`. A file that IS there and is not JSON is a
different thing, and stops the script saying where the text gave up.

### 9.2 What a record may be made of

| Declared as | Read from |
|---|---|
| `INT` | a whole number (`3.0` is fine, `3.5` is an error rather than a silent truncation) |
| `FLOAT` | any number |
| `STRING` | text |
| `BOOL` | `true` or `false` |
| `LIST<T>` | an array of `T` |
| `MAP<STRING, V>` | an object whose keys are not known in advance |
| a record | an object |
| a choice | text naming one of its members |
| `Json` | the raw subtree, unread — the escape hatch |
| `T?` | any of the above, or missing, or `null` |

Anything else — an `ENTITY`, a `WIDGET`, a function — is refused, because it has no written form to read
back. A record of yours called `Item`, `NPC`, `Object` or `Skill` is **your** record: a declared type wins
over the built-in kind of the same name.

### 9.3 Keys that are not names

A JSON key can be anything. When it is not a legal field name, say where the field comes from — the same
`{ … }` clause the record cast uses, with the key in quotes:

```vs
graph "odd-keys"

type Row { itemCount: INT, label: STRING }

var Seen: INT = 0

on start {
    val r = readJson(path: "counts.json") as Row { itemCount: "item count" }
    Seen = r?.itemCount ?: 0
}
```

Quoted means a JSON key; bare means a field of a source record. Both spellings are kept as written, so a
file comes back from a round trip exactly as it went in.

**The clause cannot be written in an `if` or `while` header.** A brace there is the block — the same rule
that stops `if ready { }` reading as a record literal — so the cast is bound first and the name tested
after, as above. Without a rename clause there is nothing to trip over, and
`if val doc = readJson(path: p) as Layout { … }` reads exactly as it looks.

### 9.4 Writing

Writing needs no type named anywhere. A record already carries its field names, so it writes as an object
with those keys in declaration order; a list writes as an array, a map as an object.

```vs
graph "write-layout"

type Item { id: INT, name: STRING, qty: INT }

on start {
    val items = [Item { id: 995, name: "Coins", qty: 12 }]

    writeJson(path: "bank/items.json", value: items)

    // Or as text, when it is going somewhere other than a file.
    log(message: jsonText(value: items, pretty: false))
}
```

### 9.5 Saving a `single`

A `single` is a record type plus one variable of it under the same name (§4.4), so there is nothing extra
to know: the variable holds a record, which writes as an object, and `as Run` names the type — which is
what the cast wanted anyway.

```vs
graph "save-state"

enum Phase { Gather, Store }

single Run {
    phase: Phase = Phase.Gather,
    laps: INT = 0,
}

on start {
    if val saved = readJson(path: "state/run.json") as Run {
        Run = saved
    }

    Run.laps = Run.laps + 1
    writeJson(path: "state/run.json", value: Run)
}
```

The `if val` is there because the file might not be there, not because a single is special — the first run
has nothing saved, and the declared defaults stand.

**A choice is checked against its members on the way in.** An enum member is its name at run time, so it
writes as `"Store"` and reads back by matching the declaration. A saved file outlives the code that wrote
it, and a `"Move"` surviving into a `Phase` that no longer has it would be a member nothing matches and
every `when` falls through — so it stops, naming the value and listing the members it could have been.

### 9.6 The verbs

| | |
|---|---|
| `readJson(path:)` | read and parse a file; nothing when it is not there |
| `writeJson(path:, value:, pretty:)` | serialise and write, replacing what was there |
| `parseJson(text:)` | parse text you already have |
| `jsonText(value:, pretty:)` | any value as JSON text |
| `readText(path:)` | the whole file; nothing when it is not there |
| `writeText(path:, text:)` | write text, replacing what was there |
| `fileExists(path:)` | is it there right now |
| `deleteFile(path:)` | remove it; false when there was nothing to remove |
| `listFiles(folder:)` | the file names directly inside a folder, sorted |
| `listFolders(folder:)` | the folder names directly inside a folder, sorted |

Paths are **relative to the script's own data folder and cannot leave it** — a leading `/` or a `..` is
refused.

**Folders nest as deep as you like**, and every folder on the way is created on write. That is how a script
organises what it saves — a folder per account, per run, per task — and `listFolders` is how it finds them
again. Neither listing recurses: each answers "what is directly in here", so a script that wants the whole
tree walks it.

```vs
graph "runs"

type Run { points: INT }

on start {
    writeJson(path: "runs/2026-08/gotr.json", value: Run { points: 40 })

    for month in listFolders(folder: "runs") {
        for name in listFiles(folder: text("runs/{m}", m: month)) {
            log(message: text("{m}/{n}", m: month, n: name))
        }
    }
}
```

---

## 10. The vocabulary

Every verb below comes with the language and needs no import. They are ordinary calls: `name(pin: value)`.

**Lists and maps** — §3.4 and §3.5. **Files and JSON** — §9.6.

**Numbers**

| | |
|---|---|
| `abs(value:)` | magnitude, keeping the type |
| `min(a:, b:)` `max(a:, b:)` | the smaller / larger |
| `floor(value:)` `ceil(value:)` `round(value:)` `toInt(value:)` | FLOAT → INT, four ways |
| `toFloat(value:)` | INT → FLOAT |
| `sqrt(value:)` `pow(base:, exponent:)` | roots and powers |
| `sin(radians:)` `cos(radians:)` `pi()` | trigonometry |

**Text**

| | |
|---|---|
| `text(template, name: value, …)` | a template with `{name}` holes |
| `toText(value:)` | any one value as a string |

**Flow and time**

| | |
|---|---|
| `now()` | milliseconds, monotonic enough to time things |
| `delay(ms:)` | wait, without holding the client |
| `invoke(f, …)` | call a function value that is not held by a name |
| `log(message:)` | write a line to the script console |

There is no import for these and no way to shadow one: a node wins its name, so declaring
`fn INT.toText(self)` does not take `toText` away.

---

## 11. Running a script

A script runs as a **fiber**, and the client gives every fiber a slice of each game tick. What that means
for authoring is one rule:

**A loop must wait.** `delay(ms:)` — or any verb that waits for the game — is what lets the tick end. A loop
that never waits is stopped, and says so:

```
runaway fiber: ran for 100 consecutive ticks without waiting — a loop with no Delay/Wait in it?
```

So the shape of a script is a loop that decides one thing, does it, and waits:

```vs
graph "loop-shape"

var Trips: INT = 0

on start {
    while Trips < 10 {
        Trips += 1
        log(message: text("trip {n}", n: Trips))
        delay(ms: 600)
    }
}
```

`on tick` and `on render` are the other side of that bargain: they are called *for* you, they may not wait,
and they must return promptly. Read and decide there; act in the loop.

---

## 12. Comments

```vs
graph "commented"

// An ordinary comment.

/*
 * A block comment.
 */

/**
 * A DOC comment: it belongs to the declaration below it and shows on hover in the IDE.
 *
 * @param n how many to count
 * @return the total
 */
fn total(n: INT) -> INT = n * 2

var Out: INT = 0

on start {
    Out = total(n: 3)
}
```

`@param` and `@return` are recognised; the IDE completes parameter names after `@param`.

A comment belongs to the text file. It is not part of the script, and it does not appear on the canvas —
`@note` is the remark that does.

---

## 13. Annotations

Two exist. Both are metadata, and neither changes what a script does.

```vs
graph "annotated"

var Out: INT = 0

on start {
    @note("the id keeps this node stable across edits")
    @id(42)
    Out = 1
}
```

`@id` pins a node's identity so the canvas and a diff agree across a round trip; `@note` is a remark that
survives into the graph. An unrecognised annotation is kept as written and warned about.

---

## 14. Conventions

These are not enforced. They are what the scripts in this repo do, and why.

### A graph `var` is configuration, or state a second handler reads

Nothing else. This is the convention most often got wrong, and it is worth stating as a rule with a test.

A counter, an accumulator, a flag — anything only one handler touches — is a **local**, declared just above
the loop that uses it:

```vs
graph "local-state"

/** Configuration: a graph var, because a human is meant to edit it. */
var Reach: INT = 12

on start {
    // State: a local, because nothing outside this handler reads it.
    var chopped = 0
    var trips = 0

    while trips < 5 {
        chopped += Reach
        trips += 1
        delay(ms: 600)
    }

    log(message: text("{n} in {t} trips", n: chopped, t: trips))
}
```

Writing those two as graph `var`s costs three things, all silent: they are one cell for the whole run, so a
second `on start`-shaped path re-enters onto the same state; a function that recursed on one would find the
inner call had overwritten it; and a reader has to check the whole document to learn that nothing else
touches them.

**The test is whether you can name the second reader.** `on render` drawing an overlay, `on tick` deciding
something the loop acts on, an importer reaching it through an alias — those are real answers, and then it
is a graph `var`. "It felt like the script's state" is not.

**Configuration at the top, as `var`s with comments.** A name, a radius, an item — anything a reader might
want to change should be one line near the top, not buried in a call. The comment explains the choice, not
the syntax. These are graph `var`s precisely because a human, or the canvas, edits them from outside.

**Ask the world, do not remember.** A script that tracks what it thinks it is doing is wrong the moment
anything unexpected happens — a disconnect, a hand-moved character, a restart mid-trip. Read the state that
matters from the game each pass.

**Name things by name, not by id.** `itemByName(name: "Bones")` survives an update; `526` does not, and
tells the next reader nothing. Reach for an id only when the name genuinely cannot distinguish what you
mean — and say why in a comment when you do.

**A document is a component.** Its `var`s are its state, its `fn`s are its methods, and its `on`
handlers are its own clock. A bundle of state every function in a file needs is a graph `var` of a record
type, not a parameter threaded through sixteen signatures:

```vs
graph "singleton"

enum Phase { Idle, Working }

type State { phase: Phase, laps: INT }

var S: State = State { phase: Phase.Idle, laps: 0 }

fn sense() -> Phase = S.phase

fn fn(phase: Phase) {
    S.phase = phase
    S.laps += 1
}

on start {
    fn(phase: sense())
}
```

Take the parameter instead when a function genuinely has to work on *some* record rather than *the* one.
The test is whether two different values are ever passed.

**Write functions over a description, not over a special case.** A record of "what we are hunting" beats two
loose variables, because the pair changes together.

**Comment the WHY.** The code says what it does. A comment earns its place by saying what would otherwise
have to be rediscovered: why this order, why this number, what was tried and did not work.

**Prefer `val`.** Use `var` when something accumulates, and prefer a local `var` over a graph `var` for
anything that belongs to one call.

---

## 15. Where to look next

| | |
|---|---|
| what the printer gives back, and the two surfaces | `CANONICAL_FORM.md` |
| what the language cannot do yet | `GAPS.md` |
| worked examples that run in the test suite | `src/test/resources/vs/examples/` |
| real scripts | the client's `scripts/` folder |
