package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.energycalc.AncientItemStats;
import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side {@code /energycalc} — the energy economy of everything you're
 * currently wearing and holding, in one table, live.
 *
 * <p>Every number here is read off the items themselves (their synced
 * {@code custom_data} PDC — see {@link AncientItemStats}), so the screen costs the
 * server nothing and updates the instant an item changes.
 *
 * <h2>The three blank columns</h2>
 * "Total to max", the pickaxe prestige energy ladder and the prestige ore
 * requirements are <b>not</b> derivable client-side: they come from
 * {@code upgrade-gear} / {@code upgrade-pick} coefficients, {@code pvp.gear-max-level}
 * and {@code pickaxe-prestige.*} in the server config, all of which get re-tuned
 * (the prestige energy costs changed as recently as today). Hardcoding a ladder
 * would ship numbers that are already wrong, so those rows read "—" and the screen
 * says why. They light up as soon as the server supplies:
 * <ul>
 *   <li><b>Total energy to max</b> — either a per-item PDC long
 *       {@code prisonscore:prisons_energy_to_max} (no protocol change, already read
 *       by {@link AncientItemStats}), or a packet field per tier.</li>
 *   <li><b>Prestige ladder</b> — a new {@code prisonsmod:v1} S2C packet carrying, per
 *       prestige step: {@code varint prestige; varlong energyCost; varint oreCount;
 *       varint+string oreMaterial}, plus the player's current energy tax rate.</li>
 * </ul>
 * Until then the screen shows what it can and is honest about the rest.
 */
public final class EnergyCalcScreen extends Screen {

    private static final int PADDING = 10;
    private static final int HEADER_H = 18;
    private static final int ROW_H = 16;
    private static final int PANEL_W = 420;

    private static final int MISSING = 0xFF6F7283;

    private final @Nullable Screen parent;

    public EnergyCalcScreen(@Nullable Screen parent) {
        super(Text.literal("Energy Calculator"));
        this.parent = parent;
    }

