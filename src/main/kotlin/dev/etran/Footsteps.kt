package dev.etran

import org.bukkit.Color
import org.bukkit.Effect
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

/** Draws footprints behind invisible players. Works on every version (legacy Effect or modern Particle). */
class Footsteps {
    private class Last(var loc: Location, var left: Boolean)

    private val last = HashMap<UUID, Last>()

    private var legacy: Effect? = null
    private var modernKey: String? = null
    private var particle: Any? = null
    private var spawn: Method? = null
    private var data: Any? = null

    fun forget(id: UUID) {
        last.remove(id)
    }

    fun clear() {
        last.clear()
    }

    /** Resolves what to draw for the configured name. Called on enable and every reload. */
    fun configure(name: String) {
        if (modernKey == name) return
        modernKey = name
        legacy = null
        particle = null
        spawn = null
        data = null

        val wanted = name.trim().uppercase()
        if (wanted == "AUTO" || wanted == "FOOTSTEP") {
            try {
                legacy = Effect.valueOf("FOOTSTEP") // 1.8 - 1.12
                return
            } catch (_: Throwable) {
            }
        }
        try {
            val pc = Class.forName("org.bukkit.Particle")
            val candidates = if (wanted == "AUTO" || wanted == "FOOTSTEP") listOf("DUST", "REDSTONE") else listOf(wanted)
            for (n in candidates) {
                val value = pc.enumConstants?.firstOrNull { (it as Enum<*>).name == n } ?: continue
                particle = value
                if (n == "DUST" || n == "REDSTONE") {
                    val dc = Class.forName("org.bukkit.Particle\$DustOptions")
                    data = dc.getConstructor(Color::class.java, java.lang.Float.TYPE)
                        .newInstance(Color.fromRGB(105, 105, 105), 0.8f)
                }
                break
            }
            spawn = World::class.java.methods.firstOrNull {
                it.name == "spawnParticle" && it.parameterTypes.size == 8 &&
                    it.parameterTypes[0] == pc && it.parameterTypes[1] == Location::class.java
            }
        } catch (_: Throwable) {
        }
    }

    /** Called once per tick for every invisible player. */
    fun tick(p: Player, s: Settings) {
        if (!s.footsteps) return
        if (!p.isOnGround) return
        if (p.isSneaking && !s.footstepWhileSneaking) return
        val now = p.location
        val l = last[p.uniqueId]
        if (l == null || l.loc.world != now.world) {
            last[p.uniqueId] = Last(now.clone(), false)
            return
        }
        val dx = now.x - l.loc.x
        val dz = now.z - l.loc.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        if (dist < s.footstepStride) return

        // Foot offset to the side of the direction of travel, alternating left / right.
        val nx = dx / dist
        val nz = dz / dist
        val side = if (l.left) 0.14 else -0.14
        val at = Location(now.world, now.x - nz * side, now.y + 0.02, now.z + nx * side)
        draw(at, s)
        l.left = !l.left
        l.loc = now.clone()
    }

    private fun draw(at: Location, s: Settings) {
        val w = at.world ?: return
        try {
            val eff = legacy
            if (eff != null) {
                for (i in 0 until s.footstepCount) w.playEffect(at, eff, 0)
                return
            }
            val m = spawn ?: return
            m.invoke(w, particle, at, s.footstepCount, 0.0, 0.0, 0.0, 0.0, data)
        } catch (_: Throwable) {
        }
    }
}
