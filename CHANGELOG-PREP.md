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

- **The PV terminal now renders item names with their true colours** — hex and gradient names that used to show up black or garbled now display correctly, and heavily-coloured names are no longer cut short.
- **`/pickbuffs` is now a theorycrafting sandbox.** Click any buff layer to open a **slider** and dial its value up or down, then watch your final XP / energy / money / shard / combat numbers update live. A new **Custom Modifiers** tab lets you add your own "what-if" modifiers — *increased %* (joins the shared additive pool), *more ×* (its own multiplier), or *flat +* — aimed at a single channel or a whole group (All Mining / All Combat / Everything), so you can plan a gear or buff setup before chasing it. Your custom modifiers and slider tweaks are saved between sessions.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
