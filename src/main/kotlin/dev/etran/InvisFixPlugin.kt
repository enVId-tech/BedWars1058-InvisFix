package dev.etran

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

class InvisFixPlugin : JavaPlugin(), Listener {
    lateinit var settings: Settings
        private set

    private lateinit var tracker: Tracker

    /** /bw-invisfix config ...: edit options in game; changes apply immediately and are saved to config.yml. */
    private val configCmd = ConfigCommand(this) { settings = Settings(config) }

    override fun onEnable() {
        if (!BedWarsHook.init()) {
            logger.severe("BedWars1058 (or a compatible fork) was not found - disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        settings = Settings(config)
        tracker = Tracker(this)
        Packets.register(this, tracker)
        tracker.start()
        server.pluginManager.registerEvents(this, this)
        logger.info("BedWars1058-InvisFix enabled (BedWars API: ${BedWarsHook.prefix}).")
    }

    override fun onDisable() {
        if (::tracker.isInitialized) {
            Packets.unregister()
            tracker.shutdown()
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        tracker.forget(e.player)
    }

    /**
     * Optional reveal on damage. Runs after other plugins (MONITOR) and only for damage that actually happened,
     * so cancelled hits and damage during respawn protection never reveal anybody.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(e: EntityDamageEvent) {
        val victim = e.entity as? Player ?: return
        if (!tracker.isInvisible(victim)) return
        val s = settings
        if (!s.revealOnAnyDamage && !s.revealOnPlayerDamage) return
        if (e.finalDamage <= 0.0) return
        var byPlayer = false
        if (e is EntityDamageByEntityEvent) {
            val d = e.damager
            byPlayer = d is Player || (d is Projectile && d.shooter is Player)
        }
        if (s.revealOnAnyDamage || (s.revealOnPlayerDamage && byPlayer)) tracker.reveal(victim)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> configCmd.reload(sender)
            "config" -> configCmd.handle(sender, args.drop(1), label)
            "status" -> sender.sendMessage("${tracker.invisible.size} invisible player(s) tracked.")
            else -> sender.sendMessage("/$label <reload | status | config ...>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when {
            args.size == 1 -> listOf("reload", "status", "config").filter { it.startsWith(args[0], ignoreCase = true) }
            args[0].equals("config", ignoreCase = true) -> configCmd.complete(args.drop(1))
            else -> emptyList()
        }
    }
}
