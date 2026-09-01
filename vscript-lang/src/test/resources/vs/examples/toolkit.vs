graph "toolkit"

// A BARREL: one front door for the documents beside it, so a caller writes one import instead of three.
//
// Nothing here can call what it forwards. The names go PAST this file rather than into it, which is what
// separates a re-export from an import followed by an export — and what stops a barrel quietly acquiring
// a vocabulary of its own.
//
// KNOWN LIMIT: a re-exported record's FIELD types are requalified one hop. A record declared here whose
// field is typed through a document THIS one imports crosses fine; one typed through a document the
// SOURCE imports arrives still spelled in the source's vocabulary, and the importer has to name that
// document itself. `shapes.Leg` avoids it deliberately.

/** Everything `geometry` offers, under its own spelling. */
export * from "geometry"

/** ...and exactly these two out of `shapes`, because the rest is that document's own business. */
export { Leg, Route, legLength } from "shapes"

/** A default is named on the way through, since `default` is not a name a caller could use here. */
export { default as slack } from "shapes"
