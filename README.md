# BedWars1058-InvisFix

Makes BedWars1058 (and compatible forks) invisibility potions behave like Hypixel.
Needs **ProtocolLib** on the server. Build: `gradle build` (JDK 17/21), jar in `build/libs/`.

* **Armor hidden** - every equipment packet of an invisible player is blanked for enemies and spectators, so armor
  can no longer reappear when someone comes into view, swaps items, respawns, etc. Held items stay visible.
* **Walking sounds silenced** - step sounds from invisible players are dropped, all other sounds are untouched.
* **Footsteps** - invisible players leave footprints (`FOOTSTEP` particle on 1.8-1.12, grey dust on 1.13+).
* **Name tags hidden** - invisible players are put in a "never show name tag" team on every viewer's scoreboard, and
  BedWars is told to restore their normal team when invisibility ends.
* **No potion particles** - the invisibility effect is re-applied without particles.
* **Reveal on damage** (off by default): `damage.reveal-on-player-damage`, `damage.reveal-on-any-damage`.

All options can be changed in game with `/bw-invisfix config set <path> <value>` (e.g. `damage.reveal-on-player-damage true`).

Commands (`invisfix.admin`): `/bw-invisfix` (alias `/bwif`) `reload | status | config <list|get|set|add|remove|reload>`.
