package com.aleks.ancientsmod.client.update;

import com.aleks.ancientsmod.AncientsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-phase auto-updater for AncientsMod.
 *
 * <p><b>Phase 1 (while MC is running):</b> downloads the new jar to
 * {@code mods/ancientsmod-X.Y.Z.jar.pending}. The {@code .pending} suffix
 * keeps Fabric from trying to load it. Old jar stays in place — Windows
 * file-locks it so we cannot replace it from inside the running JVM.
 *
 * <p><b>Phase 2 (external watcher):</b> a small installer script next to the
 * pending file is launched as a detached external process. It polls until the
 * running jar's file lock releases (i.e. this JVM exits), then deletes the old
 * {@code ancientsmod-*.jar}, renames {@code .pending → .jar}, and deletes itself.
 *
 * <p><b>Why the watcher is spawned while the game is still alive</b> (at
 * download time, and re-armed on init if a pending jar survived): JVM shutdown
 * hooks only run on a <em>graceful</em> exit. Wrapped launchers like Lunar
 * Client and Feather force-kill the game process when you quit to the launcher,
 * so a shutdown hook never fires — which is why the old "spawn on shutdown"
 * design silently failed there (the jar downloaded but never swapped in). An
 * independent OS process started before the kill survives it and completes the
 * swap once the lock frees. The shutdown hook is kept only as a fast-path
 * fallback for graceful exits, and is skipped when a live watcher is already
 * armed.
 */
public final class UpdateInstaller {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final long MIN_JAR_BYTES = 4_096;

    private static final AtomicBoolean downloadInFlight = new AtomicBoolean(false);
    /** True once the external watcher process has been launched this JVM (download-time or re-arm). */
    private static final AtomicBoolean watcherSpawned = new AtomicBoolean(false);
    private static volatile boolean initialised = false;

    /** Call once on client init. Registers the shutdown hook, clears stale pending files, and
     *  re-arms the watcher if a previous session staged an update that never got applied. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        try {
            cleanStalePending();
        } catch (Exception e) {
            AncientsMod.LOGGER.debug("AncientsMod: pending-file cleanup skipped: {}", e.toString());
        }
        // If a newer pending jar survived from a previous session, the swap never
        // completed (e.g. the launcher force-killed the JVM, so neither the shutdown
        // hook nor a prior watcher finished). Re-arm the watcher now so it can apply
        // the update when THIS session exits, however it exits.
        try {
            if (hasNewerPending()) spawnWatcher();
        } catch (Exception e) {
            AncientsMod.LOGGER.debug("AncientsMod: watcher re-arm skipped: {}", e.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(UpdateInstaller::runInstallerOnShutdown,
                "AncientsMod-UpdateInstaller-Shutdown"));
    }

    /**
     * Entry point for the {@code /ancientsmod update} chat command. Uses the cached
     * release info from {@link UpdateChecker} when available; otherwise re-fetches
     * on a virtual thread. Either way, hands off to {@link #installAsync} for the
     * actual download.
     */
    public static void runFromCommand(MinecraftClient mc) {
        if (mc == null) return;
        UpdateChecker.ReleaseInfo cached = UpdateChecker.getCachedLatest();
        if (cached != null) {
            String current = installedVersion();
            if (!UpdateChecker.isNewer(cached.version(), current)) {
                chat(mc, "Already on the latest version (v" + current + ").", Formatting.GREEN);
                return;
            }
            installAsync(mc, cached);
            return;
        }
        chat(mc, "Checking for latest release...", Formatting.GOLD);
        Thread.ofVirtual().name("AncientsMod-UpdateCmd-Fetch").start(() -> {
            UpdateChecker.ReleaseInfo info = UpdateChecker.fetchLatestReleaseSafe();
            mc.execute(() -> {
                if (info == null) {
                    chat(mc, "Could not fetch release info — check your connection and try again.", Formatting.RED);
                    return;
                }
                String current = installedVersion();
                if (!UpdateChecker.isNewer(info.version(), current)) {
                    chat(mc, "Already on the latest version (v" + current + ").", Formatting.GREEN);
                    return;
                }
                installAsync(mc, info);
            });
        });
    }

