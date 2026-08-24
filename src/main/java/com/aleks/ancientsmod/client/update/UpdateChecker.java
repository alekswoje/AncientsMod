package com.aleks.ancientsmod.client.update;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.FeatureToggles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the AncientsMod GitHub releases for a newer version and alerts the
 * player on the first server join of the session if one's available. One HTTP
 * request per session at most; subsequent rejoins stay silent until the next
 * Minecraft restart.
 *
 * <p>The chat alert includes a {@code [Click to update]} component that runs
 * the client-side {@code /ancientsmod update} command, which hands off to
 * {@link UpdateInstaller}. The release info is cached so the command doesn't
 * need to refetch.
 *
 * <p>Fires on a virtual thread so the join handler isn't blocked. User-facing
 * side effects (chat / toast / sound) are marshaled back to the render thread
 * via {@link MinecraftClient#execute}.
 */
public final class UpdateChecker {

    private static final String LATEST_API = "https://api.github.com/repos/alekswoje/AncientsMod/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/alekswoje/AncientsMod/releases/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** How long a fetched release stays trustworthy before we re-ask GitHub. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);

    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    /** Version we last alerted about, rather than a plain boolean: a release cut
     *  mid-session used to be swallowed by the latch, pinning the session to
     *  whatever was latest at first join. Keyed by version, a newer one re-alerts. */
    private static volatile String alertedVersion = null;
    private static volatile ReleaseInfo cachedLatest = null;
    private static volatile long cachedAtMs = 0L;

    /** Fire-and-forget. Safe to call from any thread, including the render thread. */
    public static void checkAsync(MinecraftClient mc) {
        if (mc == null) return;
        if (!FeatureToggles.isUpdateAlertEnabled()) return;
        if (!checkInFlight.compareAndSet(false, true)) return;

        Thread.ofVirtual().name("AncientsMod-UpdateCheck").start(() -> {
            try {
                String current = installedVersion();
                ReleaseInfo latest = cachedIfFresh();
                if (latest == null) latest = fetchLatestRelease();
                if (latest == null) return;
                store(latest);

                // Alert once per version, not once per session, so rejoining after a
                // new release was published still surfaces it.
                ReleaseInfo shown = latest;
                if (isNewer(latest.version(), current)) {
                    if (!latest.version().equals(alertedVersion)) {
                        mc.execute(() -> showAlert(mc, current, shown));
                    }
                } else {
                    AncientsMod.LOGGER.info("AncientsMod is up to date (have v{}, latest v{})", current, latest.version());
                }
            } catch (Exception e) {
                // Best-effort — never break the join flow over a bad network.
                AncientsMod.LOGGER.debug("AncientsMod update check failed: {}", e.toString());
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    /**
     * Cached result of the most recent successful check, or null.
     *
     * <p>May be arbitrarily stale. Anything about to <em>install</em> a jar must go
     * through {@link #fetchLatestReleaseSafe()} instead, or it downloads whatever was
     * latest at first join rather than what is latest now.
     */
    public static ReleaseInfo getCachedLatest() {
        return cachedLatest;
    }

    /** The cached release if it was fetched within {@link #CACHE_TTL}, else null. */
    private static ReleaseInfo cachedIfFresh() {
        ReleaseInfo info = cachedLatest;
        if (info == null) return null;
        return (System.currentTimeMillis() - cachedAtMs) <= CACHE_TTL.toMillis() ? info : null;
    }

    private static void store(ReleaseInfo info) {
        cachedLatest = info;
        cachedAtMs = System.currentTimeMillis();
    }

    /** Fetches the latest release synchronously, bypassing the cache. Returns null on any error. */
    public static ReleaseInfo fetchLatestReleaseSafe() {
        try {
            ReleaseInfo info = fetchLatestRelease();
            if (info != null) store(info);
            return info;
        } catch (Exception e) {
            AncientsMod.LOGGER.debug("AncientsMod fetch latest release failed: {}", e.toString());
            return null;
        }
    }

    public static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(AncientsMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        // Follow redirects so a repo rename (the GitHub API answers 301 for the old
        // name) doesn't silently kill the update check — the pre-rename PrisonsMod
        // client didn't do this, which is why it can't see releases after the rename.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AncientsMod/" + installedVersion())
                // GitHub serves /releases/latest with a 60s public max-age, so a proxy
                // or CDN in between can hand back a release that is already superseded.
                .header("Cache-Control", "no-cache")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            AncientsMod.LOGGER.debug("GitHub releases API returned HTTP {}", res.statusCode());
            return null;
        }
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        String tag = root.has("tag_name") && !root.get("tag_name").isJsonNull()
                ? root.get("tag_name").getAsString() : null;
        if (tag == null) return null;
        String htmlUrl = root.has("html_url") && !root.get("html_url").isJsonNull()
                ? root.get("html_url").getAsString() : RELEASES_PAGE;
        String assetUrl = findJarAsset(root);
        return new ReleaseInfo(stripLeadingV(tag), htmlUrl, assetUrl);
    }

    /** Returns the browser_download_url for the runnable mod jar asset
     *  ({@code ancientsmod-X.Y.Z.jar} — NOT {@code -sources.jar} or
     *  {@code -dev.jar}), or null if none found. */
    private static String findJarAsset(JsonObject root) {
        if (!root.has("assets") || !root.get("assets").isJsonArray()) return null;
        JsonArray assets = root.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "";
            if (!name.startsWith("ancientsmod-") || !name.endsWith(".jar")) continue;
            // Skip non-runnable jar variants — sources jars contain only .java
            // files and trigger a MixinApplyError on load because the compiled
            // mixin classes aren't there. Dev / shadow jars likewise.
            if (name.endsWith("-sources.jar") || name.endsWith("-dev.jar")
                    || name.endsWith("-shadow.jar")) continue;
            JsonElement url = a.get("browser_download_url");
            if (url != null && !url.isJsonNull()) return url.getAsString();
        }
        return null;
    }

    private static void showAlert(MinecraftClient mc, String current, ReleaseInfo latest) {
        // Render-thread guard against duplicate alerts if two checks raced. Keyed by
        // version so a release published later in the session still gets announced.
        if (latest.version().equals(alertedVersion)) return;
        alertedVersion = latest.version();

        boolean hasJar = latest.assetUrl() != null && !latest.assetUrl().isBlank();
        String clickLabel = hasJar ? "[Click to auto-update]" : "[Open releases page]";
        ClickEvent clickAction = hasJar
                ? new ClickEvent.RunCommand("/ancientsmod update")
                : new ClickEvent.OpenUrl(URI.create(latest.url()));
        String hoverText = hasJar
                ? "Downloads v" + latest.version() + " and applies on next restart"
                : latest.url();

        MutableText msg = Text.literal("[AncientsMod] ").formatted(Formatting.GOLD)
                .append(Text.literal("Update available: ").formatted(Formatting.WHITE))
                .append(Text.literal("v" + latest.version()).formatted(Formatting.GREEN))
                .append(Text.literal(" (you have v" + current + "). ").formatted(Formatting.GRAY))
                .append(Text.literal(clickLabel).styled(s -> s
                        .withColor(Formatting.AQUA)
                        .withUnderline(true)
                        .withClickEvent(clickAction)
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hoverText)))));

        if (mc.player != null) {
            mc.player.sendMessage(msg, false);
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
        }

        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("AncientsMod update").formatted(Formatting.GOLD),
                Text.literal("v" + latest.version() + " is available"));
    }

    /** True if {@code latest} is a strictly newer numeric semver than {@code current}. */
    static boolean isNewer(String latest, String current) {
        int[] l = parseVersion(latest);
        int[] c = parseVersion(current);
        int n = Math.max(l.length, c.length);
        for (int i = 0; i < n; i++) {
            int li = i < l.length ? l[i] : 0;
            int ci = i < c.length ? c[i] : 0;
            if (li != ci) return li > ci;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null || v.isEmpty()) return new int[]{0};
        int cut = v.length();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '-' || ch == '+') { cut = i; break; }
        }
        String core = v.substring(0, cut);
        String[] parts = core.split("\\.");
        // One extra slot for a letter-suffix hotfix (3.0.5a): the release scheme puts
        // tiny follow-ups on a trailing letter, and parsing "5a" as 0 made the hotfix
        // read as OLDER than the release it patches, so it would never install.
        int[] out = new int[parts.length + 1];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
            try { out[i] = end == 0 ? 0 : Integer.parseInt(part.substring(0, end)); }
            catch (NumberFormatException ex) { out[i] = 0; }
            if (i == parts.length - 1 && end < part.length()) {
                char suffix = Character.toLowerCase(part.charAt(end));
                if (suffix >= 'a' && suffix <= 'z') out[parts.length] = suffix - 'a' + 1;
            }
        }
        return out;
    }

    private static String stripLeadingV(String tag) {
        return (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
    }

    /** Public so {@link UpdateInstaller} and the {@code /ancientsmod update} command can hand it around. */
    public record ReleaseInfo(String version, String url, String assetUrl) {}

    private UpdateChecker() {}
}