    /** Open on the next client tick (safe from inside a command dispatch). */
    public static void openNow(@Nullable Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new EnergyCalcScreen(parent)));
    }

    @Override
    protected void init() {
        addDrawableChild(new GlassButton(width / 2 - 50, height - PADDING - 24, 100, 20,
                Text.literal("Done"), this::close).primary());
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.menuBackdrop(ctx, this.width, this.height);

        List<Entry> entries = collect();

        int panelW = Math.min(PANEL_W, this.width - 2 * PADDING);
        int panelX = (this.width - panelW) / 2;
        int noteLines = 3;
        int panelH = HEADER_H + 6 + ROW_H * Math.max(entries.size(), 1) + 10
                + 12 + noteLines * 10 + 8;
        int panelY = Math.max(PADDING + 26, (this.height - panelH) / 2 - 10);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Energy Calculator"),
                this.width / 2, PADDING + 2, GlassTheme.ACCENT_SOFT);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("live from your held + worn Ancient gear"),
                this.width / 2, PADDING + 14, GlassTheme.textDim());

        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        // Column geometry — right-aligned numbers, left-aligned item names.
        int left = panelX + PADDING;
        int right = panelX + panelW - PADDING;
        int colW = (right - left) / 5;
        int lvlRight    = left + colW * 2;
        int nextRight   = left + colW * 3;
        int bankedRight = left + colW * 4;
        int maxRight    = right;

        int y = panelY + 5;
        ctx.drawText(textRenderer, Text.literal("Item"), left, y, GlassTheme.textDim(), false);
        drawRight(ctx, "Lvl", lvlRight, y, GlassTheme.textDim());
        drawRight(ctx, "Next level", nextRight, y, GlassTheme.textDim());
        drawRight(ctx, "Banked", bankedRight, y, GlassTheme.textDim());
        drawRight(ctx, "To max", maxRight, y, GlassTheme.textDim());
        y += textRenderer.fontHeight + 3;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 4;

        if (entries.isEmpty()) {
            ctx.drawText(textRenderer,
                    Text.literal("No Ancient gear held or worn."),
                    left, y + 3, GlassTheme.textDim(), false);
        } else {
            int i = 0;
            for (Entry e : entries) {
                if ((i & 1) == 1) ctx.fill(panelX + 4, y, panelX + panelW - 4, y + ROW_H, 0x0AFFFFFF);
                int textY = y + (ROW_H - textRenderer.fontHeight) / 2;

                AncientItemStats s = e.stats;
                String label = e.slotLabel + ": " + trim(s.displayName(), lvlRight - left - 8);
                ctx.drawText(textRenderer, Text.literal(label), left, textY, GlassTheme.text(), false);

                String lvl = s.pickaxe() && s.prestige() > 0
                        ? s.level() + " P" + s.prestige()
                        : Integer.toString(s.level());
                drawRight(ctx, lvl, lvlRight, textY, GlassTheme.text());

                if (s.energyToNextLevel() >= 0) {
                    drawRight(ctx, compact(s.energyToNextLevel()), nextRight, textY, GlassTheme.VALUE);
                } else {
                    drawRight(ctx, "—", nextRight, textY, MISSING);
                }

                long shortfall = s.energyShortfall();
                int bankedColor = shortfall == 0 ? GlassTheme.OK : GlassTheme.text();
                drawRight(ctx, compact(s.storedEnergy()), bankedRight, textY, bankedColor);

                if (s.energyToMax() >= 0) {
                    drawRight(ctx, compact(s.energyToMax()), maxRight, textY, GlassTheme.VALUE);
                } else {
                    drawRight(ctx, "—", maxRight, textY, MISSING);
                }

                y += ROW_H;
                i++;
            }
        }

        // Footnote: exactly what is missing and why, so nobody reads a blank as a bug.
        y += 8;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 5;
        ctx.drawText(textRenderer, Text.literal("Not sent to the client yet:"),
                left, y, GlassTheme.textDim(), false);
        y += 10;
        ctx.drawText(textRenderer, Text.literal("total energy to max · prestige energy ladder · prestige ore counts"),
                left, y, MISSING, false);
        y += 10;
        ctx.drawText(textRenderer, Text.literal("Run /energycalc <targetLevel> for the server's own calculation."),
                left, y, MISSING, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    /** Held item, offhand, and the four worn pieces — whichever are Ancient. */
    private List<Entry> collect() {
        List<Entry> out = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return out;
        PlayerEntity player = mc.player;

        add(out, player, EquipmentSlot.MAINHAND, "Held");
        add(out, player, EquipmentSlot.OFFHAND, "Offhand");
        add(out, player, EquipmentSlot.HEAD, "Helmet");
        add(out, player, EquipmentSlot.CHEST, "Chestplate");
        add(out, player, EquipmentSlot.LEGS, "Leggings");
        add(out, player, EquipmentSlot.FEET, "Boots");
        return out;
    }

    private static void add(List<Entry> out, PlayerEntity player, EquipmentSlot slot, String label) {
        ItemStack stack = player.getEquippedStack(slot);
        AncientItemStats stats = AncientItemStats.of(stack);
        if (stats != null) out.add(new Entry(label, stats));
    }

    private void drawRight(DrawContext ctx, String text, int rightX, int y, int color) {
        ctx.drawText(textRenderer, Text.literal(text), rightX - textRenderer.getWidth(text), y, color, false);
    }

    private String trim(String s, int maxWidth) {
        return textRenderer.trimToWidth(s, Math.max(0, maxWidth));
    }

    /** 1_250_000 → "1.25M". Energy numbers get very large very fast. */
    private static String compact(long v) {
        if (v < 0) return "—";
        if (v < 1_000L) return Long.toString(v);
        if (v < 1_000_000L) return String.format(Locale.US, "%.1fK", v / 1_000.0);
        if (v < 1_000_000_000L) return String.format(Locale.US, "%.2fM", v / 1_000_000.0);
        return String.format(Locale.US, "%.2fB", v / 1_000_000_000.0);
    }

    private record Entry(String slotLabel, AncientItemStats stats) {}
}
