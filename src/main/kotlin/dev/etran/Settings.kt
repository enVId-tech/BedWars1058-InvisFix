package dev.etran

import org.bukkit.configuration.file.FileConfiguration

/** Immutable snapshot of config.yml; rebuilt on every reload / in-game edit. */
class Settings(c: FileConfiguration) {
    val onlyInBedwars = c.getBoolean("only-in-bedwars", true)

    val hideNametags = c.getBoolean("nametags.hide", true)
    val hideParticles = c.getBoolean("particles.hide", true)
    val revealOnPlayerDamage = c.getBoolean("damage.reveal-on-player-damage", false)
    val revealOnAnyDamage = c.getBoolean("damage.reveal-on-any-damage", false)

    val hideArmor = c.getBoolean("armor.hide", true)
    val armorHideFromTeammates = c.getBoolean("armor.hide-from-teammates", false)

    val hideWalkingSounds = c.getBoolean("sounds.hide-walking", true)
    val soundsHideFromTeammates = c.getBoolean("sounds.hide-from-teammates", true)

    val footsteps = c.getBoolean("footsteps.enabled", true)
    val footstepStride = c.getDouble("footsteps.stride", 0.6).coerceIn(0.2, 3.0)
    val footstepWhileSneaking = c.getBoolean("footsteps.while-sneaking", true)
    val footstepParticle = c.getString("footsteps.particle", "AUTO") ?: "AUTO"
    val footstepCount = c.getInt("footsteps.count", 1).coerceIn(1, 10)
}
