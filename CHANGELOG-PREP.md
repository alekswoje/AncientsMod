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

## Input & Keybinds

- Custom keybinds reset to defaults **once** after the rename. Rebind them under Options > Controls > AncientsMod if you had changed any.

## Networking & Server Integration

## Updates & Installation

- **PrisonsMod is now AncientsMod.** One mod for the whole Ancients network. The jar is now `ancientsmod-x.y.z.jar` and the in-game command is `/ancientsmod`.
- **One-time manual step if you update by hand:** delete the old `prisonsmod-*.jar` from your `mods` folder before adding the new jar. The game refuses to launch while both are installed. The in-game auto-updater does the swap for you.
- All your settings carry over automatically: feature toggles, HUD layout, HUD settings, muffler, item locks and pick buffs.

## Quality of Life
