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

- **Booster multiplier & duration on the item** (Settings → Item Display → *Multiplier / duration on boosters*, on by default). Booster items now show their multiplier (e.g. `2x`, top-left) and total duration (e.g. `30m`, bottom-right) right on the icon in your inventory and hotbar, color-matched to the boost type (green XP, aqua Energy, gold Ore, purple Shard) — so you can read a booster's worth at a glance without hovering.
- **Client-side Powerball rendering** (Settings → Mining → *Client powerball render*, on by default). Your Powerball fireballs are now drawn locally instead of as server entities, so the server stops streaming a position packet for every fireball every tick while you mine — a big lag/ping reduction when Powerball stacks up, especially on slower connections. The fireballs follow the exact same bouncing path and break the same blocks as before. They're drawn with particles, so if you run **Minimal** particles, toggle this off to go back to the server-rendered fireballs.

## UI & Screens

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
