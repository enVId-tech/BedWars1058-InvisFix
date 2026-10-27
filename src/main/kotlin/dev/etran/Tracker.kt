package dev.etran

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scoreboard.NameTagVisibility
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Knows who is invisible right now. Updated on the main thread once per tick and read by the packet
 * listeners (which run on other threads), hence the concurrent maps.
 */
class Tracker(private val plugin: InvisFixPlugin) {
    class Snap(val owner: UUID, val world: String, val x: Double, val y: Double, val z: Double, val team: String)

    /** entity id -> snapshot of every invisible player. */
    val invisible = ConcurrentHashMap<Int, Snap>()

    /** player uuid -> team name ("" = spectator / no team), kept while anybody is invisible. */
    val teamOf = ConcurrentHashMap<UUID, String>()

    val footsteps = Footsteps()

    /** True when ProtocolLib is installed; without it armor falls back to BedWars' own hide / show packets. */
    var protocolLib = false

    private val invisType: PotionEffectType? = PotionEffectType.INVISIBILITY

    /** Scoreboards on which we hid each invisible player's name tag. */
    private val tagBoards = HashMap<UUID, MutableSet<Scoreboard>>()
    private var ticks = 0L

    private fun tagTeam(entityId: Int) = "ifx_$entityId".take(16)

    /** Name tag: put the player in a "never show name tag" team on every viewer's scoreboard. */
    private fun applyTags(p: Player, force: Boolean) {
        val name = tagTeam(p.entityId)
        val done = tagBoards.getOrPut(p.uniqueId) { HashSet() }
        val boards = HashSet<Scoreboard>()
        for (v in p.world.players) if (v != p) boards.add(v.scoreboard)
        for (b in boards) {
            try {
                var t = b.getTeam(name)
                if (t == null) {
                    t = b.registerNewTeam(name)
                    t.nameTagVisibility = NameTagVisibility.NEVER
                }
                if (t.hasEntry(p.name)) {
                    if (!force) {
                        done.add(b)
                        continue
                    }
                    t.removeEntry(p.name) // re-assert in case another team packet moved the player out of ours
                }
                t.addEntry(p.name)
                done.add(b)
            } catch (_: Throwable) {
            }
        }
    }

    private fun clearTags(id: UUID, entityId: Int) {
        val boards = tagBoards.remove(id) ?: return
        val name = tagTeam(entityId)
        for (b in boards) {
            try {
                b.getTeam(name)?.unregister()
            } catch (_: Throwable) {
            }
        }
    }

    /** Ends [p]'s invisibility right now (used by the damage options). */
    fun reveal(p: Player) {
        val type = invisType ?: return
        BedWarsHook.arenaOf(p)?.let { BedWarsHook.clearShowTime(it, p) }
        p.removePotionEffect(type)
    }

    fun isInvisible(p: Player): Boolean = invisible.containsKey(p.entityId)

    fun start() {
        footsteps.configure(plugin.settings.footstepParticle)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { scan() }, 1L, 1L)
    }

    /** Do [viewer] and [owner] play on the same team? */
    fun sameTeam(viewer: UUID, owner: Snap): Boolean {
        val t = teamOf[viewer] ?: return false
        return t.isNotEmpty() && t == owner.team
    }

    private fun eligible(p: Player): Pair<Boolean, String> {
        if (!plugin.settings.onlyInBedwars) return Pair(true, "")
        val arena = BedWarsHook.arenaOf(p) ?: return Pair(false, "")
        if (!BedWarsHook.isPlaying(arena) || !BedWarsHook.isPlayer(arena, p)) return Pair(false, "")
        return Pair(true, BedWarsHook.teamName(arena, p))
    }

    private fun scan() {
        val s = plugin.settings
        footsteps.configure(s.footstepParticle)
        val type = invisType ?: return
        val online = Bukkit.getOnlinePlayers()

        val before = HashSet(invisible.keys)
        val now = HashMap<Int, Snap>()
        val players = HashMap<Int, Player>()
        for (p in online) {
            if (!p.hasPotionEffect(type)) continue
            val (ok, team) = eligible(p)
            if (!ok) continue
            if (s.hideParticles) {
                val eff = p.activePotionEffects.firstOrNull { it.type == type }
                if (eff != null && eff.hasParticles()) {
                    // Same effect, same duration, but without the swirl particles.
                    p.addPotionEffect(PotionEffect(type, eff.duration, eff.amplifier, eff.isAmbient, false), true)
                }
            }
            val l = p.location
            now[p.entityId] = Snap(p.uniqueId, l.world?.name ?: "", l.x, l.y, l.z, team)
            players[p.entityId] = p
        }

        // Teams of everybody, so the packet listeners can tell teammates from enemies and spectators.
        if (now.isNotEmpty()) {
            val alive = HashSet<UUID>()
            for (p in online) {
                alive.add(p.uniqueId)
                if (!s.onlyInBedwars) {
                    teamOf[p.uniqueId] = ""
                    continue
                }
                val arena = BedWarsHook.arenaOf(p)
                teamOf[p.uniqueId] = if (arena != null && BedWarsHook.isPlayer(arena, p)) BedWarsHook.teamName(arena, p) else ""
            }
            teamOf.keys.retainAll(alive)
        } else {
            teamOf.clear()
        }

        for (id in before) if (!now.containsKey(id)) invisible.remove(id)
        invisible.putAll(now)

        // State changes: make clients re-read the armor (the packet filter decides what they get).
        for ((id, p) in players) if (!before.contains(id)) refreshArmor(p)
        for (id in before) {
            if (now.containsKey(id)) continue
            Bukkit.getOnlinePlayers().firstOrNull { it.entityId == id }?.let {
                refreshArmor(it)
                footsteps.forget(it.uniqueId)
                clearTags(it.uniqueId, id)
                BedWarsHook.fireInvisRemoved(it) // BedWars puts the player back into their team / tab format
            }
        }

        ticks++
        // Without ProtocolLib nothing filters armor packets, so hide it again regularly.
        if (!protocolLib && s.hideArmor && ticks % 20L == 0L) for ((_, p) in players) refreshArmor(p)
        for ((_, p) in players) {
            footsteps.tick(p, s)
            if (s.hideNametags) applyTags(p, force = ticks % 40L == 0L)
        }
    }

    /** Show / hide [target]'s armor to everybody who can see them, with or without ProtocolLib. */
    private fun refreshArmor(target: Player) {
        if (protocolLib) {
            Packets.refreshArmor(target)
            return
        }
        val s = plugin.settings
        val owner = invisible[target.entityId]
        for (v in target.world.players) {
            if (v == target) continue
            val hide = owner != null && s.hideArmor && (s.armorHideFromTeammates || !sameTeam(v.uniqueId, owner))
            BedWarsHook.setArmorHidden(target, v, hide)
        }
    }

    fun forget(p: Player) {
        invisible.remove(p.entityId)
        clearTags(p.uniqueId, p.entityId)
        teamOf.remove(p.uniqueId)
        footsteps.forget(p.uniqueId)
    }

    /** Plugin shutdown: show everybody's real armor again. */
    fun shutdown() {
        val ids = HashSet(invisible.keys)
        invisible.clear()
        teamOf.clear()
        footsteps.clear()
        for (id in ids) {
            Bukkit.getOnlinePlayers().firstOrNull { it.entityId == id }?.let {
                refreshArmor(it)
                clearTags(it.uniqueId, id)
                BedWarsHook.fireInvisRemoved(it)
            }
        }
    }
}
