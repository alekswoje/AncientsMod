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

- **Custom item textures** — **Ancient Energy** (a purple gem), **money notes** (a green bill), and **shards** + **contrabands** (colour-coded by rarity: gray → green → blue → yellow → gold → red → aqua → white) now have their own icons, so you can recognise them at a glance without hovering.
- **Amount shown on the icon** — **Ancient Energy** and **money notes** now display their value as a small `1m` / `1.1k` tag in the top-right of the slot, so you can read a stack's worth without hovering. Toggle under **Settings → Item Display**.
- **Level & prestige on the icon** — gear and pickaxes now show their **level** (top-right) right on the item, and pickaxes also show their **prestige** (gold, top-left), so you can read both at a glance. Toggle under **Settings → Item Display**.
- **Smoother mining cracks** — client-side break prediction is now off by default, so the server's own crack animation is the single source. This removes a duplicate overlay that could look doubled or choppy while mining. You can re-enable prediction any time in Settings.
- **Redstone ore no longer flickers while mining the Redstone Mine** — the client now keeps redstone ore rendered unlit, so hitting or breaking nearby blocks won't make it flash.

## UI & Screens

- The **PV terminal** now shows an item's **full lore** in its hover tooltip — long pickaxes (every enchant, prestige perks, and base stats) are no longer cut off partway down.
- The **PV terminal** now **auto-focuses the search box** the moment it opens, so you can type to filter items right away. Toggle it under Settings → Custom Screens.
- The **PV terminal** is now **view-only outside a safe zone** — taking and depositing items are disabled (with a clear notice) when you're somewhere you can be attacked, matching the regular vault menu. You can still browse and search your vaults anywhere.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life
