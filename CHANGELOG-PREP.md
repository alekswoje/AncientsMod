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

- **Rift texture pack** now stays loaded across the full event — previously it unloaded on every other round when the server swapped to the alt rift world, causing two extra 5-15s resource reloads per event.

## UI & Screens

- **PV Terminal view** — new opt-in `/pv` layout that pools every stack across all your PVs into one flat, searchable grid. Click to take 1, right-click to take half, shift-click to take a whole stack. Drag a slot from your hotbar onto the grid to deposit via affinity. Toggle in *Settings → Custom Screens → "PV terminal view (ME-style flat grid on /pv)"* — off by default; flip it off any time to go back to the existing card overview.

## Input & Keybinds

## Networking & Server Integration

- **Fixed mod-command chat spam** — when the server was slow or didn't answer a mod request, `/pv` (and `/suggest`, `/bugreport`) could repeat *"Server didn't respond — sent the command directly."* endlessly and never actually open. They now fall back to the plain command a single time and open normally.

## Updates & Installation

## Quality of Life
