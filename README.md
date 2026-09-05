# vscript

A small embeddable scripting language for driving a host application, with a graph editor and a text
front-end over the same documents.

A script is a **graph**: nodes wired together, executed by a register VM. The same document can be edited as
a canvas or as text — they are two views of one model, not two formats — and the host supplies the verbs.
The language itself knows nothing about the application it drives.

## What is here

| module | what it is |
|---|---|
| `vscript-lang` | the language: lexer, parser, resolver, compiler, register VM, and the graph/text document model |
| `vscript-runtime` | running compiled scripts — fibers, scheduling, host calls, the debugger surface |
| `vscript-ui` | an immediate-mode widget kit built on Dear ImGui |
| `editor-graph` | the node canvas: palette, wiring, value editors, outline |
| `editor-text` | the text editor for the same documents |
| `editor-host` | what an embedding application implements to host either editor |
| `vscript-runview` | a read-only view of a running script, for embedding in a host's own UI |
| `vscript-shell` | a standalone GLFW window that opens the editors without a host |

## Embedding it

A host does three things: declare its verbs as a **node catalogue**, implement `editor-host` so the editor
can find documents, and run the VM. Nothing above `vscript-lang` is required — a host that only wants to
execute scripts needs the language and the runtime.

Types are the host's too. The language ships `BOOL`, `INT`, `FLOAT`, `STRING`, `LIST`, `MAP`, `FUNCTION`,
`ENUM` and an exec wire; anything domain-shaped — an item, a position, a machine — is a record or an enum
the host declares. That separation is enforced: `vscript-shell`'s `DomainFreeTest` fails the build if the
language picks up an import from a host's own packages.

## Building

```
./gradlew build          # compile and test
./gradlew :vscript-shell:run   # the standalone editor
```

JDK 21 to develop on; the modules target 17.

## Consuming it

Releases are published to the **`maven` branch of this repository**. It is public, so the tree is served
over `raw.githubusercontent` with **no credentials** — no token, no account, no third-party registry:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://raw.githubusercontent.com/ziggle-dev/vscript/maven/' }
}
dependencies {
    implementation 'dev.ziggle:vscript:1.2.0'
}
```

The modules are `vscript` (the language itself, from `:vscript-lang`), `vscript-ui`, `vscript-runtime`,
`vscript-runview`, `editor-host`, `editor-graph`, `editor-text` and `vscript-shell`. `gson` is `compileOnly`
here and is the consumer's to supply — deliberately, so nothing is forced onto a runtime classpath that
already has one.

Each release is also a [GitHub Release](../../releases) with the jars attached, for reading or vendoring.
`.github/workflows/release.yml` cuts both.

Working ON the language rather than with it? `includeBuild` a checkout and skip releases entirely — that is
what osrsx-client, osrsx-sdk and osrsx-scripts all do, so an edit to the lexer reaches them with no publish
step and no version bump.

## The language

`docs/LANGUAGE.md` is the reference, and every example in it is compiled by `DocsTest` — so the document
cannot drift from the language it documents.

A taste:

```
graph "example"

export var Count: INT = 0

on start {
    log(message: "up")
    while Count < 10 {
        Count = Count + 1
    }
}
```

## Licence

See `LICENSE`.
