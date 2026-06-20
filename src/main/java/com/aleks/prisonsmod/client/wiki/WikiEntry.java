package com.aleks.prisonsmod.client.wiki;

import java.util.List;

/**
 * One in-mod wiki article: a short, plain-English explanation of a game term
 * (a "more" multiplier, Max HP, a full-set ability, etc.).
 *
 * <p>Pure data — no Minecraft types — so the registry can be authored as plain
 * literals and the renderer ({@link InteractiveItemTooltip}) decides how to lay
 * it out. {@code paragraphs} render as wrapped blocks separated by a blank line.
 */
public record WikiEntry(String id, String title, List<String> paragraphs) {}
