# AncientsMod

Client-side Fabric companion mod for the **Ancients** Minecraft network ([ancients.gg](https://ancients.gg)).

Renders HUDs (events, cooldowns, boosters, stats, outposts), gang ping markers, floating damage/XP numbers, tooltip upgrades, a sound/particle muffler, and other purely visual helpers. The mod is display-only: the server stays authoritative for all gameplay, and the mod does nothing on servers that aren't part of the Ancients network (hardcoded allowlist).

> **Renamed from PrisonsMod (July 2026).** The mod now serves the whole Ancients network, not just the prisons gamemode, so it ships as `ancientsmod-x.y.z.jar` with mod id `ancientsmod`. If you still have a `prisonsmod-*.jar` in your `mods` folder, **delete it** — the two must not be installed together (the game will refuse to launch if they are). Your settings migrate automatically on first launch.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11 and the matching [Fabric API](https://modrinth.com/mod/fabric-api).
2. Drop `ancientsmod-x.y.z.jar` from the [latest release](https://github.com/alekswoje/AncientsMod/releases/latest) into your `mods` folder.
3. Press **F9** in game to open the settings screen. Every feature can be toggled off.

The mod checks for new releases when you join the server and can auto-update itself (`/ancientsmod update`, or click the chat prompt).

## Building

```bash
./gradlew build
```

Produces `build/libs/ancientsmod-<version>.jar` (Java 21, Gradle + Fabric Loom).

## License

MIT — see [LICENSE](LICENSE).
