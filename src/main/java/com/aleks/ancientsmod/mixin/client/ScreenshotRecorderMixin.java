package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ScreenshotClipboard;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(ScreenshotRecorder.class)
public abstract class ScreenshotRecorderMixin {
    // Both vanilla entry points end here. Capture the opt-in when the screenshot
    // is taken, then wait for the successful save before reading the PNG.
    @ModifyVariable(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;Lnet/minecraft/client/gl/Framebuffer;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private static Consumer<Text> ancientsmod$copySavedScreenshot(Consumer<Text> receiver) {
        if (!FeatureToggles.isScreenshotClipboardEnabled()) return receiver;
        return message -> {
            receiver.accept(message);
            if (message.getContent() instanceof TranslatableTextContent content
                    && "screenshot.success".equals(content.getKey())
                    && content.getArgs().length > 0
                    && content.getArgs()[0] instanceof Text filename
                    && filename.getStyle().getClickEvent() instanceof ClickEvent.OpenFile file) {
                ScreenshotClipboard.copy(Path.of(file.path()));
            }
        };
    }
}
