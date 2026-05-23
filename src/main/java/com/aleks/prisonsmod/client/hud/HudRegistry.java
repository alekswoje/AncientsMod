package com.aleks.prisonsmod.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of all moveable HUD elements. Each element calls
 * {@link #register(HudElement)} once during client init. The renderer iterates
 * this list every frame.
 */
public final class HudRegistry {

    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    private HudRegistry() {}

    public static void register(HudElement element) {
        for (HudElement existing : ELEMENTS) {
            if (existing.id().equals(element.id())) {
                throw new IllegalArgumentException("duplicate HUD element id: " + element.id());
            }
        }
        ELEMENTS.add(element);
    }

    public static List<HudElement> all() {
        return Collections.unmodifiableList(ELEMENTS);
    }

    public static HudElement byId(String id) {
        for (HudElement e : ELEMENTS) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }
}
