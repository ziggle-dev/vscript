package dev.ziggle.vscript.model

/**
 * References: values that name ONE individual.
 *
 * ### Why these exist
 *
 * An id names a KIND. A pen of eleven cows is one id; two piles of bones on two tiles are one id; four
 * sharks in your bag are one id. Almost every interesting question is about an individual — *that* cow,
 * *that* pile, *that* slot — and an id cannot express one. Every time a graph passed an id where it meant
 * an individual, something downstream had to guess which one, and "guess" always came out as "whichever is
 * nearest right now": a marker that hopped between cows as they wandered, a highlight that flickered
 * between equidistant objects, a take that reconsidered its target every pass.
 *
 * So the rule the node API follows is: anything that FINDS something hands back a reference, and an id is
 * recovered from it explicitly through an Info node. That makes the narrowing from individual to kind a
 * visible step in the graph rather than an accident of typing.
 *
 * ### Two of them, not one and not four
 *
 * Scene things share a domain — they have a tile, a distance, a name, and can be drawn — so they share one
 * type and separate by [EntityRef.Kind]. Container items share none of that and have their own identity
 * (a slot), so folding them in would produce an Info node whose outputs were half permanently empty.
 *
 * ### Pure data, deliberately
 *
 * No engine types here: this package has none, and these are values that travel through the VM's constant
 * pool and registers like any other. Turning one back into a live thing is `dev.ziggle.nodes.RefResolve`'s
 * job, which is the one place that knows both halves of the round trip.
 */

/** Which container a stack lives in. */
enum class Container { INVENTORY, BANK, EQUIPMENT }

/**
 * One live thing in the scene.
 *
 * Identity differs by kind because the game gives different guarantees. An NPC gets the server's own
 * **index**, which is unique among living NPCs and follows it as it moves. Scenery and ground piles have no
 * index and need none: they do not move, so the tile is part of what they ARE, and id-plus-tile pins one.
 */
data class EntityRef(
    val kind: Kind,
    /** Server index — NPCs only. -1 elsewhere. */
    val index: Int = -1,
    /** Object or item id — scenery and ground piles only. 0 for NPCs. */
    val id: Int = 0,
    val tx: Int = 0,
    val ty: Int = 0,
    val tz: Int = 0,
) {
    enum class Kind { NPC, OBJECT, GROUND_ITEM }

    /** Does this name anything at all? A ref to nothing is the honest result of a find that failed. */
    val valid: Boolean get() = if (kind == Kind.NPC) index >= 0 else id > 0

    companion object {
        /** Names nothing — what a find returns when it found nothing. */
        val NONE = EntityRef(Kind.NPC, index = -1)
    }
}

/**
 * One stack in a container.
 *
 * The **slot** is the identity, not the id: two stacks of the same item are two slots, and a slot goes on
 * meaning something after the item leaves it — which is what makes "the slot I just emptied" expressible.
 * [id] rides along as what was in it when the reference was taken, so a consumer can tell that a slot now
 * holds something else rather than silently acting on the wrong item.
 *
 * ### The one exception: a CLOSED bank
 *
 * A shut bank's container is unloaded, so its contents are read back out of the persisted snapshot — real
 * stacks with real ids, and no slots, because a remembered stack was never in one. Those are exactly the
 * stacks a script wants when it asks "how many of these do we own" from the other side of the map, and
 * requiring a slot threw every one of them away: `Bank Items` answered the empty list whenever the bank was
 * shut, which reads as an empty bank rather than as an unanswerable question. A planner totalling stock got
 * zero for a bank holding two hundred and forty-eight of the thing it was counting.
 *
 * **In a bank an id IS an identity**, which is what makes this sound rather than a fudge: a bank holds one
 * stack per item id, so naming a bank stack by its id names exactly one thing — the property that fails for
 * an inventory, where six sharks are six slots, and which is why this is allowed for [Container.BANK] alone.
 *
 * Such a reference can be READ and cannot be acted on, which is not a new rule to remember: withdrawing
 * needs the bank open, and once it is open every stack has a slot again.
 */
data class ItemRef(
    val container: Container,
    val slot: Int,
    val id: Int = 0,
) {
    val valid: Boolean get() = slot >= 0 || (container == Container.BANK && id > 0)

    companion object {
        val NONE = ItemRef(Container.INVENTORY, slot = -1)
    }
}
