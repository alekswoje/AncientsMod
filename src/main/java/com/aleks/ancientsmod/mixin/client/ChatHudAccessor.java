package com.aleks.ancientsmod.mixin.client;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the chat's laid-out line list so the copy overlay can work out which
 * message the cursor is on.
 *
 * <p>{@code visibleMessages} is newest-first and already wrapped: index 0 is
 * the BOTTOM row on screen, and a message too long for one row occupies a
 * contiguous run of entries whose LOWEST index carries
 * {@link ChatHudLine.Visible#endOfEntry()}. {@code scrolledLines} is how far
 * the player has scrolled back, so the row drawn at screen position {@code k}
 * is {@code visibleMessages.get(k + scrolledLines)}.
 *
 * <p>{@code messages} is the unwrapped history, also newest-first: message
 * {@code n} is the one whose {@code endOfEntry} row is the {@code n}-th such row
 * counting up from index 0 of {@code visibleMessages}. Its content is the text
 * as received, before the emoji mixin swaps aliases for glyphs.
 *
 * <p>All three fields are declared on {@link ChatHud} itself, so a plain
 * accessor reaches them.
 */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {

    @Accessor("visibleMessages")
    List<ChatHudLine.Visible> ancientsmod$visibleMessages();

    @Accessor("messages")
    List<ChatHudLine> ancientsmod$messages();

    @Accessor("scrolledLines")
    int ancientsmod$scrolledLines();
}
