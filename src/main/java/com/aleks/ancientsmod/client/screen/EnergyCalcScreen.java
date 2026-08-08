package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.energycalc.AncientItemStats;
import com.aleks.ancientsmod.client.energycalc.EnergyReferenceState;
import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.payload.EnergyReferencePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side {@code /energycalc} — the whole gear and pickaxe energy economy in one place.
 *
 * <h2>Where each number comes from</h2>
 * Two sources, and the distinction matters:
 * <ul>
 *   <li><b>Your gear</b> — read off the items themselves (their synced {@code custom_data}
 *       PDC, see {@link AncientItemStats}). Free, and live the instant an item changes.</li>
 *   <li><b>The reference tables</b> — {@code PKT_ENERGY_REFERENCE}, requested when this
 *       screen opens. The upgrade coefficients, exponents, gear max levels and prestige
 *       ladder all live in server config and get re-tuned, so they are never reconstructed
 *       client-side; a stale hardcoded ladder would be worse than a blank one.</li>
 * </ul>
 * The "To max" column joins the two: the server sends each tier's cumulative cost curve, so
 * subtracting the cumulative at the piece's current level gives what is actually left to pay.
 */
public final class EnergyCalcScreen extends Screen {

    private static final int PADDING = 10;
    private static final int ROW_H = 16;
    private static final int PANEL_W = 460;

    private final @Nullable Screen parent;

    private enum Tab { YOUR_GEAR, GEAR_TIERS, PICKAXE_PRESTIGE }

    private Tab tab = Tab.YOUR_GEAR;
    /** Which pickaxe tier's ladder the prestige tab shows. Defaults to the one you hold. */
    private int pickTierIndex = 0;
    private boolean pickTierPinned = false;

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
        // Ask every time the screen opens: the tables are config-derived and a
        // /configsplit reload between openings must not leave stale numbers up.
        NetworkHandler.sendEnergyReferenceRequest();

        int tabW = 108;
        int tabY = PADDING + 26;
        int totalW = tabW * 3 + 8;
        int tabX = (this.width - totalW) / 2;

        addDrawableChild(tabButton(tabX, tabY, tabW, "Your Gear", Tab.YOUR_GEAR));
        addDrawableChild(tabButton(tabX + tabW + 4, tabY, tabW, "Gear Tiers", Tab.GEAR_TIERS));
        addDrawableChild(tabButton(tabX + (tabW + 4) * 2, tabY, tabW, "Pickaxe Prestige", Tab.PICKAXE_PRESTIGE));

