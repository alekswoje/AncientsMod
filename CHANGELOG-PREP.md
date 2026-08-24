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

- Using an Item Nametag now opens a rename screen instead of typing the name into chat.
- The rename screen previews the real item and its tooltip, updating as you type.
- Colour swatches, format buttons, and a hex field insert codes at your cursor.
- The gradient tool blends a name across two or three colours.
- A live counter shows your name length against the server's **32** character limit and blocks Confirm when you go over.

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

- Auto-update now installs the newest release instead of the one that was newest when you joined the server.
- Update alerts now appear for releases published while you are already in-game.
- Updating twice in one session no longer costs an extra restart to apply.

## Quality of Life
