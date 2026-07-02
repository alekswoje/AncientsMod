package com.aleks.ancientsmod.client.wiki;

/**
 * A clickable region inside a single tooltip line, resolved from the item's
 * lore by {@link WikiLinkResolver}.
 *
 * <p>{@code startCol}/{@code endCol} are character offsets into the line's
 * <em>plain</em> string (i.e. {@code Text.getString()}, no formatting codes).
 * The renderer turns these into pixel boxes using the font's style-aware width
 * so the underline + hit-box line up with the glyphs exactly, even across bold
 * runs. {@code entryId} keys into {@link WikiRegistry}.
 */
public record WikiLink(int lineIndex, int startCol, int endCol, String entryId) {}
