package com.aleks.ancientsmod.client.bugreport;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.RawPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.dialog.DialogScreen;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.network.PacketByteBuf;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Screenshot for the server's /bugreport dialog. When the player sends
 * {@code /bugreport}, the mod announces a shot (so the dialog offers an
 * "attach screenshot" toggle), grabs the next frame of the world before the HUD,
 * chat and screens are drawn (see {@code GameRendererBugReportShotMixin}), and
 * streams it to the server as a JPEG. The server keeps or drops it depending on
 * the toggle; nothing is saved on disk here. The dialog that follows gets its
 * text box focused, so the player can start typing straight away.
 */
public final class BugReportShot {
    /** Long edge of the uploaded image. Enough to read terrain, small enough to send in a second. */
    private static final int MAX_EDGE = 1600;
    private static final float[] QUALITIES = {0.85f, 0.65f, 0.45f};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AncientsMod-bugreport-shot");
        thread.setDaemon(true);
        return thread;
    });

    /** A dialog opening this soon after /bugreport is the report form. Its title is
     *  drawn glyph art, so timing is the reliable way to recognise it. */
    private static final long FOCUS_WINDOW_MS = 10_000L;

    /** Shot id waiting for the next world frame, or 0. Render thread only. */
    private static int pendingShotId;
    private static long armedAtMs;

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String name = command.strip().split("\\s+", 2)[0];
            if (name.equalsIgnoreCase("bugreport")) arm();
            return true;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof DialogScreen<?> dialog
                    && System.currentTimeMillis() - armedAtMs < FOCUS_WINDOW_MS) {
                focusFirstTextBox(dialog);
            }
        });
    }

    /** Focuses the first text input, walking down through the dialog's scroll container. */
    private static boolean focusFirstTextBox(ParentElement parent) {
        for (Element child : parent.children()) {
            if (child instanceof EditBoxWidget || child instanceof TextFieldWidget
                    || (child instanceof ParentElement nested && focusFirstTextBox(nested))) {
                parent.setFocused(child);
                return true;
            }
        }
        return false;
    }

    private static void arm() {
        if (!ServerAllowlist.isAllowed() || !ClientPlayNetworking.canSend(RawPayload.ID)) return;
        int shotId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(5));
        buf.writeByte(Protocol.PKT_BUGREPORT_SHOT_BEGIN);
        buf.writeInt(shotId);
        if (!send(buf)) return;
        pendingShotId = shotId;
        armedAtMs = System.currentTimeMillis();
    }

    /** Called mid-frame once the world is drawn and before any GUI is. */
    public static void onWorldRendered(MinecraftClient client) {
        int shotId = pendingShotId;
        if (shotId == 0) return;
        pendingShotId = 0;
        try {
            ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> {
                int[] argb;
                int w, h;
                try (image) {
                    int step = Math.max(1, (int) Math.ceil(Math.max(image.getWidth(), image.getHeight()) / (double) MAX_EDGE));
                    w = image.getWidth() / step;
                    h = image.getHeight() / step;
                    argb = sample(image, step, w, h);
                }
                WORKER.execute(() -> encodeAndSend(client, shotId, argb, w, h));
            });
        } catch (Throwable t) {
            AncientsMod.LOGGER.warn("bug report screenshot failed", t);
            sendChunks(client, shotId, null);
        }
    }

    private static int[] sample(NativeImage image, int step, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = image.getColorArgb(x * step, y * step);
            }
        }
        return out;
    }

    private static void encodeAndSend(MinecraftClient client, int shotId, int[] argb, int w, int h) {
        byte[] jpeg = null;
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, argb, 0, w);
            for (float q : QUALITIES) {
                jpeg = encode(img, q);
                if (jpeg.length <= Protocol.MAX_BUGREPORT_SHOT_BYTES) break;
                jpeg = null;
            }
        } catch (Throwable t) {
            AncientsMod.LOGGER.warn("bug report screenshot encode failed", t);
        }
        byte[] body = jpeg;
        client.execute(() -> sendChunks(client, shotId, body));
    }

    private static byte[] encode(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** {@code jpeg == null} tells the server the capture failed, so it stops offering it. */
    private static void sendChunks(MinecraftClient client, int shotId, byte[] jpeg) {
        if (client.getNetworkHandler() == null) return;
        int size = Protocol.BUGREPORT_SHOT_CHUNK_BYTES;
        int count = jpeg == null ? 0 : (jpeg.length + size - 1) / size;
        for (int i = 0; i < Math.max(1, count); i++) {
            int start = i * size;
            int len = jpeg == null ? 0 : Math.min(size, jpeg.length - start);
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(len + 16));
            buf.writeByte(Protocol.PKT_BUGREPORT_SHOT_CHUNK);
            buf.writeInt(shotId);
            buf.writeVarInt(i);
            buf.writeVarInt(count);
            buf.writeVarInt(len);
            if (len > 0) buf.writeBytes(jpeg, start, len);
            if (!send(buf)) return;
        }
    }

    private static boolean send(PacketByteBuf buf) {
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            ClientPlayNetworking.send(new RawPayload(data));
            return true;
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("send bug report screenshot failed", t);
            return false;
        }
    }

    private BugReportShot() {}
}
