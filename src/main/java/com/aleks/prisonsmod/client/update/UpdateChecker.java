package com.aleks.prisonsmod.client.update;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
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
 * Checks the PrisonsMod GitHub releases for a newer version and alerts the
 * player on the first server join of the session if one's available. One HTTP
 * request per session at most; subsequent rejoins stay silent until the next
 * Minecraft restart.
 *
 * <p>The chat alert includes a {@code [Click to update]} component that runs
 * the client-side {@code /prisonsmod update} command, which hands off to
 * {@link UpdateInstaller}. The release info is cached so the command doesn't
 * need to refetch.
 *
 * <p>Fires on a virtual thread so the join handler isn't blocked. User-facing
 * side effects (chat / toast / sound) are marshaled back to the render thread
 * via {@link MinecraftClient#execute}.
 */
public final class UpdateChecker {

    private static final String LATEST_API = "https://api.github.com/repos/alekswoje/PrisonsMod/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/alekswoje/PrisonsMod/releases/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private static volatile boolean alertShown = false;
    private static volatile ReleaseInfo cachedLatest = null;

    /** Fire-and-forget. Safe to call from any thread, including the render thread. */
    public static void checkAsync(MinecraftClient mc) {
        if (mc == null || alertShown) return;
        if (!FeatureToggles.isUpdateAlertEnabled()) return;
        if (!checkInFlight.compareAndSet(false, true)) return;

        Thread.ofVirtual().name("PrisonsMod-UpdateCheck").start(() -> {
            try {
                String current = installedVersion();
                ReleaseInfo latest = fetchLatestRelease();
                if (latest == null) return;
                cachedLatest = latest;

                if (isNewer(latest.version(), current)) {
                    mc.execute(() -> showAlert(mc, current, latest));
                } else {
                    PrisonsMod.LOGGER.info("PrisonsMod is up to date (have v{}, latest v{})", current, latest.version());
                }
            } catch (Exception e) {
                // Best-effort — never break the join flow over a bad network.
                PrisonsMod.LOGGER.debug("PrisonsMod update check failed: {}", e.toString());
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    /** Cached result of the most recent successful check, or null. */
    public static ReleaseInfo getCachedLatest() {
        return cachedLatest;
    }

    /** Fetches the latest release synchronously. Returns null on any error. */
    public static ReleaseInfo fetchLatestReleaseSafe() {
        try {
            ReleaseInfo info = fetchLatestRelease();
            if (info != null) cachedLatest = info;
            return info;
        } catch (Exception e) {
            PrisonsMod.LOGGER.debug("PrisonsMod fetch latest release failed: {}", e.toString());
            return null;
        }
    }

    public static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(PrisonsMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PrisonsMod/" + installedVersion())
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            PrisonsMod.LOGGER.debug("GitHub releases API returned HTTP {}", res.statusCode());
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

    /** Returns the browser_download_url for the first {@code prisonsmod-*.jar} asset, or null. */
    private static String findJarAsset(JsonObject root) {
        if (!root.has("assets") || !root.get("assets").isJsonArray()) return null;
        JsonArray assets = root.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "";
            if (name.startsWith("prisonsmod-") && name.endsWith(".jar")) {
                JsonElement url = a.get("browser_download_url");
                if (url != null && !url.isJsonNull()) return url.getAsString();
            }
        }
        return null;
    }

    private static void showAlert(MinecraftClient mc, String current, ReleaseInfo latest) {
        // Render-thread guard against duplicate alerts if two checks raced.
        if (alertShown) return;
        alertShown = true;

        boolean hasJar = latest.assetUrl() != null && !latest.assetUrl().isBlank();
        String clickLabel = hasJar ? "[Click to auto-update]" : "[Open releases page]";
        ClickEvent clickAction = hasJar
                ? new ClickEvent.RunCommand("/prisonsmod update")
                : new ClickEvent.OpenUrl(URI.create(latest.url()));
        String hoverText = hasJar
                ? "Downloads v" + latest.version() + " and applies on next restart"
                : latest.url();

        MutableText msg = Text.literal("[PrisonsMod] ").formatted(Formatting.GOLD)
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
                Text.literal("PrisonsMod update").formatted(Formatting.GOLD),
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
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException ex) { out[i] = 0; }
        }
        return out;
    }

    private static String stripLeadingV(String tag) {
        return (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
    }

    /** Public so {@link UpdateInstaller} and the {@code /prisonsmod update} command can hand it around. */
    public record ReleaseInfo(String version, String url, String assetUrl) {}

    private UpdateChecker() {}
}
