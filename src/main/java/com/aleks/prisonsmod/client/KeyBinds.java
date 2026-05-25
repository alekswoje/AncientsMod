package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.screen.SettingsScreen;
import com.aleks.prisonsmod.render.MinePredictRenderer;
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
 * Client key bindings for PrisonsMod A/B testing.
 *
 * <p>Default bindings are chosen to not collide with common vanilla actions;
 * users can rebind via Options → Controls → "PrisonsMod".
 */
public final class KeyBinds {

    /** Toggle client-side mining-crack prediction (mine-start latency hint). */
    public static final KeyBinding TOGGLE_MINE_PREDICT = new KeyBinding(
            "key.prisonsmod.toggleMinePredict",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            KeyBinding.Category.MISC
    );

    /** Send a gang ping at feet (tap) or at the cursor target (hold). Default: middle mouse button. */
    public static final KeyBinding GANG_PING = new KeyBinding(
            "key.prisonsmod.gangPing",
            InputUtil.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            KeyBinding.Category.MULTIPLAYER
    );

    /** Open the PrisonsMod settings screen. */
    public static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.prisonsmod.openSettings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            KeyBinding.Category.MISC
    );

    /**
     * Toggle item lock on the slot currently hovered in any inventory screen.
     * Default Z (unbound in vanilla 1.21). Consumed by the screen-key mixin,
     * not a tick handler — vanilla key bindings don't fire while a Screen is
     * open, so the mixin reads {@link #matchesKey(int, int)} directly.
     */
    public static final KeyBinding TOGGLE_ITEM_LOCK = new KeyBinding(
            "key.prisonsmod.toggleItemLock",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            KeyBinding.Category.INVENTORY
    );

    /** Returns true if the given keyboard event matches the lock keybind's currently-bound key. Used by the screen mixin. */
    public static boolean matchesItemLockKey(KeyInput input) {
        return TOGGLE_ITEM_LOCK.matchesKey(input);
    }

    public static void register() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_MINE_PREDICT);
        KeyBindingHelper.registerKeyBinding(GANG_PING);
        KeyBindingHelper.registerKeyBinding(OPEN_SETTINGS);
        KeyBindingHelper.registerKeyBinding(TOGGLE_ITEM_LOCK);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_MINE_PREDICT.wasPressed()) {
                boolean nowOn = FeatureToggles.toggleMinePredict();
                if (!nowOn) {
                    // Clear any in-flight predicted cracks so the switch-off is visible.
                    MinePredictRenderer.reset();
                }
                notify(client, nowOn);
            }
            while (OPEN_SETTINGS.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new SettingsScreen(null));
                }
            }
        });

        PrisonsMod.LOGGER.info("PrisonsMod keybinds registered");
    }

    private static void notify(MinecraftClient client, boolean nowOn) {
        if (client.player == null) return;
        Formatting color = nowOn ? Formatting.GREEN : Formatting.RED;
        String state = nowOn ? "ON" : "OFF";
        Text msg = Text.literal("[PrisonsMod] Mine Predict: ")
                .append(Text.literal(state).formatted(color, Formatting.BOLD));
        client.player.sendMessage(msg, true); // action bar
    }

    private KeyBinds() {}
}
