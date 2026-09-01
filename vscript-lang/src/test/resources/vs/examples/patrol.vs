graph "patrol"

// The features that landed after the phase list closed: enums, `when`, extensions, destructuring by name,
// and bodies without braces. Everything computed lands in a graph variable, because those are what a run
// leaves behind and therefore what `ExamplesTest` can read back without a host node to observe with.
//
// No game nodes at all, so this lowers, validates, compiles and RUNS in the language's own tests.

/**
 * Where the patrol is up to.
 *
 * A member is its NAME at run time — `Phase.Walk` is the string "Walk" — which is why a saved document
 * stays legible and why comparison is ordinary equality. It is also why `is` and `as` are refused on one:
 * a bare string carries no tag to test against.
 */
export enum Phase { Walk, Fight, Rest }

/** Extensions. The receiver arrives as `self`, and this is an ordinary function underneath. */
export fn INT.double(self) -> INT = self * 2

export fn INT.clampTo(self, high: INT) -> INT = self > high ? high : self

/** Several results, so the destructure below has something to take apart by name. */
export fn report(step: INT) -> (Index: INT, Label: STRING, Cost: INT) {
    return step, "step " + step, step.double()
}

// ---- what the run leaves behind -----------------------------------------------------------------------

export var Cost: INT = 0

export var Label: STRING = ""

export var Story: STRING = ""

export var Clamped: INT = 0

export var Steps: INT = 0

on start {
    // BY NAME, and out of order: every bare name is one of `report`'s outputs, so this binds those pins
    // rather than the first two. `cost: Cost` renames one, and one rename would make the whole list
    // by-name even if the others were not already output names.
    val (label, cost: Cost) = report(step: 3)
    Label = label
    Cost = cost

    // Extensions chain, and the second one takes an argument beside the receiver.
    Clamped = 9.double().clampTo(high: 12)

    // A loop accumulating into a local `var`. TWO statements, so it needs braces — a braceless body holds
    // exactly one, and writing this without them would have left `i` unchanged and looped forever.
    var total = 0
    var i = 0
    while i < 3 {
        total = total + i.double()
        i = i + 1
    }
    Steps = total

    // `when` over an enum. Every member is covered, so there is no `else` and no exhaustiveness warning —
    // add a member to `Phase` and this is one of the places that will be reported.
    var at: Phase = Phase.Fight
    when at {
        Phase.Walk -> Story = "walking"
        Phase.Fight -> Story = "fighting"
        Phase.Rest -> Story = "resting"
    }

    // `when` with no subject is a chain of conditions, first match wins. Braces optional here and on the
    // `if` below — a bare body is exactly one statement.
    when {
        Cost > 100 -> Story = Story + " (expensive)"
        Cost > 4 -> Story = Story + " (steady)"
        else -> Story = Story + " (cheap)"
    }

    if Clamped > 10 Story = Story + " !"
    else Story = Story + " ."
}
