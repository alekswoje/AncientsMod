package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.CooldownsPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moveable Cooldowns HUD. Renders the player's active command, combat, enchant
 * and pickaxe cooldowns as rows. Per-widget settings let the player mute
 * entire categories.
 */
public final class CooldownsHud extends HudElement {

    public static final CooldownsHud INSTANCE = new CooldownsHud();

    public static final String KEY_CATEGORIES = "categories";
    /** Comma-separated whitelist of individual cooldown keys to show. Empty = show every key in the visible categories. */
    public static final String KEY_VISIBLE = "visible";

    public static final List<String> CATEGORY_KEYS = List.of(
            "commands", "combat", "enchant", "pickaxe"
    );

    public static final Set<String> DEFAULT_CATEGORIES = new LinkedHashSet<>(List.of(
            "commands", "combat", "enchant", "pickaxe"
    ));

    /** Stable keys for every individual cooldown the player can toggle. Matches the wire byte ids. */
    public static final List<String> ALL_KEYS = List.of(
            "fix", "fixall", "eat", "feed", "jet",
            "combat_tag",
            "devour", "last_stand", "enlighten", "painkiller", "prismatic_eff", "berserk", "adrenaline"
    );

    private static final int MIN_WIDTH = 158;

    private CooldownsHud() {}

    @Override public String id() { return "cooldowns"; }
    @Override public String displayName() { return "Cooldowns"; }

    @Override
    public boolean isVisible() {
        if (!FeatureToggles.isCooldownsHudEnabled()) return false;
        if (HudStyle.isAlwaysShown(id())) return true;
        return !computeRows().isEmpty();
    }

    @Override
    public String editorPlaceholder() { return "Cooldowns"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new CooldownsHudSettingsScreen(parent, this);
    }

    public Set<String> enabledCategories() {
        return HudSettings.getStringSet(id(), KEY_CATEGORIES, DEFAULT_CATEGORIES);
    }

    /** Empty set = show every cooldown in the enabled categories. Otherwise = whitelist. */
    public Set<String> visibleCooldowns() {
        return HudSettings.getStringSet(id(), KEY_VISIBLE, new LinkedHashSet<>());
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int colGap = HudStyle.columnGap(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("COOLDOWNS");
        for (Row r : computeRows()) {
            int labelW = fr.getWidth(r.label);
            int timeW = fr.getWidth(formatDuration(r.secondsRemaining));
            int rowW = labelW + colGap + timeW;
            if (rowW > widest) widest = rowW;
        }
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int rows = computeRows().size();
        int contentRows = Math.max(rows, 1);
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * contentRows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 150; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<Row> rows = computeRows();
        int w = width();
        int h = height();

        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "COOLDOWNS");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());

        for (Row r : rows) {
            ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);

            int textX = padX + stripW + stripGap;
            int textY = rowY + 2;

            String time = formatDuration(r.secondsRemaining);
            int timeW = fr.getWidth(time);
            int timeX = w - padX - timeW;
            ctx.drawText(fr, Text.literal(time), timeX, textY, HudStyle.TIME_COLOR, true);

            ctx.drawText(fr, Text.literal(r.label), textX, textY,
                    (r.accent & 0x00FFFFFF) | 0xFF000000, true);

