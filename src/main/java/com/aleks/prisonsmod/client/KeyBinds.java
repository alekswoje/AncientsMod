package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.render.MinePredictRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
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

    public static void register() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_MINE_PREDICT);
        KeyBindingHelper.registerKeyBinding(GANG_PING);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_MINE_PREDICT.wasPressed()) {
                boolean nowOn = FeatureToggles.toggleMinePredict();
                if (!nowOn) {
                    // Clear any in-flight predicted cracks so the switch-off is visible.
                    MinePredictRenderer.reset();
                }
                notify(client, nowOn);
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
