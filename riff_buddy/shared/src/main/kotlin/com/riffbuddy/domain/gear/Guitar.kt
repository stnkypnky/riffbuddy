package com.riffbuddy.domain.gear

/** A pickup position the selector can choose. */
data class PickupPosition(val label: String, val description: String = "")

/**
 * A guitar, in the terms a tone card needs.
 *
 * Pickups matter more than anything else here: "bridge humbucker" and "neck
 * single-coil" want opposite advice from the same amp, so a card that cannot
 * name the position it assumes is a card nobody can act on.
 */
data class GuitarProfile(
    val make: String,
    val model: String,
    val stringCount: Int = 6,
    val pickups: List<PickupPosition> = emptyList(),
    val controls: List<String> = emptyList(),
    val hasCoilSplit: Boolean = false,
    val notes: String = "",
) {
    val displayName: String get() = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * The ESP LTD EC-256, taken from the published spec rather than from memory,
 * like the Mustang LT25, which came off Fender's manual.
 *
 * Two passive humbuckers, a three-way toggle, one volume and one tone, and *no*
 * coil-split. Coil-split is a control some guitars have and this model does not,
 * which is why [notes] asks the player to check: a card that suggests splitting
 * a pickup that cannot be split is a card that wastes time at the amp.
 */
object EspLtdEc256 {
    val profile = GuitarProfile(
        make = "ESP LTD",
        model = "EC-256",
        stringCount = 6,
        pickups = listOf(
            PickupPosition("Bridge", "Humbucker — tighter and brighter, for riffs and lead"),
            PickupPosition("Both", "Humbuckers together — rounder, good for rhythm"),
            PickupPosition("Neck", "Humbucker — warm and thick, for clean and lead"),
        ),
        controls = listOf("Volume", "Tone", "Pickup selector"),
        hasCoilSplit = false,
        notes = "Standard EC-256 spec: two humbuckers, no coil-split. Check it matches yours.",
    )
}
