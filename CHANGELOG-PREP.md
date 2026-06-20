# Changelog Prep — Next Release

> **Working file.** Player-facing changes accumulate here between mod releases. The CI on the next `v*` tag reads this file, uses it as the GitHub release notes for the auto-published release, then resets this file to the empty template.
>
> **Format.** Append bullets under the relevant section heading. One short, player-facing sentence per bullet, optionally with `**bold**` for emphasis. Sub-headings with `### ` are fine inside any section.
>
> **Skip.** Internal refactors, dev-only fixes, CI tweaks, log additions, and any change a player won't see → don't log. If unsure, log it (easier to prune than recover).
>
> **What counts as player-facing for the mod:** new HUDs/widgets, render tweaks the player sees, new keybinds, new screens, new client commands, changes to feature toggles, changes to peaceful-PvP / peaceful-mining behavior, tooltip changes, anything visible in the GUI editor or settings screen.

## HUDs

- **Cooldowns HUD** now shows your **/fixall** cooldown. Previously only `/fix` appeared, so the much longer fixall cooldown was invisible — you can toggle it under the HUD's *Per-cooldown filter*.
- **Stats HUD** rates (XP/h, Energy/h, $/h, Hunter XP) no longer cap at 2.1B — they now show the real value however high it climbs.

## Rendering & Visuals

- **Fullbright.** New setting under **World** in the PrisonsMod settings (F9). On by default — replaces the server's removed permanent night vision so dark areas still look bright. The server can disable it in specific worlds via its `prisonsmod.fullbright.blacklist-worlds` config (sent over the prisonsmod channel on join), so atmospheric event worlds can stay dark.
- **Rift texture pack** now stays loaded across the full event — previously it unloaded on every other round when the server swapped to the alt rift world, causing two extra 5-15s resource reloads per event.
- **Per-texture hiding from `/toggles`.** You can now hide individual custom item textures (not just all of them) from the server's **/toggles → Custom Textures** menu — with the mod installed, the ones you turn off render as their plain vanilla item just for you, while everyone else still sees the custom art.
- **Fixed the Ancient Energy amount showing twice** on the item icon — the energy value no longer overlaps itself in the top-right corner. (The gear level/prestige overlay was wrongly reading the energy amount as a "level" and drawing it on top of the currency overlay.)
- **Fixed stray level numbers on quest icons.** The gear level/prestige overlay no longer draws a number on non-gear items whose name happens to end in one — e.g. the *Path of the Ancients* quests "Reach Level 10" / "Reach Level 30" no longer show a bogus `10` / `30` badge. It now renders only on actual leveled gear and pickaxes.
- **Booster multiplier & duration on the item** (Settings → Item Display → *Multiplier / duration on boosters*, on by default). Booster items now show their multiplier (e.g. `2x`, top-left) and total duration (e.g. `30m`, bottom-right) right on the icon in your inventory and hotbar, color-matched to the boost type (green XP, aqua Energy, gold Ore, purple Shard) — so you can read a booster's worth at a glance without hovering.

## UI & Screens

