# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- The **Stats HUD** now groups your Hunt drops by **rarity** (Common → Mythic, rarest first) instead of one row per item, and adds a live **Hunter XP/h** rate plus your session Hunter XP total.

## Rendering & Visuals

## UI & Screens

- The **PV Terminal now merges identical items into one tile** showing the combined total (e.g. `576`, `12k`) instead of a separate tile per stack — no more scrolling past nine stacks of the same item. Hover shows the full breakdown and which PVs it spans.
- Added **sorting to the PV Terminal**: a button on the right of the search bar cycles **Quantity / A–Z / Category**, and the grid auto-sorts (Quantity by default). Your choice is remembered between sessions.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
