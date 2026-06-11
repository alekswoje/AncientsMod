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
- **Low-ping mine prediction (on by default).** Mining now feels like 25 ping even at 300: the crack animation starts the instant you swing, the block visually breaks the moment its timer ends (ore turns to stone locally, confirmed by the server right after), and break particles + sound play instantly on your client. The server stays fully authoritative — toggle it in Settings → Mining if you prefer the old server-driven visuals.
- **Powerball fireballs render again.** The client-side Powerball now shows its fire-charge body, not just the flame trail — it renders correctly alongside Sodium, Iris, and Distant Horizons.

## UI & Screens

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
