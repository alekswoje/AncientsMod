package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.client.pv.PvPreset;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * Preset picker — opened from a {@link PvAffinityPickerScreen}'s "Presets"
 * button. Lists the 4 available presets as tiles. Click previews the layout
 * in chat; shift-click applies the preset (server overwrites PV 1-6
 * affinities). After apply, server pushes a fresh bundle and we route back
 * to the overview.
 */
public final class PvPresetsScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int PANEL_H = 230;
    private static final int TITLE_BAR_H = 24;
    private static final int FOOTER_H = 28;
    private static final int TILE_W = 356;
    private static final int TILE_H = 36;
    private static final int TILE_GAP = 6;

    private final int returnVault;
    private PvBundlePayload bundle;
    private ButtonWidget backButton;

    public PvPresetsScreen(int returnVault, PvBundlePayload bundle) {
        super(Text.literal("PV Affinity Presets"));
        this.returnVault = returnVault;
        this.bundle = bundle;
    }

    public void onBundleUpdated(PvBundlePayload payload) {
        this.bundle = payload;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnY = panelY + PANEL_H - FOOTER_H + 4;

        backButton = ButtonWidget.builder(Text.literal("Back to affinities"),
                        b -> PvClient.openAffinityPickerFromPresets(returnVault))
                .dimensions(panelX + PANEL_W - 12 - 140, btnY, 140, 20)
                .build();
        this.addDrawableChild(backButton);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        // Hit-test tiles BEFORE delegating so the click reliably routes here
        // even if some focused widget would otherwise consume it.
        if (click.button() == 0) {
            int panelX = (this.width - PANEL_W) / 2;
            int panelY = (this.height - PANEL_H) / 2;
            int tileX = panelX + (PANEL_W - TILE_W) / 2;
            int tileY = panelY + TITLE_BAR_H + 6;

            double mx = click.x();
            double my = click.y();
            PvPreset[] all = PvPreset.values();
            for (int i = 0; i < all.length; i++) {
                int ty = tileY + i * (TILE_H + TILE_GAP);
                if (mx < tileX || mx >= tileX + TILE_W) continue;
                if (my < ty || my >= ty + TILE_H) continue;
                NetworkHandler.sendPvApplyPreset(all[i].storageKey());
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, 0xFF555555);
        ctx.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);

        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        ctx.drawText(this.textRenderer, Text.literal("§eAffinity Presets"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        String hint = "§7Click a preset to apply · overwrites PV 1-6";
        int hintW = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + PANEL_W - hintW - 10, panelY + 8, 0xFFAAAAAA, false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int tileX = panelX + (PANEL_W - TILE_W) / 2;
        int tileY = panelY + TITLE_BAR_H + 6;

        PvPreset[] all = PvPreset.values();
        for (int i = 0; i < all.length; i++) {
            int ty = tileY + i * (TILE_H + TILE_GAP);
            boolean hover = mouseX >= tileX && mouseX < tileX + TILE_W
                    && mouseY >= ty && mouseY < ty + TILE_H;
            renderTile(ctx, all[i], tileX, ty, hover);
        }
    }

    private void renderTile(DrawContext ctx, PvPreset preset, int x, int y, boolean hover) {
        int bg = hover ? 0xFF2A2A2A : 0xFF1E1E1E;
        int border = hover ? 0xFFFFCC33 : 0xFF444444;

        ctx.fill(x, y, x + TILE_W, y + TILE_H, bg);
        ctx.fill(x, y, x + TILE_W, y + 1, border);
        ctx.fill(x, y + TILE_H - 1, x + TILE_W, y + TILE_H, border);
        ctx.fill(x, y, x + 1, y + TILE_H, border);
        ctx.fill(x + TILE_W - 1, y, x + TILE_W, y + TILE_H, border);

        ItemStack icon = new ItemStack(preset.icon());
        int iconY = y + (TILE_H - 16) / 2;
        ctx.drawItem(icon, x + 6, iconY);

        ctx.drawText(this.textRenderer, Text.literal("§6" + preset.displayName()),
                x + 28, y + 6, 0xFFFFCC33, false);
        ctx.drawText(this.textRenderer, Text.literal("§7" + preset.description()),
                x + 28, y + 18, 0xFFAAAAAA, false);
    }

    @Override
    public void close() {
        PvClient.onPickerClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
