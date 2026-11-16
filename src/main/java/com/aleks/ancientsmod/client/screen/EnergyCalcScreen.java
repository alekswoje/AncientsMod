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
import net.minecraft.text.OrderedText;
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
 *
 * <h2>Layout</h2>
 * One flat Hearth glass panel: ember title with the tab's description as muted meta, a bronze
 * rule, the tab row, the active table (column labels, rule, transparent rows, a wrapped note),
 * then a footer rule with Done on the right. Numbers are Flame, "maxed"/banked-enough are Moss.
 */
public final class EnergyCalcScreen extends Screen {

    private static final int PADDING = 10;
    private static final int ROW_H = 16;
    private static final int PANEL_W = 460;
    private static final int HEADER_H = 24;
    private static final int TAB_H = 16;
    private static final int FOOTER_H = 26;

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

        // Tabs sit left-aligned under the header rule; the active one is the ember (selected) fill.
        int tabW = Math.min(108, (panelW() - 2 * PADDING - 8) / 3);
        int tabY = panelY() + HEADER_H + 6;
        int tabX = panelX() + PADDING;

        addDrawableChild(tabButton(tabX, tabY, tabW, "Your Gear", Tab.YOUR_GEAR));
        addDrawableChild(tabButton(tabX + tabW + 4, tabY, tabW, "Gear Tiers", Tab.GEAR_TIERS));
        addDrawableChild(tabButton(tabX + (tabW + 4) * 2, tabY, tabW, "Pickaxe Prestige", Tab.PICKAXE_PRESTIGE));