    /**
     * Fire-and-forget. Downloads the release asset to a staged {@code .pending}
     * file and tells the player to restart. Safe to call from any thread.
     */
    public static void installAsync(MinecraftClient mc, UpdateChecker.ReleaseInfo info) {
        if (mc == null) return;
        if (info == null) {
            chat(mc, "No update info available — try rejoining the server.", Formatting.RED);
            return;
        }
        if (info.assetUrl() == null || info.assetUrl().isBlank()) {
            chat(mc, "Latest release has no downloadable jar — open the releases page to grab it manually.", Formatting.RED);
            return;
        }
        if (!downloadInFlight.compareAndSet(false, true)) {
            chat(mc, "An update is already downloading...", Formatting.GRAY);
            return;
        }

        chat(mc, "Downloading v" + info.version() + "...", Formatting.GOLD);
        Thread.ofVirtual().name("AncientsMod-UpdateInstall").start(() -> {
            try {
                clearOtherPendings(info.version());
                Path staged = downloadToPending(info);
                // Arm the watcher NOW, while the game is still running, so the swap
                // survives the JVM being force-killed by a wrapped launcher.
                spawnWatcher();
                long kb = Files.size(staged) / 1024;
                mc.execute(() -> chat(mc,
                        "v" + info.version() + " downloaded (" + kb + " KB). Fully close and reopen Minecraft to apply.",
                        Formatting.GREEN));
            } catch (Exception e) {
                AncientsMod.LOGGER.warn("AncientsMod update download failed", e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                mc.execute(() -> chat(mc, "Update download failed: " + msg, Formatting.RED));
            } finally {
                downloadInFlight.set(false);
            }
        });
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private static Path downloadToPending(UpdateChecker.ReleaseInfo info) throws IOException, InterruptedException {
        Path dir = modsDir();
        Files.createDirectories(dir);
        Path target = dir.resolve("ancientsmod-" + info.version() + ".jar.pending");
        Path tmp = dir.resolve("ancientsmod-" + info.version() + ".jar.pending.partial");
        Files.deleteIfExists(tmp);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.assetUrl()))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "AncientsMod/" + installedVersion())
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + res.statusCode());
        }
        long size = Files.size(tmp);
        if (size < MIN_JAR_BYTES) {
            Files.deleteIfExists(tmp);
            throw new IOException("Downloaded file is suspiciously small (" + size + " bytes)");
        }
        // Cheap header check — every jar/zip starts with PK\3\4.
        try (var in = Files.newInputStream(tmp)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
                Files.deleteIfExists(tmp);
                throw new IOException("Downloaded file is not a valid jar (bad magic bytes)");
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        AncientsMod.LOGGER.info("AncientsMod: staged v{} update at {}", info.version(), target);
        return target;
    }

    /** Remove any other pending files so the installer doesn't race them. */
    private static void clearOtherPendings(String keepVersion) {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ancientsmod-*.jar.pending")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.equals("ancientsmod-" + keepVersion + ".jar.pending")) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    /** On startup, delete any pending file whose version isn't newer than the installed one. */
    private static void cleanStalePending() throws IOException {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return;
        String current = installedVersion();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ancientsmod-*.jar.pending")) {
            for (Path p : ds) {
                String pendingVersion = versionFromPending(p.getFileName().toString());
                if (pendingVersion == null || !UpdateChecker.isNewer(pendingVersion, current)) {
                    Files.deleteIfExists(p);
                    AncientsMod.LOGGER.info("AncientsMod: removed stale pending update {} (installed v{})",
                            p.getFileName(), current);
                }
            }
        }
        // Legacy PrisonsMod pendings can only come from the pre-rename mod line. If we're
        // running, the migration already happened — delete them before an old watcher
        // process can swap one in next to us (which would duplicate the mod id).
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prisonsmod-*.jar.pending")) {
            for (Path p : ds) {
                Files.deleteIfExists(p);
                AncientsMod.LOGGER.info("AncientsMod: removed legacy pending update {}", p.getFileName());
            }
        }
    }

    private static String versionFromPending(String fileName) {
        // ancientsmod-X.Y.Z.jar.pending
        String prefix = "ancientsmod-";
        String suffix = ".jar.pending";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) return null;
        return fileName.substring(prefix.length(), fileName.length() - suffix.length());
    }

    // ── Watcher spawn ───────────────────────────────────────────────────────

    /**
     * Writes the installer script and launches the detached external watcher
     * process — at most once per JVM (CAS-guarded). Unlike the shutdown hook,
     * this is meant to be called while the game is still alive, so the watcher
     * survives the JVM being force-killed (e.g. Lunar/Feather "quit to
     * launcher"), which is exactly when shutdown hooks do NOT run. The watcher
     * polls until the running jar's lock releases (this JVM exits), then swaps
     * {@code .pending → .jar}.
     */
    private static void spawnWatcher() {
        if (!watcherSpawned.compareAndSet(false, true)) return;
        try {
            Path script = writeInstallerScript();
            spawnInstaller(script);
            AncientsMod.LOGGER.info("AncientsMod: update watcher armed — pending update applies when the game exits");
        } catch (Exception e) {
            watcherSpawned.set(false);  // allow the shutdown hook to retry as a fallback
            AncientsMod.LOGGER.debug("AncientsMod: update watcher spawn failed: {}", e.toString());
        }
    }

    // ── Shutdown-time spawn (fast-path fallback for graceful exits) ──────────

    private static void runInstallerOnShutdown() {
        try {
            if (watcherSpawned.get()) return;   // a live watcher is already handling the swap
            if (!hasPendingFiles()) return;
            Path script = writeInstallerScript();
            spawnInstaller(script);
        } catch (Exception e) {
            AncientsMod.LOGGER.debug("AncientsMod: shutdown installer spawn failed: {}", e.toString());
        }
    }

    /** True if a staged {@code .pending} jar with a version newer than the installed one exists. */
    private static boolean hasNewerPending() {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return false;
        String current = installedVersion();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ancientsmod-*.jar.pending")) {
            for (Path p : ds) {
                String v = versionFromPending(p.getFileName().toString());
                if (v != null && UpdateChecker.isNewer(v, current)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static boolean hasPendingFiles() {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ancientsmod-*.jar.pending")) {
            return ds.iterator().hasNext();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void spawnInstaller(Path script) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
            cmd.add("-File");
            cmd.add(script.toAbsolutePath().toString());
        } else {
            cmd.add("/bin/sh");
            cmd.add(script.toAbsolutePath().toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(script.getParent().toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    // ── Installer script ────────────────────────────────────────────────────

    /** Writes (or refreshes) the installer script next to the pending file. Returns its path. */
    private static Path writeInstallerScript() throws IOException {
        Path dir = modsDir();
        Files.createDirectories(dir);
        Path script = dir.resolve(isWindows() ? ".ancientsmod-installer.ps1" : ".ancientsmod-installer.sh");
        String body = isWindows() ? POWERSHELL_INSTALLER : BASH_INSTALLER;
        Files.writeString(script, body, StandardCharsets.UTF_8);
        if (!isWindows()) {
            try {
                script.toFile().setExecutable(true, false);
            } catch (Exception ignored) {}
        }
        return script;
    }

    // Polls until the running jar's lock releases (this JVM exits), then for each
    // pending jar deletes the old ancientsmod-*.jar (and any legacy prisonsmod-*.jar
    // left over from before the 2026-07 mod rename) and renames .pending -> .jar,
    // and finally self-deletes. Spawned while the game is still alive, so the wait
    // is bounded generously (6h) to outlast a play session; the mod re-arms this
    // watcher on its next launch if the deadline lapsed first. CRITICAL: the
    // pending jar is moved into place ONLY after the locked old jar is confirmed
    // gone — otherwise a deadline-while-locked path could leave BOTH jars on disk
    // and crash Fabric on a duplicate mod id.
    private static final String POWERSHELL_INSTALLER =
            "$ErrorActionPreference = 'Continue'\n" +
            "$dir = $PSScriptRoot\n" +
            "$deadline = (Get-Date).AddSeconds(21600)\n" +
            "function Test-Unlocked($path) {\n" +
            "    try { $fs = [System.IO.File]::Open($path, 'Open', 'ReadWrite', 'None'); $fs.Close(); return $true }\n" +
            "    catch { return $false }\n" +
            "}\n" +
            "while ($true) {\n" +
            "    $pending = @(Get-ChildItem -LiteralPath $dir -Filter 'ancientsmod-*.jar.pending' -ErrorAction SilentlyContinue)\n" +
            "    if (-not $pending -or $pending.Count -eq 0) { break }\n" +
            "    $allDone = $true\n" +
            "    foreach ($p in $pending) {\n" +
            "        $target = $p.FullName.Substring(0, $p.FullName.Length - '.pending'.Length)\n" +
            "        $olds = @(Get-ChildItem -LiteralPath $dir -ErrorAction SilentlyContinue |\n" +
            "                 Where-Object { ($_.Name -like 'ancientsmod-*.jar' -or $_.Name -like 'prisonsmod-*.jar') -and $_.FullName -ne $target })\n" +
            "        $locked = $false\n" +
            "        foreach ($o in $olds) { if (-not (Test-Unlocked $o.FullName)) { $locked = $true; break } }\n" +
            "        if ($locked) { $allDone = $false; continue }\n" +
            "        foreach ($o in $olds) { Remove-Item -LiteralPath $o.FullName -Force -ErrorAction SilentlyContinue }\n" +
            "        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue }\n" +
            "        Move-Item -LiteralPath $p.FullName -Destination $target -Force -ErrorAction SilentlyContinue\n" +
            "    }\n" +
            "    if ($allDone) { break }\n" +
            "    if ((Get-Date) -ge $deadline) { break }\n" +
            "    Start-Sleep -Milliseconds 750\n" +
            "}\n" +
            "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n";

    private static final String BASH_INSTALLER =
            "#!/usr/bin/env sh\n" +
            "set +e\n" +
            "dir=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n" +
            "sleep 2\n" +
            "for pending in \"$dir\"/ancientsmod-*.jar.pending; do\n" +
            "    [ -e \"$pending\" ] || continue\n" +
            "    target=\"${pending%.pending}\"\n" +
            "    for old in \"$dir\"/ancientsmod-*.jar \"$dir\"/prisonsmod-*.jar; do\n" +
            "        [ -e \"$old\" ] || continue\n" +
            "        [ \"$old\" = \"$target\" ] && continue\n" +
            "        rm -f \"$old\"\n" +
            "    done\n" +
            "    rm -f \"$target\"\n" +
            "    mv -f \"$pending\" \"$target\"\n" +
            "done\n" +
            "rm -f \"$0\"\n";

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Path modsDir() {
        Path jar = FabricLoader.getInstance().getModContainer(AncientsMod.MOD_ID)
                .map(c -> c.getOrigin().getPaths())
                .filter(paths -> !paths.isEmpty())
                .map(paths -> paths.get(0))
                .orElse(null);
        if (jar != null && Files.isRegularFile(jar) && jar.getParent() != null) {
            return jar.getParent();
        }
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    private static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(AncientsMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static void chat(MinecraftClient mc, String message, Formatting colour) {
        if (mc == null || mc.player == null) return;
        mc.player.sendMessage(Text.literal("[AncientsMod] ").formatted(Formatting.GOLD)
                .append(Text.literal(message).formatted(colour)), false);
    }

    private UpdateInstaller() {}
}