            rowY += rowH;
        }
    }

    private List<Row> computeRows() {
        Set<String> enabled = enabledCategories();
        if (enabled.isEmpty()) return List.of();
        Set<String> whitelist = visibleCooldowns();

        List<Row> out = new ArrayList<>();
        for (CooldownsPayload.Entry e : CooldownState.entries()) {
            String catKey = categoryKeyForId(e.category());
            if (catKey == null || !enabled.contains(catKey)) continue;
            String key = entryKeyFor(e);
            if (!whitelist.isEmpty() && key != null && !whitelist.contains(key)) continue;
            int seconds = CooldownState.liveSecondsRemaining(e);
            if (seconds <= 0) continue;
            out.add(new Row(displayNameFor(e), seconds, accentFor(e)));
        }
        return out;
    }

    /** Maps a wire entry to its stable per-cooldown key used in {@link #ALL_KEYS}. */
    public static String entryKeyFor(CooldownsPayload.Entry e) {
        if (e.category() == Protocol.CD_CAT_COMMANDS) {
            if (e.id() == Protocol.CD_CMD_FIX)    return "fix";
            if (e.id() == Protocol.CD_CMD_FIXALL) return "fixall";
            if (e.id() == Protocol.CD_CMD_EAT)  return "eat";
            if (e.id() == Protocol.CD_CMD_FEED) return "feed";
            if (e.id() == Protocol.CD_CMD_JET)  return "jet";
        }
        if (e.category() == Protocol.CD_CAT_COMBAT) {
            if (e.id() == Protocol.CD_COMBAT_TAG) return "combat_tag";
        }
        if (e.category() == Protocol.CD_CAT_ENCHANT) {
            if (e.id() == Protocol.CD_ENCH_DEVOUR)         return "devour";
            if (e.id() == Protocol.CD_ENCH_LAST_STAND)     return "last_stand";
            if (e.id() == Protocol.CD_ENCH_ENLIGHTEN)      return "enlighten";
            if (e.id() == Protocol.CD_ENCH_PAINKILLER)     return "painkiller";
            if (e.id() == Protocol.CD_ENCH_PRISMATIC_EFF)  return "prismatic_eff";
            if (e.id() == Protocol.CD_ENCH_BERSERK)        return "berserk";
            if (e.id() == Protocol.CD_ENCH_ADRENALINE)     return "adrenaline";
        }
        return null;
    }

    public static String displayNameForKey(String key) {
        return switch (key) {
            case "fix"            -> "/fix";
            case "fixall"         -> "/fixall";
            case "eat"            -> "/eat";
            case "feed"           -> "/feed";
            case "jet"            -> "/jet";
            case "combat_tag"     -> "Combat tag";
            case "devour"         -> "Devour";
            case "last_stand"     -> "Last Stand";
            case "enlighten"      -> "Enlighten";
            case "painkiller"     -> "Painkiller";
            case "prismatic_eff"  -> "Prismatic Eff.";
            case "berserk"        -> "Berserk";
            case "adrenaline"     -> "Adrenaline";
            default -> key;
        };
    }

    private static String categoryKeyForId(byte cat) {
        if (cat == Protocol.CD_CAT_COMMANDS) return "commands";
        if (cat == Protocol.CD_CAT_COMBAT)   return "combat";
        if (cat == Protocol.CD_CAT_ENCHANT)  return "enchant";
        if (cat == Protocol.CD_CAT_PICKAXE)  return "pickaxe";
        return null;
    }

    public static String displayNameForCategory(String key) {
        return switch (key) {
            case "commands" -> "Command cooldowns";
            case "combat"   -> "Combat tag cooldown";
            case "enchant"  -> "Enchant proc cooldowns";
            case "pickaxe"  -> "Pickaxe ability cooldowns";
            default -> key;
        };
    }

    private static String displayNameFor(CooldownsPayload.Entry e) {
        String key = entryKeyFor(e);
        return key == null ? "Cooldown" : displayNameForKey(key);
    }

    private static int accentFor(CooldownsPayload.Entry e) {
        if (e.category() == Protocol.CD_CAT_COMMANDS) return 0xFF8AC2FF;
        if (e.category() == Protocol.CD_CAT_COMBAT)   return 0xFFFF8A66;
        if (e.category() == Protocol.CD_CAT_ENCHANT)  return 0xFFD37CFF;
        if (e.category() == Protocol.CD_CAT_PICKAXE)  return 0xFFE6B05A;
        return 0xFFFFFFFF;
    }

    private static String formatDuration(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60;
        int s = seconds % 60;
        if (m < 60) return String.format(Locale.US, "%d:%02d", m, s);
        int h = m / 60;
        int rm = m % 60;
        return String.format(Locale.US, "%d:%02d:%02d", h, rm, s);
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }

    private record Row(String label, int secondsRemaining, int accent) {}
}