        addDrawableChild(new GlassButton(width / 2 - 50, height - PADDING - 24, 100, 20,
                Text.literal("Done"), this::close).primary());
    }

    private GlassButton tabButton(int x, int y, int w, String label, Tab target) {
        GlassButton b = new GlassButton(x, y, w, 18, Text.literal(label), () -> {
            this.tab = target;
            this.clearAndInit();
        });
        return tab == target ? b.primary() : b;
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.menuBackdrop(ctx, this.width, this.height);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Energy Calculator"),
                this.width / 2, PADDING + 2, GlassTheme.ACCENT_SOFT);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(subtitle()),
                this.width / 2, PADDING + 14, GlassTheme.textDim());

        switch (tab) {
            case YOUR_GEAR -> renderYourGear(ctx);
            case GEAR_TIERS -> renderGearTiers(ctx);
            case PICKAXE_PRESTIGE -> renderPickaxePrestige(ctx);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private String subtitle() {
        return switch (tab) {
            case YOUR_GEAR -> "live from your held + worn Ancient gear";
            case GEAR_TIERS -> "total energy to take a fresh piece to max level";
            case PICKAXE_PRESTIGE -> "energy and blocks per prestige step";
        };
    }

    // ── Tab 1: your gear ────────────────────────────────────────────────────

    private void renderYourGear(DrawContext ctx) {
        List<Entry> entries = collect();

        int panelW = Math.min(PANEL_W, this.width - 2 * PADDING);
        int panelX = (this.width - panelW) / 2;
        int panelH = 24 + ROW_H * Math.max(entries.size(), 1) + 30;
        int panelY = bodyTop();

        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        int left = panelX + PADDING;
        int right = panelX + panelW - PADDING;
        int colW = (right - left) / 5;
        int lvlRight = left + colW * 2;
        int nextRight = left + colW * 3;
        int bankedRight = left + colW * 4;
        int maxRight = right;

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
            ctx.drawText(textRenderer, Text.literal("No Ancient gear held or worn."),
                    left, y + 3, GlassTheme.textDim(), false);
            y += ROW_H;
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
                    drawRight(ctx, "—", nextRight, textY, GlassTheme.textMuted());
                }

                long shortfall = s.energyShortfall();
                drawRight(ctx, compact(s.storedEnergy()), bankedRight, textY,
                        shortfall == 0 ? GlassTheme.OK : GlassTheme.text());

                drawToMax(ctx, e, maxRight, textY);

                y += ROW_H;
                i++;
            }
        }

        y += 8;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 5;
        if (EnergyReferenceState.hasData()) {
            int tax = EnergyReferenceState.get().energyTaxPercent();
            ctx.drawText(textRenderer, Text.literal(
                            "Energy tax " + tax + "% — mine " + pctDivisorNote(tax) + " to bank 1."),
                    left, y, GlassTheme.textDim(), false);
        } else {
            ctx.drawText(textRenderer, Text.literal("Waiting for the server's cost tables…"),
                    left, y, GlassTheme.textMuted(), false);
        }
    }

    /**
     * "To max" for one row. Gear has a real max level, so this is
     * {@code tierTotal - tierCumulative[currentLevel]}. Pickaxes have no max level (their
     * level is enchant-driven and open-ended), so the cell shows the next prestige's energy
     * instead — the number a pickaxe owner is actually saving toward.
     */
    private void drawToMax(DrawContext ctx, Entry e, int rightX, int textY) {
        AncientItemStats s = e.stats;

        if (s.pickaxe()) {
            EnergyReferencePayload.PickTier tier = EnergyReferenceState.pickTier(e.tierLabel);
            if (tier != null) {
                for (EnergyReferencePayload.PrestigeStep step : tier.ladder()) {
                    if (step.prestige() == s.prestige() + 1) {
                        drawRight(ctx, "P" + step.prestige() + " " + compact(step.energyCost()),
                                rightX, textY, GlassTheme.ACCENT_SOFT);
                        return;
                    }
                }
                drawRight(ctx, "max P", rightX, textY, GlassTheme.OK);
                return;
            }
            drawRight(ctx, "—", rightX, textY, GlassTheme.textMuted());
            return;
        }

        EnergyReferencePayload.GearTier tier = EnergyReferenceState.gearTier(e.tierLabel);
        // Two things move a piece's real cap off its tier's: a per-item override (a
        // level-capped quest gift), which is on the item and readable, and bonus enchant
        // slots, which are not. So an override that disagrees with the tier falls back to
        // "—", and "maxed" means "at the tier cap" — a piece carrying bonus slots can still
        // climb past that, and will read maxed a few levels early. Better than a confidently
        // wrong remaining figure, and the alternative needs a new server field.
        boolean capMatchesTier = tier != null
                && (s.maxLevelOverride() < 0 || s.maxLevelOverride() == tier.maxLevel())
                && s.level() < tier.maxLevel();
        if (capMatchesTier) {
            long remaining = tier.remainingFrom(s.level());
            if (remaining >= 0) {
                drawRight(ctx, compact(remaining), rightX, textY, GlassTheme.VALUE);
                return;
            }
        }
        if (tier != null && s.maxLevelOverride() < 0 && s.level() >= tier.maxLevel()) {
            drawRight(ctx, "maxed", rightX, textY, GlassTheme.OK);
            return;
        }
        // Server table not in yet, or this item's cap isn't the tier's.
        drawRight(ctx, s.energyToMax() >= 0 ? compact(s.energyToMax()) : "—", rightX, textY,
                s.energyToMax() >= 0 ? GlassTheme.VALUE : GlassTheme.textMuted());
    }

    // ── Tab 2: gear tiers ───────────────────────────────────────────────────

    private void renderGearTiers(DrawContext ctx) {
        List<EnergyReferencePayload.GearTier> tiers = EnergyReferenceState.get().gearTiers();

        int panelW = Math.min(PANEL_W, this.width - 2 * PADDING);
        int panelX = (this.width - panelW) / 2;
        int panelH = 24 + ROW_H * Math.max(tiers.size(), 1) + 30;
        int panelY = bodyTop();

        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        int left = panelX + PADDING;
        int right = panelX + panelW - PADDING;
        int colW = (right - left) / 4;
        int maxLvlRight = left + colW * 2;
        int totalRight = left + colW * 3;
        int preTaxRight = right;

        int y = panelY + 5;
        ctx.drawText(textRenderer, Text.literal("Tier"), left, y, GlassTheme.textDim(), false);
        drawRight(ctx, "Max lvl", maxLvlRight, y, GlassTheme.textDim());
        drawRight(ctx, "Energy to max", totalRight, y, GlassTheme.textDim());
        drawRight(ctx, "Mined (pre-tax)", preTaxRight, y, GlassTheme.textDim());
        y += textRenderer.fontHeight + 3;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 4;

        if (tiers.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("Waiting for the server's cost tables…"),
                    left, y + 3, GlassTheme.textMuted(), false);
            y += ROW_H;
        } else {
            int tax = EnergyReferenceState.get().energyTaxPercent();
            int i = 0;
            for (EnergyReferencePayload.GearTier t : tiers) {
                if ((i & 1) == 1) ctx.fill(panelX + 4, y, panelX + panelW - 4, y + ROW_H, 0x0AFFFFFF);
                int textY = y + (ROW_H - textRenderer.fontHeight) / 2;

                ctx.drawText(textRenderer, Text.literal(t.label()), left, textY, GlassTheme.text(), false);
                drawRight(ctx, Integer.toString(t.maxLevel()), maxLvlRight, textY, GlassTheme.text());
                drawRight(ctx, compact(t.totalToMax()), totalRight, textY, GlassTheme.VALUE);
                drawRight(ctx, compact(preTax(t.totalToMax(), tax)), preTaxRight, textY, GlassTheme.textDim());

                y += ROW_H;
                i++;
            }
        }

        y += 8;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 5;
        ctx.drawText(textRenderer,
                Text.literal("Per piece. Helmet, chestplate, leggings, boots, sword and axe of a tier all cost the same."),
                left, y, GlassTheme.textDim(), false);
    }

    // ── Tab 3: pickaxe prestige ─────────────────────────────────────────────

    private void renderPickaxePrestige(DrawContext ctx) {
        List<EnergyReferencePayload.PickTier> tiers = EnergyReferenceState.get().pickTiers();

        int panelW = Math.min(PANEL_W, this.width - 2 * PADDING);
        int panelX = (this.width - panelW) / 2;

        if (tiers.isEmpty()) {
            int panelY = bodyTop();
            GlassRender.panel(ctx, panelX, panelY, panelW, 40);
            ctx.drawText(textRenderer, Text.literal("Waiting for the server's cost tables…"),
                    panelX + PADDING, panelY + 14, GlassTheme.textMuted(), false);
            return;
        }

        if (!pickTierPinned) {
            pickTierIndex = defaultPickTierIndex(tiers);
            pickTierPinned = true;
        }
        int idx = Math.floorMod(pickTierIndex, tiers.size());
        EnergyReferencePayload.PickTier tier = tiers.get(idx);

        int panelH = 24 + ROW_H * Math.max(tier.ladder().size(), 1) + 42;
        int panelY = bodyTop();
        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        int left = panelX + PADDING;
        int right = panelX + panelW - PADDING;
        int colW = (right - left) / 4;
        int energyRight = left + colW * 2;
        int oreRight = right;

        int y = panelY + 5;
        ctx.drawText(textRenderer, Text.literal(tier.label() + " pickaxe  ◂ ▸"),
                left, y, GlassTheme.ACCENT_SOFT, false);
        drawRight(ctx, "Energy", energyRight, y, GlassTheme.textDim());
        drawRight(ctx, "Blocks required", oreRight, y, GlassTheme.textDim());
        y += textRenderer.fontHeight + 3;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 4;

        int i = 0;
        for (EnergyReferencePayload.PrestigeStep step : tier.ladder()) {
            if ((i & 1) == 1) ctx.fill(panelX + 4, y, panelX + panelW - 4, y + ROW_H, 0x0AFFFFFF);
            int textY = y + (ROW_H - textRenderer.fontHeight) / 2;

            ctx.drawText(textRenderer, Text.literal("P" + step.prestige()), left, textY,
                    GlassTheme.text(), false);
            drawRight(ctx, compact(step.energyCost()), energyRight, textY, GlassTheme.VALUE);
            String ore = step.oreCount() <= 0 ? "—"
                    : compact(step.oreCount()) + " " + step.oreLabel();
            drawRight(ctx, ore, oreRight, textY, GlassTheme.text());

            y += ROW_H;
            i++;
        }

        y += 8;
        ctx.fill(panelX + 4, y, panelX + panelW - 4, y + 1, GlassTheme.rim());
        y += 5;
        ctx.drawText(textRenderer, Text.literal("Full ladder: "
                        + compact(tier.totalPrestigeEnergy()) + " energy across "
                        + tier.ladder().size() + " steps."),
                left, y, GlassTheme.text(), false);
        y += 10;
        ctx.drawText(textRenderer,
                Text.literal("Click the tier name or press ←/→ to switch tier. Ore counts are weighted; premium ores count as more than one block."),
                left, y, GlassTheme.textDim(), false);
    }

    /** Index of the tier matching the pickaxe in hand, so the tab opens on what you use. */
    private int defaultPickTierIndex(List<EnergyReferencePayload.PickTier> tiers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        String label = tierLabelOf(mc.player.getEquippedStack(EquipmentSlot.MAINHAND));
        if (label != null) {
            for (int i = 0; i < tiers.size(); i++) {
                if (tiers.get(i).label().equalsIgnoreCase(label)) return i;
            }
        }
        return 0;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (tab == Tab.PICKAXE_PRESTIGE) {
            if (input.key() == GLFW.GLFW_KEY_LEFT) { pickTierIndex--; pickTierPinned = true; return true; }
            if (input.key() == GLFW.GLFW_KEY_RIGHT) { pickTierIndex++; pickTierPinned = true; return true; }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        // Clicking the tier header cycles tiers — same affordance as the arrow keys.
        if (tab == Tab.PICKAXE_PRESTIGE) {
            int panelW = Math.min(PANEL_W, this.width - 2 * PADDING);
            int panelX = (this.width - panelW) / 2;
            int headerY = bodyTop() + 5;
            if (click.y() >= headerY - 2 && click.y() <= headerY + textRenderer.fontHeight + 2
                    && click.x() >= panelX + PADDING && click.x() <= panelX + panelW - PADDING) {
                pickTierIndex += (click.button() == 1 ? -1 : 1);
                pickTierPinned = true;
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private int bodyTop() { return PADDING + 26 + 18 + 8; }

    // ── Collection + helpers ────────────────────────────────────────────────

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
        if (stats != null) out.add(new Entry(label, stats, tierLabelOf(stack)));
    }

    /** The server-side tier name for an item's material, or null if it isn't a tiered item. */
    private static @Nullable String tierLabelOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return EnergyReferenceState.tierLabelFor(Registries.ITEM.getId(stack.getItem()).getPath());
    }

    /** Energy that must be mined to bank {@code net} after the rank's energy tax. */
    private static long preTax(long net, int taxPercent) {
        if (taxPercent <= 0 || taxPercent >= 100) return net;
        return Math.round(net / (1.0 - taxPercent / 100.0));
    }

    private static String pctDivisorNote(int taxPercent) {
        if (taxPercent <= 0 || taxPercent >= 100) return "1";
        return String.format(Locale.US, "%.2f", 1.0 / (1.0 - taxPercent / 100.0));
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

    private record Entry(String slotLabel, AncientItemStats stats, @Nullable String tierLabel) {}
}
