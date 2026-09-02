# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- New **Mine Prediction** HUD (off by default, Settings → HUDs) shows how many predicted breaks the server confirmed, how many rolled back, and how long confirmations took.

## Rendering & Visuals

## UI & Screens

## Input & Keybinds

## Networking & Server Integration

- Predicted breaks no longer flicker back to ore when the server hitches. A block you broke now waits for the server to catch up and only comes back if the server moved on without breaking it.
- Fast builds that break blocks in under **100ms** no longer see every block pop back for a moment before the server's break lands.

## Updates & Installation

## Quality of Life

- `/ancientsmod predict` prints the mine prediction counters, `/ancientsmod predict reset` clears them, and `/ancientsmod predict log` logs every rollback to the game log.