        addDrawableChild(new GlassButton(panelX() + panelW() - PADDING - 52, panelY() + panelH() - 20, 52, 14,
                Text.translatable("gui.done"), this::close).primary());
    }

    // ── Panel geometry: one panel, full height, so tables loading late never move the buttons ──

    private int panelW() { return Math.min(PANEL_W, this.width - 2 * PADDING); }
    private int panelX() { return (this.width - panelW()) / 2; }
    private int panelY() { return PADDING; }
    private int panelH() { return this.height - 2 * PADDING; }

    private GlassButton tabButton(int x, int y, int w, String label, Tab target) {
        GlassButton b = new GlassButton(x, y, w, TAB_H, Text.literal(label), () -> {
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

        int px = panelX(), py = panelY(), pw = panelW(), ph = panelH();
        GlassRender.panel(ctx, px, py, pw, ph);

        // Header: ember title, the tab's description as muted meta, bronze rule.
        String title = "Energy Calculator";
        ctx.drawText(textRenderer, Text.literal(title), px + PADDING, py + 10, GlassTheme.ACCENT, true);
        int metaX = px + PADDING + textRenderer.getWidth(title) + 6;
        ctx.drawText(textRenderer, Text.literal(trim(subtitle(), px + pw - PADDING - metaX)),
                metaX, py + 10, GlassTheme.textMuted(), false);
        GlassRender.rule(ctx, px + PADDING, px + pw - PADDING, py + HEADER_H);

        // Footer rule; Done is a drawable child on the right.
        GlassRender.rule(ctx, px + 1, px + pw - 1, py + ph - FOOTER_H);

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

        int left = panelX() + PADDING;
        int right = panelX() + panelW() - PADDING;
        int colW = (right - left) / 5;
        int lvlRight = left + colW * 2;
        int nextRight = left + colW * 3;
        int bankedRight = left + colW * 4;
        int maxRight = right;

        int y = bodyTop();
        ctx.drawText(textRenderer, Text.literal("Item"), left, y, GlassTheme.textMuted(), false);
        drawRight(ctx, "Lvl", lvlRight, y, GlassTheme.textMuted());
        drawRight(ctx, "Next level", nextRight, y, GlassTheme.textMuted());
        drawRight(ctx, "Banked", bankedRight, y, GlassTheme.textMuted());
        drawRight(ctx, "To max", maxRight, y, GlassTheme.textMuted());
        y += textRenderer.fontHeight + 3;
        GlassRender.rule(ctx, left, right, y);
        y += 4;

        if (entries.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("No Ancient gear held or worn."),
                    left, y + 3, GlassTheme.textMuted(), false);
            y += ROW_H;
        } else {
            for (Entry e : entries) {
                int textY = y + (ROW_H - textRenderer.fontHeight) / 2;

                AncientItemStats s = e.stats;
                String label = e.slotLabel + ": " + trim(s.displayName(), lvlRight - left - 8);
                ctx.drawText(textRenderer, Text.literal(label), left, textY, GlassTheme.text(), false);

                // The item stores 1, 5, 10 or 11; players know those as P1..P4.
                String lvl = s.pickaxe() && s.prestige() > 0
                        ? s.level() + " P" + EnergyReferenceState.get().shownPrestige(s.prestige())
                        : Integer.toString(s.level());
                drawRight(ctx, lvl, lvlRight, textY, GlassTheme.text());

                if (s.energyToNextLevel() >= 0) {
                    drawRight(ctx, compact(s.energyToNextLevel()), nextRight, textY, GlassTheme.VALUE);
                } else {
                    drawRight(ctx, "-", nextRight, textY, GlassTheme.textMuted());
                }

                long shortfall = s.energyShortfall();
                drawRight(ctx, compact(s.storedEnergy()), bankedRight, textY,
                        shortfall == 0 ? GlassTheme.OK : GlassTheme.text());

                drawToMax(ctx, e, maxRight, textY);

                y += ROW_H;
            }
        }

        y += 6;
        GlassRender.rule(ctx, left, right, y);
        y += 6;
        if (!EnergyReferenceState.hasData()) {
            drawWrapped(ctx, "Waiting for the server's cost tables…", left, y, right - left, GlassTheme.textMuted());
            return;
        }
        EnergyReferencePayload ref = EnergyReferenceState.get();
        EnergyReferencePayload.Profile profile = ref.profile();
        if (profile == null) {
            int tax = ref.energyTaxPercent();
            drawWrapped(ctx, "Energy tax " + tax + "%: mine " + pctDivisorNote(tax) + " to bank 1.",
                    left, y, right - left, GlassTheme.textDim());
            return;
        }
        y = drawPickaxeBlock(ctx, ref, profile, entries, left, right, y);
        String tax = profile.taxBasisPoints() <= 0 ? "No extract tax."
                : "Extract tax " + profile.taxText() + "%. None in a safe zone.";
        drawWrapped(ctx, tax, left, y, right - left, GlassTheme.textDim());
    }

    /**
     * The held pickaxe in this player's hands: where it stops mining, what an ore pays, and how
     * long the next level and the next prestige take at the rate being mined right now. Every
     * number is the server's, so the mining tree, trims, jewels and boosters are in them.
     * Returns the y below the block (unchanged when no pickaxe is held).
     */
    private int drawPickaxeBlock(DrawContext ctx, EnergyReferencePayload ref, EnergyReferencePayload.Profile profile,
                                 List<Entry> entries, int left, int right, int y) {
        Entry held = null;
        for (Entry e : entries) {
            if (e.stats.pickaxe() && "Held".equals(e.slotLabel)) { held = e; break; }
        }
        if (held == null || !profile.holdingPickaxe()) return y;
        AncientItemStats s = held.stats;
        int lineH = textRenderer.fontHeight + 3;
        int half = (right - left) / 2;

        // Two pairs a line: with six pieces listed above there is little height left at GUI scale 4.
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"Capacity", compact(profile.heldCapacity())});
        if (profile.energyMultiplier() > 0) {
            pairs.add(new String[] {"Mining energy", String.format(Locale.US, "%.2fx", profile.energyMultiplier())});
        }
        if (profile.energyPerHour() <= 0) {
            pairs.add(new String[] {"Rate", "mine to measure"});
        } else {
            pairs.add(new String[] {"Rate", compact(profile.energyPerHour()) + " an hour"});
            long shortfall = s.energyShortfall();
            if (shortfall > 0) pairs.add(new String[] {"Next level", time(profile.secondsToGain(shortfall))});
        }
        for (int i = 0; i < pairs.size(); i++) {
            String[] pair = pairs.get(i);
            boolean measured = !"mine to measure".equals(pair[1]);
            drawPair(ctx, pair[0], pair[1], measured ? GlassTheme.VALUE : GlassTheme.textMuted(),
                    left + (i % 2) * half, y);
            if (i % 2 == 1 || i == pairs.size() - 1) y += lineH;
        }

        EnergyReferencePayload.PrestigeStep next = ref.nextStep(EnergyReferenceState.pickTier(held.tierLabel), s.prestige());
        if (next != null) {
            long need = Math.max(0L, next.energyCost() - s.storedEnergy());
            StringBuilder v = new StringBuilder(need == 0 ? "energy ready" : compact(need) + " to go");
            long fills = profile.fillsFor(need);
            if (fills > 1) v.append(", ").append(fills).append(" fills");
            long seconds = profile.secondsToGain(need);
            if (need > 0 && seconds > 0) v.append(", ").append(time(seconds));
            drawPair(ctx, "Prestige " + next.prestige(), v.toString(),
                    need == 0 ? GlassTheme.OK : GlassTheme.text(), left, y);
            y += lineH;
        }
        return y + 2;
    }

    /** A muted label with its value 78 px along: wide enough for "Mining energy". */
    private void drawPair(DrawContext ctx, String label, String value, int valueColor, int x, int y) {
        ctx.drawText(textRenderer, Text.literal(label), x, y, GlassTheme.textMuted(), false);
        ctx.drawText(textRenderer, Text.literal(value), x + 78, y, valueColor, false);
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
                EnergyReferencePayload.PrestigeStep step = EnergyReferenceState.get().nextStep(tier, s.prestige());
                if (step != null) {
                    drawRight(ctx, "P" + step.prestige() + " " + compact(step.energyCost()),
                            rightX, textY, GlassTheme.VALUE);
                    return;
                }
                drawRight(ctx, "max P", rightX, textY, GlassTheme.OK);
                return;
            }
            drawRight(ctx, "-", rightX, textY, GlassTheme.textMuted());
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
        drawRight(ctx, s.energyToMax() >= 0 ? compact(s.energyToMax()) : "-", rightX, textY,
                s.energyToMax() >= 0 ? GlassTheme.VALUE : GlassTheme.textMuted());
    }

    // ── Tab 2: gear tiers ───────────────────────────────────────────────────

    private void renderGearTiers(DrawContext ctx) {
        List<EnergyReferencePayload.GearTier> tiers = EnergyReferenceState.get().gearTiers();

        int left = panelX() + PADDING;
        int right = panelX() + panelW() - PADDING;
        int colW = (right - left) / 4;
        int maxLvlRight = left + colW * 2;
        int totalRight = left + colW * 3;
        int preTaxRight = right;

        int y = bodyTop();
        ctx.drawText(textRenderer, Text.literal("Tier"), left, y, GlassTheme.textMuted(), false);
        drawRight(ctx, "Max lvl", maxLvlRight, y, GlassTheme.textMuted());
        drawRight(ctx, "Energy to max", totalRight, y, GlassTheme.textMuted());
        drawRight(ctx, "Mined (pre-tax)", preTaxRight, y, GlassTheme.textMuted());
        y += textRenderer.fontHeight + 3;
        GlassRender.rule(ctx, left, right, y);
        y += 4;

        if (tiers.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("Waiting for the server's cost tables…"),
                    left, y + 3, GlassTheme.textMuted(), false);
            y += ROW_H;
        } else {
            double tax = EnergyReferenceState.get().taxRate();
            for (EnergyReferencePayload.GearTier t : tiers) {
                int textY = y + (ROW_H - textRenderer.fontHeight) / 2;

                ctx.drawText(textRenderer, Text.literal(t.label()), left, textY, GlassTheme.text(), false);
                drawRight(ctx, Integer.toString(t.maxLevel()), maxLvlRight, textY, GlassTheme.text());
                drawRight(ctx, compact(t.totalToMax()), totalRight, textY, GlassTheme.VALUE);
                drawRight(ctx, compact(preTax(t.totalToMax(), tax)), preTaxRight, textY, GlassTheme.textDim());

                y += ROW_H;
            }
        }

        y += 6;
        GlassRender.rule(ctx, left, right, y);
        y += 6;
        drawWrapped(ctx, "Per piece. Helmet, chestplate, leggings, boots, sword and axe of a tier all cost the same.",
                left, y, right - left, GlassTheme.textDim());
    }

    // ── Tab 3: pickaxe prestige ─────────────────────────────────────────────

    private void renderPickaxePrestige(DrawContext ctx) {
        List<EnergyReferencePayload.PickTier> tiers = EnergyReferenceState.get().pickTiers();

        int left = panelX() + PADDING;
        int right = panelX() + panelW() - PADDING;

        if (tiers.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("Waiting for the server's cost tables…"),
                    left, bodyTop(), GlassTheme.textMuted(), false);
            return;
        }

        if (!pickTierPinned) {
            pickTierIndex = defaultPickTierIndex(tiers);
            pickTierPinned = true;
        }
        int idx = Math.floorMod(pickTierIndex, tiers.size());
        EnergyReferencePayload.PickTier tier = tiers.get(idx);

        int colW = (right - left) / 4;
        int energyRight = left + colW * 2;
        int oreRight = right;

        int y = bodyTop();
        // Candle: this label is clickable (cycles the tier).
        ctx.drawText(textRenderer, Text.literal(tier.label() + " pickaxe  ◂ ▸"),
                left, y, GlassTheme.ACCENT_SOFT, false);
        drawRight(ctx, "Energy", energyRight, y, GlassTheme.textMuted());
        drawRight(ctx, "Blocks required", oreRight, y, GlassTheme.textMuted());
        y += textRenderer.fontHeight + 3;
        GlassRender.rule(ctx, left, right, y);
        y += 4;

        // The pickaxe in hand, when it is of the tier on show: the rungs it already stands on read as done.
        EnergyReferencePayload ref = EnergyReferenceState.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack heldStack = mc == null || mc.player == null ? null : mc.player.getEquippedStack(EquipmentSlot.MAINHAND);
        AncientItemStats held = AncientItemStats.of(heldStack);
        boolean heldIsThisTier = held != null && held.pickaxe() && tier.label().equalsIgnoreCase(tierLabelOf(heldStack));

        for (int i = 0; i < tier.ladder().size(); i++) {
            EnergyReferencePayload.PrestigeStep step = tier.ladder().get(i);
            int textY = y + (ROW_H - textRenderer.fontHeight) / 2;
            boolean done = heldIsThisTier && ref.reached(i, held.prestige());

            ctx.drawText(textRenderer, Text.literal("P" + step.prestige()), left, textY,
                    done ? GlassTheme.textMuted() : GlassTheme.text(), false);
            if (done) {
                drawRight(ctx, "done", energyRight, textY, GlassTheme.OK);
            } else {
                drawRight(ctx, compact(step.energyCost()), energyRight, textY, GlassTheme.VALUE);
            }
            String ore = step.oreCount() <= 0 ? "-"
                    : compact(step.oreCount()) + " " + step.oreLabel();
            drawRight(ctx, ore, oreRight, textY, done ? GlassTheme.textMuted() : GlassTheme.text());

            y += ROW_H;
        }

        y += 6;
        GlassRender.rule(ctx, left, right, y);
        y += 6;
        ctx.drawText(textRenderer, Text.literal("Full ladder: "), left, y, GlassTheme.text(), false);
        int lx = left + textRenderer.getWidth("Full ladder: ");
        String total = compact(tier.totalPrestigeEnergy());
        ctx.drawText(textRenderer, Text.literal(total), lx, y, GlassTheme.VALUE, false);
        ctx.drawText(textRenderer, Text.literal(" energy across " + tier.ladder().size() + " steps."),
                lx + textRenderer.getWidth(total), y, GlassTheme.text(), false);
        y += 12;
        drawWrapped(ctx, "Click the tier name or press ←/→ to switch tier. Blocks are the pickaxe's total, not per step. Premium ores count as more than one block.",
                left, y, right - left, GlassTheme.textDim());
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
            int pw = panelW();
            int px = panelX();
            int headerY = bodyTop();
            if (click.y() >= headerY - 2 && click.y() <= headerY + textRenderer.fontHeight + 2
                    && click.x() >= px + PADDING && click.x() <= px + pw - PADDING) {
                pickTierIndex += (click.button() == 1 ? -1 : 1);
                pickTierPinned = true;
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    /** Top of the active tab's table: below the header rule and the tab row. */
    private int bodyTop() { return panelY() + HEADER_H + 6 + TAB_H + 10; }

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

    /** Energy that must be mined to bank {@code net} after the extract tax (a 0..1 rate). */
    private static long preTax(long net, double taxRate) {
        if (taxRate <= 0 || taxRate >= 1) return net;
        return Math.round(net / (1.0 - taxRate));
    }

    /** The two largest units of a duration: "45s", "12m", "3h 20m", "2d 19h". "-" when unknown. */
    static String time(long seconds) {
        if (seconds < 0) return "-";
        if (seconds < 60) return Math.max(1, seconds) + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h" + (minutes % 60 > 0 ? " " + (minutes % 60) + "m" : "");
        long days = hours / 24;
        return days + "d" + (hours % 24 > 0 ? " " + (hours % 24) + "h" : "");
    }

    private static String pctDivisorNote(int taxPercent) {
        if (taxPercent <= 0 || taxPercent >= 100) return "1";
        return String.format(Locale.US, "%.2f", 1.0 / (1.0 - taxPercent / 100.0));
    }

    /** Body note wrapped to the panel width (the long notes overflowed on one line). */
    private void drawWrapped(DrawContext ctx, String text, int x, int y, int maxWidth, int color) {
        for (OrderedText line : textRenderer.wrapLines(Text.literal(text), Math.max(20, maxWidth))) {
            ctx.drawText(textRenderer, line, x, y, color, false);
            y += textRenderer.fontHeight + 1;
        }
    }

    private void drawRight(DrawContext ctx, String text, int rightX, int y, int color) {
        ctx.drawText(textRenderer, Text.literal(text), rightX - textRenderer.getWidth(text), y, color, false);
    }

    private String trim(String s, int maxWidth) {
        return textRenderer.trimToWidth(s, Math.max(0, maxWidth));
    }

    /** 1_250_000 → "1.25M". Energy numbers get very large very fast. */
    private static String compact(long v) {
        if (v < 0) return "-";
        if (v < 1_000L) return Long.toString(v);
        if (v < 1_000_000L) return String.format(Locale.US, "%.1fK", v / 1_000.0);
        if (v < 1_000_000_000L) return String.format(Locale.US, "%.2fM", v / 1_000_000.0);
        return String.format(Locale.US, "%.2fB", v / 1_000_000_000.0);
    }

    private record Entry(String slotLabel, AncientItemStats stats, @Nullable String tierLabel) {}
}
