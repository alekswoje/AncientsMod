# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- Removed the dungeon timer HUD and the Tartarus Vision skill tree screen (dungeons are removed from the server next season)
- Removed the caravan, warlord caravan and caravan escort kill counters from the Stats HUD (caravans are removed from the server)
- New **Armor Durability** HUD showing the uses left on each worn piece, off by default
- The Stats HUD has a **Mining sim** section showing live `/miningsim` totals and per-hour rates while a session runs, and it says **PAUSED** when the session is paused
- Armor Durability HUD can also track your held and offhand item, show percent instead of uses, and stay hidden until a piece drops below a chosen percent
- New **Clock** HUD showing your own local time, off by default
- Clock HUD supports 12 or 24 hour format, seconds, timezone and date
- New **saturation strip** above the hunger bar, off by default
- Events HUD now shows the **Mining Rush** timer by default
- Events HUD has a **Skywars** row that fills in once the server starts sending that timer
- Collapsed booster rows now count down to the **lowest** remaining time instead of the highest
- New **Jewel Slots** HUD showing your three jewel sockets as extra hotbar style slots, next to the hotbar by default
- Socketed jewels show their gem icon and their rarity colours the slot border
- Locked jewel slots show a padlock and the prestige they need to unlock
- Jewel Slots HUD can stack vertically, list the stats on each socketed jewel, and stay visible while every slot is empty
- Your jewel sockets now appear in the **inventory screen** as three real slots down the side of the panel
- Pick a jewel up onto your cursor and click a socket to fit it, click a socketed jewel to take it back out
- Hovering a socket shows the jewel and its stats, or the prestige a locked socket needs
- The inventory sockets are drawn from your resource pack's own slot art, so they match whatever pack you use

## Rendering & Visuals

- New **Low-ping mine prediction** starts the crack animation the instant you swing, on by default
- Blocks now break on your own screen the moment their predicted timer ends
- Break particles and the break sound now play instantly on your client
- Mine prediction keeps animating while **ClickLock** is on
- Powerball fireballs show their fire charge body again instead of only the flame trail
- Powerball fireballs now render correctly alongside **Sodium**, **Iris** and **Distant Horizons**

## UI & Screens

- Removed the **Caravan combat** bundle from the muffler settings screen
- `/energycalc` now opens an in-game screen listing level, **next level energy cost** and banked energy for every Ancient item you hold or wear
- The `/energycalc` screen has a **Gear Tiers** tab showing the max level and the total energy to max a fresh piece of every tier
- The `/energycalc` screen has a **Pickaxe Prestige** tab showing the energy and blocks each prestige step needs, for every pickaxe tier
- `/miningsim` now opens a **Mining Simulation** screen instead of printing to chat, and it opens on its own when a session ends. `/simstats` opens the same screen
- The Mining Simulation screen has **Start**, **Pause**, **Resume** and **Stop** buttons, so a whole session runs from the screen without typing a command
- **Start** has an **Auto-stop** selector for **3m**, **5m**, **10m** or **30m** of active mining, or off
- The Mining Simulation screen's **Sources** tab lists every reward by the proc chain that earned it, with the origin highlighted
- The **Sources** and **Procs** tabs sort by any column, newest click reversing the order
- The Mining Simulation screen has a **Graph** tab plotting XP, energy and money per hour across the session
- The Mining Simulation screen has a **History** tab that keeps finished sessions, and shift-clicking two shows the difference in each rate
- Clicking a saved session opens it, so its sources, procs and graph can be read exactly like a running one
- Saved sessions can be **renamed** and **deleted**, and they now survive a relog
- The **To max** column now fills in, showing the energy left on the piece you are wearing and the next prestige cost on the pickaxe you hold
- `/energycalc <targetLevel>` still goes to the server for its full cost breakdown
- `/buffs` now opens with a **mining speed** line showing your speed multiplier and blocks per second
- `/buffs` now opens with a **daily bonus** line showing the bonus, its block cap and your progress
- **Mine-crack prediction** in the advanced settings screen is now called **Low-ping mine prediction**
- Jewel sockets now show a jewel's full description, including the lines under its stats that only the item itself used to show
- Unique jewels now show their own name and icon in the jewel sockets and the jewel HUD instead of a generic gem

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life

- Every new HUD, overlay and screen line has its own toggle in the settings screen
