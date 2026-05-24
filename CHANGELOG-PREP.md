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

- New **Personal Vaults overview screen** — running `/pv` now opens a single screen showing all 8 of your PVs side-by-side with item previews and the affinity bindings for each vault. Click any vault card to open it normally. Toggleable in Settings → Custom Screens. `/pv 1`, `/pv 2`, `/pvsort` etc. still go to the server unchanged.
- Settings reorganised: the **PV overview** and **bug-report UI** toggles moved out of *Network* into their own **Custom Screens** section, so Network now lists only true network behaviors (update alert, auto-rejoin).

## Input & Keybinds

## Networking & Server Integration

- New **Auto-rejoin** toggle (Settings → Network, off by default). When the server kicks you, restarts, or your connection drops, the mod waits 5 s and reconnects to the same server address, then keeps retrying every 5 s until you're back in. The proxy puts you back on the backend you were last playing on (or queues you if it's still booting). Click any button on the disconnect screen to cancel.
- Running `/bugreport` again while a previous bug-report window is still open (waiting on the AI, resolved, or escalated) now **cleanly closes the old session and starts a fresh one** instead of falling through to the server as a context-free legacy chat command. Previously this silently filed a second no-description report and auto-opened an empty Discord ticket.

## Updates & Installation

## Quality of Life
