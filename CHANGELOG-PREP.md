# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- The booster HUD now displays multipliers to two decimals (e.g. **1.25x**) instead of rounding to one decimal.

## Rendering & Visuals

- The rift texture pack now loads before you enter the rift instead of mid-round, so you no longer lose the first few seconds to a resource reload.
- Meteor pings now show a live countdown to impact in **yellow**, then flip to a **green** count-up of how long ago the meteor landed.
- **Hot Zones** now show a beam marking the zone in your tier's mine, so you can find the **1.5x** bonus spot from across the mine. Toggle it under **World → Hot zone indicator** (on by default).
- The **Ore Rush** beam now disappears the moment the rush is finished or expires, instead of lingering for the rest of its timer.

## UI & Screens

- Fixed shift-clicking a tile in the Personal Vault and Cell Vault terminals only pulling part of the stack when the item was split across multiple vaults — it now extracts a **full stack** gathered from all of them at once.
- The `/pickbuffs` screen has a new **Mining Speed** tab that breaks down your pickaxe's break-speed multiplier by source (tool, Efficiency, Momentum, prestige, trims, Hermes' Pace) and shows your current break time per block.
- The Per-Ore Yields tab in `/pickbuffs` now has a **Break** column showing how long each ore takes to mine.

## Input & Keybinds

## Networking & Server Integration

- Fixed mining sometimes getting stuck when you glance off a block and back while holding left-click — it now resumes instead of forcing you to release and re-click.

## Updates & Installation

## Quality of Life
