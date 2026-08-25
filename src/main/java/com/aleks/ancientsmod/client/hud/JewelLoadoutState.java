package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.JewelLoadoutsPayload;

import java.util.List;

/** Client-side jewel loadout state, replaced wholesale by each server push. */
public final class JewelLoadoutState {

    private static volatile List<JewelLoadoutsPayload.Page> pages = List.of();
    private static volatile int activePage;

    private JewelLoadoutState() {}

    public static void update(JewelLoadoutsPayload payload) {
        pages = payload.pages();
        activePage = payload.activePage();
        com.aleks.ancientsmod.AncientsMod.LOGGER.info(
                "AncientsMod: jewel loadouts updated ({} pages, active {})",
                pages.size(), activePage);
    }

    public static List<JewelLoadoutsPayload.Page> pages() {
        return pages;
    }

    public static int activePage() {
        return activePage;
    }

    /** True until the server has pushed anything, or when it pushed none. */
    public static boolean isEmpty() {
        return pages.isEmpty();
    }

    public static void clear() {
        pages = List.of();
        activePage = 0;
    }
}
