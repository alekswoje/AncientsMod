# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- **New "Blocks" section in the Stats HUD** — shows how many of each ore you've mined while you're mining, right in the HUD instead of the action bar. Fully toggleable: pick which ores to show (defaults to Iron / Gold / Diamond / Emerald) and switch between **lifetime totals on your pickaxe** (great for prestige) and **this-session** counts, under Settings → Stats HUD → Blocks. Off by default — flip on "Show blocks (per-ore counts)" to enable.

## Rendering & Visuals

- **Mining rushes now show a ping beam**, just like meteors — when a rush spawns in your tier's mine, a colored beacon beam (tinted to the ore) and a distance label point you straight to it. Toggle under Settings → World → "Mining rush pings".

## UI & Screens

- **The PV terminal is now the default `/pv` view** — open `/pv` and you get the searchable single-grid terminal (every item across all your vaults at once) instead of the card overview. Prefer the cards? Turn the terminal off under Settings → PV to switch back.
- **Personal Vault affinities are gone** — the affinity picker, presets, and the Sort button have been removed from the PV screens (the server-side affinity routing and `/pvsort` were retired too). The terminal replaces all of it.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
