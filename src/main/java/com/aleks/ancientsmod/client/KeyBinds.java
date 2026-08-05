package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.screen.MufflerScreen;
import com.aleks.ancientsmod.client.screen.SettingsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * Client key bindings for AncientsMod A/B testing.
 *
 * <p>Default bindings are chosen to not collide with common vanilla actions;
 * users can rebind via Options → Controls → "AncientsMod".
 */
public final class KeyBinds {

    /** Toggle client-side mining-crack prediction (mine-start latency hint). */
    public static final KeyBinding TOGGLE_MINE_PREDICT = new KeyBinding(
            "key.ancientsmod.toggleMinePredict",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            KeyBinding.Category.MISC
    );

    /** Send a gang ping at feet (tap) or at the cursor target (hold). Default: middle mouse button. */
    public static final KeyBinding GANG_PING = new KeyBinding(
            "key.ancientsmod.gangPing",
            InputUtil.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            KeyBinding.Category.MULTIPLAYER
    );

    /** Open the AncientsMod settings screen. */
    public static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.ancientsmod.openSettings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            KeyBinding.Category.MISC
    );

    /** Open the Sound & Particle Muffler screen. Unbound by default — also
     *  reachable from F9 → Audio & Particles and {@code /muffler}. */
    public static final KeyBinding OPEN_MUFFLER = new KeyBinding(
            "key.ancientsmod.openMuffler",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
    );

    /**
     * Toggle item lock on the slot currently hovered in any inventory screen.
     * Default Z (unbound in vanilla 1.21). Consumed by the screen-key mixin,
     * not a tick handler — vanilla key bindings don't fire while a Screen is
     * open, so the mixin reads {@link #matchesKey(int, int)} directly.
     */
    public static final KeyBinding TOGGLE_ITEM_LOCK = new KeyBinding(
            "key.ancientsmod.toggleItemLock",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            KeyBinding.Category.INVENTORY
    );

    /**
     * Hold to zoom (OptiFine-style). Level-triggered — the FOV mixin reads
     * {@code ZOOM.isPressed()} every frame rather than polling wasPressed(),
     * so zoom holds exactly as long as the key does. Default C.
     */
    public static final KeyBinding ZOOM = new KeyBinding(
            "key.ancientsmod.zoom",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            KeyBinding.Category.MISC
    );

    /** Returns true if the given keyboard event matches the lock keybind's currently-bound key. Used by the screen mixin. */
    public static boolean matchesItemLockKey(KeyInput input) {
        return TOGGLE_ITEM_LOCK.matchesKey(input);
    }

    public static void register() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_MINE_PREDICT);
        KeyBindingHelper.registerKeyBinding(GANG_PING);
        KeyBindingHelper.registerKeyBinding(OPEN_SETTINGS);
        KeyBindingHelper.registerKeyBinding(OPEN_MUFFLER);
        KeyBindingHelper.registerKeyBinding(TOGGLE_ITEM_LOCK);
        KeyBindingHelper.registerKeyBinding(ZOOM);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_MINE_PREDICT.wasPressed()) {
                // Clearing in-flight predictions on switch-off is done by
                // FeatureToggles.setMinePredict, so the settings screen gets it too.
                boolean nowOn = FeatureToggles.toggleMinePredict();
                notify(client, nowOn);
            }
            while (OPEN_SETTINGS.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new SettingsScreen(null));
                }
            }
            while (OPEN_MUFFLER.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MufflerScreen(null));
                }
            }
        });

        AncientsMod.LOGGER.info("AncientsMod keybinds registered");
    }

    private static void notify(MinecraftClient client, boolean nowOn) {
        if (client.player == null) return;
        Formatting color = nowOn ? Formatting.GREEN : Formatting.RED;
        String state = nowOn ? "ON" : "OFF";
        Text msg = Text.literal("[AncientsMod] Mine Predict: ")
                .append(Text.literal(state).formatted(color, Formatting.BOLD));
        client.player.sendMessage(msg, true); // action bar
    }

    private KeyBinds() {}
}
