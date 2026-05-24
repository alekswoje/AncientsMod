package com.aleks.prisonsmod.client.update;

import com.aleks.prisonsmod.PrisonsMod;
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
 * Two-phase auto-updater for PrisonsMod.
 *
 * <p><b>Phase 1 (while MC is running):</b> downloads the new jar to
 * {@code mods/prisonsmod-X.Y.Z.jar.pending}. The {@code .pending} suffix
 * keeps Fabric from trying to load it. Old jar stays in place — Windows
 * file-locks it so we cannot replace it from inside the running JVM.
 *
 * <p><b>Phase 2 (on JVM shutdown):</b> a small installer script next to
 * the pending file is spawned as an external process. It waits for the
 * old jar's file lock to release (i.e. for the JVM to fully exit), deletes
 * the old {@code prisonsmod-*.jar}, renames {@code .pending → .jar}, then
 * deletes itself. The script is written once; the shutdown hook only
 * spawns it when a pending file actually exists.
 */
public final class UpdateInstaller {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final long MIN_JAR_BYTES = 4_096;

    private static final AtomicBoolean downloadInFlight = new AtomicBoolean(false);
    private static volatile boolean initialised = false;

    /** Call once on client init. Registers the shutdown hook and clears any stale pending files. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        try {
            cleanStalePending();
        } catch (Exception e) {
            PrisonsMod.LOGGER.debug("PrisonsMod: pending-file cleanup skipped: {}", e.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(UpdateInstaller::runInstallerOnShutdown,
                "PrisonsMod-UpdateInstaller-Shutdown"));
    }

    /**
     * Entry point for the {@code /prisonsmod update} chat command. Uses the cached
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
        Thread.ofVirtual().name("PrisonsMod-UpdateCmd-Fetch").start(() -> {
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
        Thread.ofVirtual().name("PrisonsMod-UpdateInstall").start(() -> {
            try {
                clearOtherPendings(info.version());
                Path staged = downloadToPending(info);
                writeInstallerScript();
                long kb = Files.size(staged) / 1024;
                mc.execute(() -> chat(mc,
                        "v" + info.version() + " downloaded (" + kb + " KB). Close and reopen Minecraft to apply.",
                        Formatting.GREEN));
            } catch (Exception e) {
                PrisonsMod.LOGGER.warn("PrisonsMod update download failed", e);
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
        Path target = dir.resolve("prisonsmod-" + info.version() + ".jar.pending");
        Path tmp = dir.resolve("prisonsmod-" + info.version() + ".jar.pending.partial");
        Files.deleteIfExists(tmp);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.assetUrl()))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "PrisonsMod/" + installedVersion())
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
        PrisonsMod.LOGGER.info("PrisonsMod: staged v{} update at {}", info.version(), target);
        return target;
    }

    /** Remove any other pending files so the installer doesn't race them. */
    private static void clearOtherPendings(String keepVersion) {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prisonsmod-*.jar.pending")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.equals("prisonsmod-" + keepVersion + ".jar.pending")) {
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
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prisonsmod-*.jar.pending")) {
            for (Path p : ds) {
                String pendingVersion = versionFromPending(p.getFileName().toString());
                if (pendingVersion == null || !UpdateChecker.isNewer(pendingVersion, current)) {
                    Files.deleteIfExists(p);
                    PrisonsMod.LOGGER.info("PrisonsMod: removed stale pending update {} (installed v{})",
                            p.getFileName(), current);
                }
            }
        }
    }

    private static String versionFromPending(String fileName) {
        // prisonsmod-X.Y.Z.jar.pending
        String prefix = "prisonsmod-";
        String suffix = ".jar.pending";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) return null;
        return fileName.substring(prefix.length(), fileName.length() - suffix.length());
    }

    // ── Shutdown-time spawn ─────────────────────────────────────────────────

    private static void runInstallerOnShutdown() {
        try {
            if (!hasPendingFiles()) return;
            Path script = writeInstallerScript();
            spawnInstaller(script);
        } catch (Exception e) {
            PrisonsMod.LOGGER.debug("PrisonsMod: shutdown installer spawn failed: {}", e.toString());
        }
    }

    private static boolean hasPendingFiles() {
        Path dir = modsDir();
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "prisonsmod-*.jar.pending")) {
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
        Path script = dir.resolve(isWindows() ? ".prisonsmod-installer.ps1" : ".prisonsmod-installer.sh");
        String body = isWindows() ? POWERSHELL_INSTALLER : BASH_INSTALLER;
        Files.writeString(script, body, StandardCharsets.UTF_8);
        if (!isWindows()) {
            try {
                script.toFile().setExecutable(true, false);
            } catch (Exception ignored) {}
        }
        return script;
    }

    // Waits for the old jar's lock to release (JVM exit), deletes any existing
    // prisonsmod-*.jar except the freshly-installed target, renames .pending →
    // .jar, then self-deletes. Resilient to multiple pending files (caller is
    // expected to keep only one, but we don't crash if there are more).
    private static final String POWERSHELL_INSTALLER =
            "$ErrorActionPreference = 'Continue'\n" +
            "$dir = $PSScriptRoot\n" +
            "$pending = @(Get-ChildItem -LiteralPath $dir -Filter 'prisonsmod-*.jar.pending' -ErrorAction SilentlyContinue)\n" +
            "if (-not $pending -or $pending.Count -eq 0) {\n" +
            "    Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n" +
            "    exit 0\n" +
            "}\n" +
            "$deadline = (Get-Date).AddSeconds(120)\n" +
            "foreach ($p in $pending) {\n" +
            "    $target = $p.FullName.Substring(0, $p.FullName.Length - '.pending'.Length)\n" +
            "    $olds = @(Get-ChildItem -LiteralPath $dir -Filter 'prisonsmod-*.jar' -ErrorAction SilentlyContinue |\n" +
            "             Where-Object { $_.FullName -ne $target })\n" +
            "    foreach ($o in $olds) {\n" +
            "        while ((Get-Date) -lt $deadline) {\n" +
            "            try {\n" +
            "                $fs = [System.IO.File]::Open($o.FullName, 'Open', 'ReadWrite', 'None')\n" +
            "                $fs.Close()\n" +
            "                break\n" +
            "            } catch {\n" +
            "                Start-Sleep -Milliseconds 500\n" +
            "            }\n" +
            "        }\n" +
            "        Remove-Item -LiteralPath $o.FullName -Force -ErrorAction SilentlyContinue\n" +
            "    }\n" +
            "    if (Test-Path -LiteralPath $target) {\n" +
            "        Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue\n" +
            "    }\n" +
            "    Move-Item -LiteralPath $p.FullName -Destination $target -Force -ErrorAction SilentlyContinue\n" +
            "}\n" +
            "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n";

    private static final String BASH_INSTALLER =
            "#!/usr/bin/env sh\n" +
            "set +e\n" +
            "dir=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n" +
            "sleep 2\n" +
            "for pending in \"$dir\"/prisonsmod-*.jar.pending; do\n" +
            "    [ -e \"$pending\" ] || continue\n" +
            "    target=\"${pending%.pending}\"\n" +
            "    for old in \"$dir\"/prisonsmod-*.jar; do\n" +
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
        Path jar = FabricLoader.getInstance().getModContainer(PrisonsMod.MOD_ID)
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
        return FabricLoader.getInstance().getModContainer(PrisonsMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static void chat(MinecraftClient mc, String message, Formatting colour) {
        if (mc == null || mc.player == null) return;
        mc.player.sendMessage(Text.literal("[PrisonsMod] ").formatted(Formatting.GOLD)
                .append(Text.literal(message).formatted(colour)), false);
    }

    private UpdateInstaller() {}
}
