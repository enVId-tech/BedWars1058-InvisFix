package dev.etran

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.EnumWrappers
import com.comphenix.protocol.wrappers.Pair
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Everything that needs packets (ProtocolLib):
 *  - armor of invisible players is blanked in every entity-equipment packet a viewer receives
 *  - walking (step) sounds made by invisible players are dropped; every other sound goes through
 */
object Packets {
    private lateinit var plugin: InvisFixPlugin
    private lateinit var tracker: Tracker

    private val ARMOR_SLOTS = listOf(
        EnumWrappers.ItemSlot.HEAD,
        EnumWrappers.ItemSlot.CHEST,
        EnumWrappers.ItemSlot.LEGS,
        EnumWrappers.ItemSlot.FEET,
    )

    private fun air() = ItemStack(Material.AIR)

    fun register(p: InvisFixPlugin, t: Tracker) {
        plugin = p
        tracker = t
        val pm = ProtocolLibrary.getProtocolManager()

        pm.addPacketListener(object : PacketAdapter(p, ListenerPriority.HIGH, PacketType.Play.Server.ENTITY_EQUIPMENT) {
            override fun onPacketSending(event: PacketEvent) {
                try {
                    filterEquipment(event)
                } catch (_: Throwable) {
                }
            }
        })

        val soundTypes = listOf(PacketType.Play.Server.NAMED_SOUND_EFFECT, PacketType.Play.Server.ENTITY_SOUND)
            .filter { it.isSupported }.toTypedArray()
        if (soundTypes.isNotEmpty()) {
            pm.addPacketListener(object : PacketAdapter(p, ListenerPriority.HIGH, *soundTypes) {
                override fun onPacketSending(event: PacketEvent) {
                    try {
                        filterSound(event)
                    } catch (_: Throwable) {
                    }
                }
            })
        }
    }

    fun unregister() {
        try {
            ProtocolLibrary.getProtocolManager().removePacketListeners(plugin)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ armor

    private fun filterEquipment(event: PacketEvent) {
        val s = plugin.settings
        if (!s.hideArmor) return
        val id = event.packet.integers.read(0)
        val owner = tracker.invisible[id] ?: return
        val viewer = event.player ?: return
        if (viewer.uniqueId == owner.owner) return
        if (!s.armorHideFromTeammates && tracker.sameTeam(viewer.uniqueId, owner)) return

        // The same packet object may be shared between viewers, so edit a copy.
        val copy = event.packet.deepClone()
        event.packet = copy

        val pairs = try { copy.slotStackPairLists } catch (_: Throwable) { null }
        if (pairs != null && pairs.size() > 0) { // 1.16+
            val list = pairs.read(0)
            val fixed = ArrayList<Pair<EnumWrappers.ItemSlot, ItemStack>>(list.size)
            for (pair in list) {
                fixed.add(if (pair.first in ARMOR_SLOTS) Pair(pair.first, air()) else pair)
            }
            pairs.write(0, fixed)
            return
        }
        val slots = try { copy.itemSlots } catch (_: Throwable) { null }
        if (slots != null && slots.size() > 0) { // 1.9 - 1.15
            if (slots.read(0) in ARMOR_SLOTS) copy.itemModifier.write(0, air())
            return
        }
        // 1.8: slot index 0 = hand, 1..4 = boots, leggings, chestplate, helmet
        if (copy.integers.size() > 1 && copy.integers.read(1) in 1..4) copy.itemModifier.write(0, air())
    }

    /** Sends [target]'s real armor to everybody who can see them; the filter above decides what is blanked. */
    fun refreshArmor(target: Player) {
        try {
            val world = target.world
            val viewers = world.players.filter { it != target }
            if (viewers.isEmpty()) return
            val pm = ProtocolLibrary.getProtocolManager()
            val items = listOf(
                target.inventory.helmet,
                target.inventory.chestplate,
                target.inventory.leggings,
                target.inventory.boots,
            ).map { it ?: air() }

            val packets = ArrayList<PacketContainer>()
            val probe = PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT)
            val modernPairs = try { probe.slotStackPairLists.size() > 0 } catch (_: Throwable) { false }
            val modernSlots = try { probe.itemSlots.size() > 0 } catch (_: Throwable) { false }
            if (modernPairs) {
                val pc = PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT)
                pc.integers.write(0, target.entityId)
                val list = ArrayList<Pair<EnumWrappers.ItemSlot, ItemStack>>()
                for (i in ARMOR_SLOTS.indices) list.add(Pair(ARMOR_SLOTS[i], items[i]))
                pc.slotStackPairLists.write(0, list)
                packets.add(pc)
            } else {
                for (i in ARMOR_SLOTS.indices) {
                    val pc = PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT)
                    pc.integers.write(0, target.entityId)
                    if (modernSlots) pc.itemSlots.write(0, ARMOR_SLOTS[i]) else pc.integers.write(1, 4 - i)
                    pc.itemModifier.write(0, items[i])
                    packets.add(pc)
                }
            }
            for (v in viewers) for (pc in packets) pm.sendServerPacket(v, pc)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ sounds

    private fun soundName(pc: PacketContainer): String? {
        try {
            val strings = pc.strings
            if (strings.size() > 0) return strings.read(0) // 1.8: "step.grass"
        } catch (_: Throwable) {
        }
        try {
            val effects = pc.soundEffects
            if (effects.size() > 0) {
                val snd: Any = effects.read(0) ?: return null
                val key = try {
                    snd.javaClass.getMethod("getKey").invoke(snd)?.toString()
                } catch (_: Throwable) {
                    null
                }
                return key ?: snd.toString()
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun isStep(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("step.") || n.endsWith(".step") || n.endsWith("_step")
    }

    private fun filterSound(event: PacketEvent) {
        val s = plugin.settings
        if (!s.hideWalkingSounds || tracker.invisible.isEmpty()) return
        val pc = event.packet
        val name = soundName(pc) ?: return
        if (!isStep(name)) return
        val viewer = event.player ?: return

        if (event.packetType == PacketType.Play.Server.ENTITY_SOUND) {
            val owner = tracker.invisible[pc.integers.read(0)] ?: return
            if (owner.owner == viewer.uniqueId) return
            if (!s.soundsHideFromTeammates && tracker.sameTeam(viewer.uniqueId, owner)) return
            event.isCancelled = true
            return
        }

        // Position sound (coordinates are in 1/8 blocks): is it an invisible player's footstep?
        if (pc.integers.size() < 3) return
        val x = pc.integers.read(0) / 8.0
        val y = pc.integers.read(1) / 8.0
        val z = pc.integers.read(2) / 8.0
        val world = viewer.world.name
        for (owner in tracker.invisible.values) {
            if (owner.world != world || owner.owner == viewer.uniqueId) continue
            if (Math.abs(owner.x - x) > 1.3 || Math.abs(owner.z - z) > 1.3) continue
            if (y - owner.y < -1.5 || y - owner.y > 2.0) continue
            if (!s.soundsHideFromTeammates && tracker.sameTeam(viewer.uniqueId, owner)) continue
            event.isCancelled = true
            return
        }
    }
}
