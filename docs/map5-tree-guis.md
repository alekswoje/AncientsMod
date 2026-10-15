# Map 5 tree screens and combat-only Hunter

These changes are Map 5 preparation. Do not enable them on Map 4 or move these notes into the live patch queue before cutover.

## Gates and release

- Keep `mining-tree.enabled: false` and `hunter.map5-tree: false` in shipped config until Map 5. Existing dev overrides can enable both.
- Mining screens require AncientsMod protocol minor 12. Older clients and Bedrock use the chest view, with a Next tree button for the six classes.
- The mod branch includes the existing, unreleased Hunter screen work. No mod release tag is part of this change.
- At Map 5 cutover, enable the existing gates and release the matching mod. No new permission, world or data scope is introduced.

## Player changes (deferred changelog)

- `/miningtree` opens one connected web with paths and wheels, 487 nodes, five effect notables and four keystones. Drag to pan, scroll to zoom, and hover to inspect nodes.
- `/miningtree ascendancy` opens the class screen. Each of the six existing classes has its own view; switching the view does not choose a class or spend points.
- Mining and ascendancy points stay separate. Class switching still requires refunding every allocated node in the current class. Soulforge keeps its perk picker and refund controls.
- The main-screen respec explicitly refunds all mining and class points. A class-screen respec refunds only the current class. Individual refunds still reject disconnected trees and charge the existing money price.
- Hunter's Map 5 loot stats, notables and keystones become combat/survival choices. This includes Loaded Dice, Jackpot, Trophy Hunter, Fortune's Price, Rich Vein, Boss Spoils, Greed, Gilded Trail, Keen Spoils and Hoarder's Haul. Hunter XP nodes remain. Map 4's tree and effects are unchanged.

## Mining keystones

All positive bonuses use the existing base-tree caps. Class bonuses and mechanics are unchanged. Penalties multiply the matching mining payout and stack multiplicatively with other penalties.

| Keystone | Bonus | Tradeoff |
| --- | --- | --- |
| Heavy Hand | 15% increased ore | 25% less mining XP |
| Charged Core | 10% increased energy | 20% less ore |
| Gem Seeker | 8% increased jewel chance, 12% more shard chance | 20% less energy |
| Haste | 8% increased mining speed | 15% less ore |

## Existing dev allocations

The new main web uses `web_` node IDs. Old dev main-tree allocations are discarded from derived progress, restoring those points without charging a respec. Class node IDs, slots, edges, chosen perks and allocations are preserved. Map 4 never constructs the mining manager.

## Verification

- `MiningWebTest`: connectivity, one main root, six unchanged class trees, all effects and keystones, stat caps, payout tradeoffs, bridge refunds and wire fixtures.
- `HunterCombatOnlyTest`: no loot node or ability in Map 5; live-tree loot remains intact.
- Mod `MiningTreePayloadTest` decodes real plugin-generated fixtures for all seven views.
- In-game checks: pan/zoom at different GUI scales; take and refund a node; verify class lock and paid class respec; open Soulforge, choose a perk and return; reconnect; open Hunter; test an older/unmodded client. Automated builds do not establish these visual checks.
