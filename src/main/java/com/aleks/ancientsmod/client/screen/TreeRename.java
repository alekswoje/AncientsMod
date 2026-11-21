package com.aleks.ancientsmod.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Inline rename box for a saved build (mining tree) or preset (Hunter tree). Right-click opens
 * it over the plaque; Enter sends {@code /<command> <slot> <name>} (the server checks and saves
 * the name and pushes a fresh state), Escape or a click elsewhere drops it. An empty name puts
 * the default back.
 */
final class TreeRename {

    static final int MAX_LENGTH = 16;

    private final String command;
    private TextFieldWidget field;
    private int slot = -1;

    /** @param command the server command without the slash, e.g. "miningtree rename". */
    TreeRename(String command) {
        this.command = command;
    }

    TextFieldWidget createField(TextRenderer tr, int x, int w) {
        field = new TextFieldWidget(tr, x, 0, w, 13, Text.literal("Name"));
        field.setMaxLength(MAX_LENGTH);
        field.setPlaceholder(Text.literal("Name, Enter to save").withColor(0xFF7C7488));
        field.visible = false;
        field.active = false;
        return field;
    }

    TextFieldWidget field() { return field; }

    boolean active() { return slot >= 0 && field != null; }

    /** Opens the box at {@code y} for {@code slot} (0-based), holding the current custom name. */
    void start(int slot, int y, String current) {
        if (field == null) return;
        this.slot = slot;
        field.setY(y);
        field.setText(current == null ? "" : current);
        field.visible = true;
        field.active = true;
        field.setFocused(true);
        field.setCursorToEnd(false);
        field.setSelectionStart(0);
    }

    boolean over(double mx, double my) {
        return active() && field.isMouseOver(mx, my);
    }

    void commit() {
        if (!active()) return;
        String name = field.getText().strip();
        int n = slot + 1;
        cancel();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendChatCommand(command + " " + n + (name.isEmpty() ? "" : " " + name));
        }
    }

    void cancel() {
        slot = -1;
        if (field == null) return;
        field.setFocused(false);
        field.visible = false;
        field.active = false;
    }
}
