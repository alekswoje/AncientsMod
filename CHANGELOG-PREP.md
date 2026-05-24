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

## UI & Screens

- **The `/pv` overview now scrolls vertically** so you can browse all your personal vaults when you have more than fit on screen. The Sort button stays anchored in the footer; mouse-wheel scrolls one row at a time.
- **Fixed: clicking PV 8 in the `/pv` overview did nothing.** An off-by-one cap rejected vault 8 before the request reached the server — left-click and right-click now both work on every accessible vault.
- **Affinity picker shows when a category is bound to multiple PVs.** A category bound to both this vault and elsewhere displays "✔ + PV 5,9"; a category bound only on other PVs shows "on PV 5,9". Click still toggles only this vault's binding (multi-affinity is the new default — other bindings stay).

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
