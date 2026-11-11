# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- New Expedition map in the top right shows the whole Expedition, the packs left to clear (yellow when a Rare is with them) and the boss arena. Press M to hide or show it.
- The XP bar and its number turn purple while they show your Hunter level, so you can tell it apart from your mining level.

## Rendering & Visuals

- Meteors and meteorite showers no longer show a beam or play a ping sound. The Meteorite shower pings setting is gone.
- New **Low fire overlay** setting (on by default) draws the on-fire flames low on your screen so you can still see. Turn it off in the settings screen for vanilla fire.

## UI & Screens

- **Hunter tree screen.** `/hunter` opens a full-screen tree. Drag to move, scroll to zoom, click a node to take it and click again to confirm.
- **Mining and Ascendancy tree screens.** `/miningtree` opens a full-screen tree with its own point pools for mining and for each Ascendancy class.
- New **Mod tree screens** toggle in Advanced settings. Turn it off to get the plain server menu for `/hunter` and `/miningtree` instead.
- The Hunter and mining tree screens have a **search box**. Type a word like "speed" or a node's name and every match lights up; press Enter to jump from one match to the next. Ctrl+F or just start typing to search.
- The mining tree screen shows an icon for every node again and no longer lags when you zoom out over the whole tree.
- The mining tree's Auto button opens a list of Off, Energy, XP, Money and Mixed, and one click picks one.
- The mining tree's stat list has an icon for each stat instead of a coloured square.
- The mining tree screen has a Build button. It lists your saved builds, and one click switches to another.
- Tree screens say why a node of another ascendancy class is locked, and the Hunter stat strip hides stats nothing on your tree raises.
- Locked jewel sockets read Ascension and A# instead of Prestige and P#.
- The mining tree screen shows the class tabs only once you reach Ascension 1.

## Input & Keybinds

- Hold left click to keep swinging a sword or axe at monsters in front of you.

## Networking & Server Integration

- `/bugreport` can now attach a screenshot of what you were looking at. The dialog shows a toggle, and the shot leaves out your HUD and chat. Bind **Report a Bug** in Controls to do it with one key, and the description box is ready to type in when the window opens.
- `/suggest` can attach a screenshot too. Tick the box in the suggestion window and it goes up with your idea.

## Updates & Installation

## Quality of Life

- Roll ranges like (4-6) on gear and jewels show only while you hold **Alt**.
- Tooltip collapse and wiki links work with the new gear lore layout.
- Removed the loot browser, since /loottables no longer exists on the server.
- The Hunter tree shows Armour nodes as flat Armour.
