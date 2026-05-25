# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- **Stats HUD now hides itself when you have no kills or drops to show** instead of sitting around in spawn/hub displaying just the world name. It pops back up as soon as you start farming again. Set the widget's "Always show" toggle if you want it pinned regardless.
- **Stats HUD — "Show lootbox subtypes" toggle.** New per-widget setting (Drops section). Off (default): every lootbox / lockbox / seasonal crate drop rolls up to a single "Lootbox" / "Lockbox" / "Seasonal Crate" row. On: each rarity / subtype gets its own row (e.g. "Common Booster Box", "Rare Booster Box", "Epic Booster Box"). Same wire data either way — the mod aggregates locally.
- **Stats HUD now stays accurate when the server adds new drop categories.** The decoder consumes the whole packet even if it has more rows than the mod can display, so older mods will still see a truncated-but-correct view instead of silently corrupting the rest of the stats packet.
- **Stats HUD — new Mining section (XP/h, Energy/h, $/h).** Shows live per-hour rates while you're actively mining, with the same compact formatting the action bar uses (e.g. `12.5K`). Section auto-hides a few seconds after you stop mining. Toggle it under the widget settings ("Show mining (XP/h, Energy/h, $/h)"); enabling it also tells the server to default the action-bar rates off so the same numbers don't show twice (re-enable in-game via `/toggles` → Action Bar → Show Rates if you want both).

## Rendering & Visuals

- **Gang pings and meteorite labels now scale with zoom.** Using a zoom mod (Zoomify / OkZoomer / Lunar zoom / OptiFine zoom) narrows your FOV, which made the beam grow while the floating text stayed tiny — now the text grows in lockstep with the beam and the meteorite block, matching what you see through the zoom.

## UI & Screens

- **The `/pv` overview now scrolls vertically** so you can browse all your personal vaults when you have more than fit on screen. The Sort button stays anchored in the footer; mouse-wheel scrolls one row at a time.
- **Fixed: clicking PV 8 in the `/pv` overview did nothing.** An off-by-one cap rejected vault 8 before the request reached the server — left-click and right-click now both work on every accessible vault.
- **Affinity picker shows when a category is bound to multiple PVs.** A category bound to both this vault and elsewhere displays "✔ + PV 5,9"; a category bound only on other PVs shows "on PV 5,9". Click still toggles only this vault's binding (multi-affinity is the new default — other bindings stay).
- **Drag-and-drop reorder in the `/pv` overview.** Click and drag any PV tile onto another to swap their contents and affinities — drop a high-volume PV next to its sibling so they sit together in your overview. Drag ghost follows the cursor, valid drop targets pulse yellow, and both tiles flash on release.
- **Smooth animations in the `/pv` overview.** Hover state eases in over ~250ms, drag ghost renders at the cursor with a translucent panel, and the swap completion flash fades out cleanly. Behavior is unchanged — just nicer to look at.
- **`/buffs` Damage Dealt now lists every damage-proc enchant on your weapon** — Lightning, Scorch, Electrocution, Poison (swords), Bleed (axes), Pummel (either) — with its proc chance, per-proc damage, and average damage per hit. Numbers are computed from your held weapon's actual base damage.
- **`/buffs` Damage Taken now leads with your total damage reduction** (`-85.0% (15.0% taken)`) and adds a Total damage reduction summary row under the armor list. Empty armor slots also explain why they're blank in the row detail.
- **`/buffs` Damage Taken now lists every defensive enchant on your armor** — Titan Blood / Crouch as always-on DR multipliers, Tank / Armoured as conditional (vs axe / sword attackers), and Painkiller, Blood Magic, Damage Limiter, Voodoo, Aegis, Deflect, Maneuver, Anti Gank, Elemental Master, Extinguish, Tough Skin, Cactus, Last Stand, Adrenaline, Escapist as informational proc/conditional rows. Each row shows what triggers it and what it does.
- **Proc damage rows in `/buffs` Damage Dealt are now live displays, not toggles.** Lightning, Scorch, Electrocution, Poison, Bleed, and Pummel each show their current expected avg/hit damage — the number updates instantly as you tick Swordsman / Execute / Enrage / Monster Hunter / Fearless on or off. The proc rows have no checkbox of their own.
- **Lucky proc boost is now a toggle at the TOP of Damage Dealt** and multiplies only the proc damage rows when ticked — it no longer inflates your weapon's base hit. The Lucky row shows the proc-chance multiplier (e.g. `x2.00`); ticking it scales every proc row's displayed damage by that factor.
- **Conditional-DR enchants in `/buffs` Damage Taken are now toggleable** with checkboxes — tick Tank to see "what's my DR vs an axe attacker", tick Painkiller to see the average per-hit reduction. The total at the top of the channel updates live as you toggle.
- **`/buffs` Total damage reduction row now does the math correctly** when Titan Blood, Crouch, or other multiplicative passive layers are active — previously it summed DR percentages additively which over-estimated.

## Input & Keybinds

- **New keybind: Item Lock (default Z).** Open any inventory screen, hover a slot, press Z to flag it as locked. A small yellow padlock appears in the slot's corner. Locked slots refuse Q-drops, Ctrl+Q stack drops, drag-into-void, shift-clicks into chests/vaults, and number-key hotbar swaps — the slot becomes read-only until you press Z again to unlock. Works on hotbar, main inventory, armor, and offhand. Rebind under Options → Controls → Inventory. Manage all locks (clear-all) under PrisonsMod Settings → Inventory.

## Networking & Server Integration

- **Disabling the PV overview now also disables server-side affinity routing.** When PrisonsMod Settings → PV Overview is off, the shift-click mixin stops intercepting (vanilla shift-click runs) AND the server is told to skip affinity routing for you, so your stored affinity bindings don't silently re-route items into the wrong vault. State is synced on join and on every toggle change; uninstalling the mod has the same effect.

## Updates & Installation

## Quality of Life
