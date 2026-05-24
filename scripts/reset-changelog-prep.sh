#!/usr/bin/env bash
# Resets CHANGELOG-PREP.md to the empty template after a successful release
# publish. Runs as part of the release workflow.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREP="$REPO_ROOT/CHANGELOG-PREP.md"

cat > "$PREP" <<'EOF'
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

## Networking & Server Integration

## Updates & Installation

## Quality of Life
EOF

echo "[reset-changelog-prep] Prep file reset to empty template."
