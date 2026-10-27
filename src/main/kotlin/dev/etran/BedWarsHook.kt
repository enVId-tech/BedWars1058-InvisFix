package dev.etran

import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** Reflection helper: works with com.andrei1058.bedwars and forks that use com.tomkeuper.bedwars. */
object Reflect {
    private fun assignable(param: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !param.isPrimitive
        if (param.isInstance(arg)) return true
        return when (param) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Boolean.TYPE -> arg is Boolean
            else -> false
        }
    }

    fun call(target: Any, method: String, vararg args: Any?): Any? = try {
        val m = target.javaClass.methods.firstOrNull { m ->
            m.name == method && m.parameterTypes.size == args.size &&
                m.parameterTypes.indices.all { assignable(m.parameterTypes[it], args[it]) }
        }
        if (m == null) null else {
            m.isAccessible = true
            m.invoke(target, *args)
        }
    } catch (_: Throwable) {
        null
    }
}

object BedWarsHook {
    private val PREFIXES = listOf("com.andrei1058.bedwars", "com.tomkeuper.bedwars")
    lateinit var prefix: String
        private set
    private lateinit var api: Any

    fun init(): Boolean {
        for (p in PREFIXES) {
            val cls = try {
                Class.forName("$p.api.BedWars")
            } catch (_: Throwable) {
                continue
            }
            val reg = Bukkit.getServicesManager().getRegistration(cls) ?: continue
            prefix = p
            api = reg.provider as Any
            return true
        }
        return false
    }

    fun arenaOf(p: Player): Any? {
        val util = Reflect.call(api, "getArenaUtil") ?: return null
        return Reflect.call(util, "getArenaByPlayer", p)
    }

    fun isPlaying(arena: Any): Boolean = (Reflect.call(arena, "getStatus") as? Enum<*>)?.name == "playing"

    fun isPlayer(arena: Any, p: Player): Boolean = Reflect.call(arena, "isPlayer", p) as? Boolean ?: false

    /** Team name of [p] in [arena], or "" for spectators / players without a team. */
    fun teamName(arena: Any, p: Player): String {
        val team = Reflect.call(arena, "getTeam", p) ?: return ""
        return (Reflect.call(team, "getName") as? String) ?: team.toString()
    }

    /** BedWars' own bookkeeping of invisible players; removing the entry stops it from expiring them later. */
    @Suppress("UNCHECKED_CAST")
    fun clearShowTime(arena: Any, p: Player) {
        try {
            (Reflect.call(arena, "getShowTime") as? MutableMap<Any?, Any?>)?.remove(p)
        } catch (_: Throwable) {
        }
    }

    /** Fires BedWars' own "invisibility removed" event so it restores the player's team / tab formatting. */
    fun fireInvisRemoved(p: Player) {
        try {
            val arena = arenaOf(p) ?: return
            val team = Reflect.call(arena, "getTeam", p) ?: return
            val base = "$prefix.api.events.player.PlayerInvisibilityPotionEvent"
            val type = Class.forName("$base\$Type").enumConstants.first { (it as Enum<*>).name == "REMOVED" }
            val ctor = Class.forName(base).constructors.first { it.parameterTypes.size == 4 }
            Bukkit.getPluginManager().callEvent(ctor.newInstance(type, team, p, arena) as org.bukkit.event.Event)
        } catch (_: Throwable) {
        }
    }

    /** BedWars' own armor-hiding packets (used when ProtocolLib is not installed). */
    fun setArmorHidden(victim: Player, viewer: Player, hidden: Boolean) {
        val vs = Reflect.call(api, "getVersionSupport") ?: return
        Reflect.call(vs, if (hidden) "hideArmor" else "showArmor", victim, viewer)
    }
}
