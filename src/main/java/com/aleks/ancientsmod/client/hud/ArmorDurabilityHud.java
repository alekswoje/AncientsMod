package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moveable Armor Durability HUD. Shows the remaining durability of each worn
 * piece (and optionally the held / offhand item) so you can see a piece about to
 * break without opening your inventory.
 *
 * <p>Entirely client-side: durability lives in the synced {@code ItemStack}, so
 * this needs no server packet and no plugin change. Unbreakable / non-damageable
 * items are skipped — there is nothing to count down.
 *
 * <p>Off by default (new HUD clutter); flip it on in Settings → HUDs.
 */
public final class ArmorDurabilityHud extends HudElement {

    public static final ArmorDurabilityHud INSTANCE = new ArmorDurabilityHud();

    /** Comma-separated set of slot keys to render. */
    public static final String KEY_SLOTS = "slots";
    /** Show "78%" instead of "1234 / 1561". */
    public static final String KEY_SHOW_PERCENT = "show_percent";
    /** Hide rows that are still at full durability. */
    public static final String KEY_HIDE_FULL = "hide_full";
    /** Only show a row once it drops below this percent (0 = always show). */
    public static final String KEY_ONLY_BELOW = "only_below";

    public static final List<String> ALL_SLOTS =
            List.of("helmet", "chestplate", "leggings", "boots", "mainhand", "offhand");

    public static final Set<String> DEFAULT_SLOTS =
            new LinkedHashSet<>(List.of("helmet", "chestplate", "leggings", "boots"));

    private static final int MIN_WIDTH = 140;

    private static final int OK_COLOR   = 0xFF8AE08A;
    private static final int WARN_COLOR = 0xFFFFC857;
    private static final int LOW_COLOR  = 0xFFFF6E6E;

    private ArmorDurabilityHud() {}

    @Override public String id() { return "armor_durability"; }
    @Override public String displayName() { return "Armor Durability"; }

    @Override
    public boolean isVisible() {
        if (!FeatureToggles.isArmorDurabilityHudEnabled()) return false;
        if (HudStyle.isAlwaysShown(id())) return true;
        return !computeRows().isEmpty();
    }

    @Override
    public String editorPlaceholder() { return "Armor Durability"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new ArmorDurabilityHudSettingsScreen(parent, this);
    }

    public Set<String> enabledSlots() {
        return HudSettings.getStringSet(id(), KEY_SLOTS, DEFAULT_SLOTS);
    }

    public boolean showPercent() {
        return HudSettings.getBoolean(id(), KEY_SHOW_PERCENT, false);
    }

    public boolean hideFull() {
        return HudSettings.getBoolean(id(), KEY_HIDE_FULL, false);
    }

    /** 0 = no threshold; otherwise only rows at or below this percent render. */
    public int onlyBelowPercent() {
        return Math.max(0, Math.min(100, HudSettings.getInt(id(), KEY_ONLY_BELOW, 0)));
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int colGap = HudStyle.columnGap(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("DURABILITY");
        for (Row r : computeRows()) {
            int rowW = fr.getWidth(r.label) + colGap + fr.getWidth(r.rightText);
            if (rowW > widest) widest = rowW;
        }
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int contentRows = Math.max(computeRows().size(), 1);
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * contentRows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 220; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<Row> rows = computeRows();
        int w = width();
        int h = height();

        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "DURABILITY");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());

        for (Row r : rows) {
            ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);

            int textX = padX + stripW + stripGap;
            int textY = rowY + 2;

            int rightW = fr.getWidth(r.rightText);
            ctx.drawText(fr, Text.literal(r.rightText), w - padX - rightW, textY, r.accent, true);
            ctx.drawText(fr, Text.literal(r.label), textX, textY, HudStyle.TIME_COLOR, true);

            rowY += rowH;
        }
    }

    private List<Row> computeRows() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return List.of();
        PlayerEntity player = mc.player;

        Set<String> enabled = enabledSlots();
        if (enabled.isEmpty()) return List.of();

        boolean percent = showPercent();
        boolean hideFull = hideFull();
        int threshold = onlyBelowPercent();

        List<Row> out = new ArrayList<>();
        for (String key : ALL_SLOTS) {
            if (!enabled.contains(key)) continue;
            ItemStack stack = player.getEquippedStack(slotFor(key));
            if (stack == null || stack.isEmpty()) continue;
            if (!stack.isDamageable()) continue;    // unbreakable / no durability bar

            int max = stack.getMaxDamage();
            if (max <= 0) continue;
            int remaining = Math.max(0, max - stack.getDamage());
            int pct = (int) Math.floor(remaining * 100.0 / max);

            if (hideFull && remaining >= max) continue;
            if (threshold > 0 && pct > threshold) continue;

            String right = percent
                    ? pct + "%"
                    : String.format(Locale.US, "%d / %d", remaining, max);
            out.add(new Row(displayNameForSlot(key), right, colorFor(pct)));
        }
        return out;
    }

    private static EquipmentSlot slotFor(String key) {
        return switch (key) {
            case "helmet"     -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings"   -> EquipmentSlot.LEGS;
            case "boots"      -> EquipmentSlot.FEET;
            case "offhand"    -> EquipmentSlot.OFFHAND;
            default           -> EquipmentSlot.MAINHAND;
        };
    }

    public static String displayNameForSlot(String key) {
        return switch (key) {
            case "helmet"     -> "Helmet";
            case "chestplate" -> "Chestplate";
            case "leggings"   -> "Leggings";
            case "boots"      -> "Boots";
            case "mainhand"   -> "Held item";
            case "offhand"    -> "Offhand";
            default -> key;
        };
    }

    private static int colorFor(int pct) {
        if (pct <= 15) return LOW_COLOR;
        if (pct <= 40) return WARN_COLOR;
        return OK_COLOR;
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }

    private record Row(String label, String rightText, int accent) {}
}
