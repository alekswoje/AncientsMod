# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

## Rendering & Visuals

- **Fullbright.** New setting under **World** in the PrisonsMod settings (F9). On by default — replaces the server's removed permanent night vision so dark areas still look bright. The server can disable it in specific worlds via its `prisonsmod.fullbright.blacklist-worlds` config (sent over the prisonsmod channel on join), so atmospheric event worlds can stay dark.
- **Rift texture pack** now stays loaded across the full event — previously it unloaded on every other round when the server swapped to the alt rift world, causing two extra 5-15s resource reloads per event.
- **Per-texture hiding from `/toggles`.** You can now hide individual custom item textures (not just all of them) from the server's **/toggles → Custom Textures** menu — with the mod installed, the ones you turn off render as their plain vanilla item just for you, while everyone else still sees the custom art.
- **Booster multiplier & duration on the item** (Settings → Item Display → *Multiplier / duration on boosters*, on by default). Booster items now show their multiplier (e.g. `2x`, top-left) and total duration (e.g. `30m`, bottom-right) right on the icon in your inventory and hotbar, color-matched to the boost type (green XP, aqua Energy, gold Ore, purple Shard) — so you can read a booster's worth at a glance without hovering.

## UI & Screens

- **Tartarus Vision** — brand-new fullscreen skill-tree screen. Right-click the Oracle of Tartarus NPC and pick "Tartarus Vision", or run `/skilltree` from anywhere on the server, to open a Path-of-Exile-style overview of the whole dungeon skill tree. **Drag** to pan, **scroll** to zoom, **left-click** a node to allocate, **right-click** to refund, **bottom-right button** (twice) to respec all. Live points HUD top-left, search bar at the top (auto-defocuses when you click into the tree), a soft amethyst halo on the chain of nodes you'd need to allocate to reach a hovered one, and a brief glow on every node the moment it's allocated or refunded. Falls back to the in-world chisel for vanilla players — chisel allocations push live to any open Vision screen so the two stay in sync.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
