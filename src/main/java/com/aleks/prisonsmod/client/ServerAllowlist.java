package com.aleks.prisonsmod.client;

import net.minecraft.client.network.ServerInfo;

import java.util.Locale;
import java.util.Set;

/**
 * Hard gate that keeps the mod dormant on any server that isn't RooPrisons.
 * Checked on every connection join; if the current server address doesn't
 * match, the mod's packet receivers and renderers remain inert.
 *
 * <p>The allowlist is intentionally hardcoded and built into the jar — a
 * malicious server impersonating RooPrisons would also need to spoof DNS /
 * IP, which is out of scope for an open-source client mod. Belt-and-braces:
 * even on an allowed server, feature gating still requires valid {@code
 * prisonsmod:v1} packets to arrive.
 *
 * <p>Matching is case-insensitive and ignores port. Subdomains of
 * {@code rooprisons.com} match via suffix check.
 */
public final class ServerAllowlist {

    /** Literal hostnames (case-insensitive). */
    private static final Set<String> EXACT_HOSTS = Set.of(
            "ancients.gg",
            "play.ancients.gg",
            "dev.ancients.gg",
            "season2.ancients.gg",
            "hub.ancients.gg",
            "rooprisons.com",
            "play.rooprisons.com",
            "dev.rooprisons.com",
            "season2.rooprisons.com",
            "hub.rooprisons.com",
            "174.136.202.138",
            "localhost",
            "127.0.0.1"
    );

    /** Any hostname ending in one of these is accepted. */
    private static final Set<String> SUFFIX_DOMAINS = Set.of(
            ".ancients.gg",
            ".rooprisons.com"
    );

    private static volatile boolean allowed = false;

    /** Called on ClientPlayConnectionEvents.JOIN. */
    public static void onJoin(ServerInfo info) {
        if (info == null || info.address == null) {
            allowed = false;
            return;
        }
        String host = stripPort(info.address).toLowerCase(Locale.ROOT);
        if (EXACT_HOSTS.contains(host)) {
            allowed = true;
            return;
        }
        for (String suffix : SUFFIX_DOMAINS) {
            if (host.endsWith(suffix)) {
                allowed = true;
                return;
            }
        }
        allowed = false;
    }

    /** Called on ClientPlayConnectionEvents.DISCONNECT. */
    public static void onDisconnect() {
        allowed = false;
    }

    /** True only while connected to an allowlisted server. */
    public static boolean isAllowed() {
        return allowed;
    }

    private static String stripPort(String address) {
        int colon = address.lastIndexOf(':');
        // If bracketed IPv6 or no colon, return as-is.
        if (colon < 0 || address.indexOf(']') > colon) return address;
        return address.substring(0, colon);
    }

    private ServerAllowlist() {}
}