- **In-game item wiki.** Hold **Alt** over any **armor trim** and its tooltip **pins in place** so you can move your cursor onto it — recognised terms become **underlined and clickable**, each opening a short plain-English explainer. Every trim is covered: the stacking words (*more / less / increased / reduced*) link separately from the stat they modify, and the explainers span the full spread of trim stats and abilities — *damage dealt/taken*, *PvE damage*, *Max HP*, *luck*, *Momentum*, *Powerball*, *mining speed*, *shards*, the *meteorite* bonuses, *cell guards/doors*, *proc chance*, plus ability mechanics like *vanish*, *hit*, *Wither*, *Conquest stacks*, *true damage*, *phantom mines*, *Star-Forged* and *Titan's Grip*. **Esc** or click outside closes the popup; toggle under Settings → Tooltips → *Item wiki*.
- **The item wiki now reaches beyond trims — starting with Death Ward.** Hold **Alt** over the **Wayfarer trim**, the **Guardian Angel** enchant, or a **Last Rites** rune and the underlined *Death Ward* term is clickable on each, opening the same explainer — so a shared mechanic reads the same everywhere it appears, not just on the trim that grants it.
- **Tartarus Vision** — brand-new fullscreen skill-tree screen. Right-click the Oracle of Tartarus NPC and pick "Tartarus Vision", or run `/skilltree` from anywhere on the server, to open a Path-of-Exile-style overview of the whole dungeon skill tree. **Drag** to pan, **scroll** to zoom, **left-click** a node to allocate, **right-click** to refund, **bottom-right button** (twice) to respec all. Live points HUD top-left, search bar at the top (auto-defocuses when you click into the tree), a soft amethyst halo on the chain of nodes you'd need to allocate to reach a hovered one, and a brief glow on every node the moment it's allocated or refunded. Falls back to the in-world chisel for vanilla players — chisel allocations push live to any open Vision screen so the two stay in sync.
- **Tartarus Vision renders the rebuilt dungeon skill tree.** The screen now shows the redesigned tree's new node effects with proper descriptions — weapon-specific damage (Sword / Axe / Bow / Wand), Critical Strike Chance & Multiplier, Ailment Damage & Duration, Attack/Cast Speed, % Maximum HP, Life Regen, the fortune nodes, and the new build-defining keystones (Glass Cannon, Executioner, Stormweaver, Avatar of the Hunt, Juggernaut, Bloodthirst, Earthsplitter, Attunement).
- **New: Cell Vault Terminal** — opening your cell's vault chest now brings up an ME-style terminal showing every container in your cell (vault + chests + barrels) as one searchable, sortable grid, with click-to-extract and shift-click/drag deposits, just like the PV terminal. Toggle it off in settings ("Cell terminal on vault chest") to keep the vanilla chest.
- **Trimmed gear now keeps its trim in the PV and Cell Vault terminals** — armor with an armor trim renders with the trim overlay on its icon instead of looking like plain untrimmed gear.
- **`/pickbuffs` is now a theorycrafting sandbox.** Click any buff layer to open a **slider** and dial its value up or down, then watch your final XP / energy / money / shard / combat numbers update live. A new **Custom Modifiers** tab lets you add your own "what-if" modifiers — *increased %* (joins the shared additive pool), *more ×* (its own multiplier), or *flat +* — aimed at a single channel or a whole group (All Mining / All Combat / Everything), so you can plan a gear or buff setup before chasing it. Your custom modifiers and slider tweaks are saved between sessions.
- **PV settings simplified to one toggle.** The old "PV overview screen on /pv" and "PV terminal view on /pv" switches are now a single **"PV terminal view on /pv"** toggle — on opens the ME-style terminal when you run `/pv`, off falls back to the vanilla menu. Previously the terminal toggle quietly did nothing unless the overview toggle was also on.
- **Loot browser shows drop rates with your luck.** The mod's `/loot` drop list now factors in your **current luck** — on a luck-affected table each entry shows `raw% §7→§a adjusted%` right on the row (with the full breakdown and your luck total on hover), matching the server's own loot GUI. The luck total reflects your live sources (outpost buff, armor trims, Orb of Moros) and updates whenever you reopen the list.
- **Settings screen decluttered (F9).** The list is now wider and much shorter. The "set-and-forget" toggles almost everyone leaves on — scrollable tooltips, item wiki, pickaxe block breakdown, peaceful mining, mine-crack prediction, the bug-report / PV / loot custom screens, auto-rejoin, and item lock — moved behind a new **Advanced settings…** button at the top, leaving the main screen to the toggles you actually change. Everything still works the same and is one click away.
- **Auto-rejoin and mine-crack prediction are now on by default** (both still toggleable under Settings → Advanced).

## Input & Keybinds

## Networking & Server Integration

- **Server times now show in your local timezone.** On join, the mod tells the server your computer's timezone, so every clock time the server displays you — event schedules, daily resets, season-pass & gang-top payouts, your cell's raid-protection window, and your auction/history timestamps — renders in **your** zone (daylight-saving included) instead of Pacific.

## Updates & Installation

## Quality of Life

- **Fixed scroll getting stuck after viewing a long tooltip.** Scrollable tooltips no longer keep swallowing your scroll wheel after you stop hovering an oversized item — chat history and other scrollable screens scroll normally again.
