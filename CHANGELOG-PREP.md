# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- Removed the dungeon timer HUD and the Tartarus Vision skill tree screen (dungeons are removed from the server next season)
- New **Armor Durability** HUD showing the uses left on each worn piece, off by default
- Armor Durability HUD can also track your held and offhand item, show percent instead of uses, and stay hidden until a piece drops below a chosen percent
- New **Clock** HUD showing your own local time, off by default
- Clock HUD supports 12 or 24 hour format, seconds, timezone and date
- New **saturation strip** above the hunger bar, off by default
- Events HUD now shows the **Mining Rush** timer by default
- Events HUD has a **Skywars** row that fills in once the server starts sending that timer
- Collapsed booster rows now count down to the **lowest** remaining time instead of the highest

## Rendering & Visuals

- New **Low-ping mine prediction** starts the crack animation the instant you swing, on by default
- Blocks now break on your own screen the moment their predicted timer ends
- Break particles and the break sound now play instantly on your client
- Mine prediction keeps animating while **ClickLock** is on
- Powerball fireballs show their fire charge body again instead of only the flame trail
- Powerball fireballs now render correctly alongside **Sodium**, **Iris** and **Distant Horizons**

## UI & Screens

- `/energycalc` now opens an in-game screen listing level, **next level energy cost** and banked energy for every Ancient item you hold or wear
- `/energycalc <targetLevel>` still goes to the server for its full cost breakdown
- `/buffs` now opens with a **mining speed** line showing your speed multiplier and blocks per second
- `/buffs` now opens with a **daily bonus** line showing the bonus, its block cap and your progress
- **Mine-crack prediction** in the advanced settings screen is now called **Low-ping mine prediction**

## Input & Keybinds

## Networking & Server Integration

## Updates & Installation

## Quality of Life

- Every new HUD, overlay and screen line has its own toggle in the settings screen
