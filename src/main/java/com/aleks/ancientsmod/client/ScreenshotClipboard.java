package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Image clipboard adapters, kept outside Minecraft's headless AWT runtime. */
public final class ScreenshotClipboard {
    // Serialize copies so rapid screenshots cannot finish out of order. Never
    // block rendering or retain a NativeImage after vanilla closes it.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AncientsMod-screenshot-clipboard");
        thread.setDaemon(true);
        return thread;
    });

    public static void copy(Path image) {
        WORKER.execute(() -> {
            try {
                copyImage(image.toAbsolutePath());
                notifyPlayer("Screenshot copied to clipboard.");
            } catch (Exception e) {
                AncientsMod.LOGGER.warn("Could not copy screenshot to clipboard", e);
                notifyPlayer("Screenshot saved, but clipboard copy failed."
                        + (isLinux() ? " Install wl-copy (Wayland) or xclip (X11)." : ""));
            }
        });
    }

    static void copyImage(Path image) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            // The path is passed as data in the environment, never interpolated
            // into PowerShell source. STA + persistent copy survives process exit.
            String script = "Add-Type -AssemblyName System.Windows.Forms; "
                    + "Add-Type -AssemblyName System.Drawing; "
                    + "$ErrorActionPreference='Stop'; "
                    + "$image=[System.Drawing.Image]::FromFile($env:ANCIENTS_SCREENSHOT); "
                    + "try { $data=New-Object System.Windows.Forms.DataObject; "
                    + "$data.SetImage($image); "
                    + "[System.Windows.Forms.Clipboard]::SetDataObject($data,$true,10,100) "
                    + "} finally { $image.Dispose() }";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-STA", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            command.environment().put("ANCIENTS_SCREENSHOT", image.toString());
            run(command);
        } else if (os.startsWith("mac")) {
            run(new ProcessBuilder("/usr/bin/osascript", "-e", "on run argv", "-e",
                    "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
                    "-e", "end run", image.toString()));
        } else if (isLinux()) {
            if (System.getenv("WAYLAND_DISPLAY") != null) {
                run(new ProcessBuilder("wl-copy", "--type", "image/png").redirectInput(image.toFile()));
            } else {
                run(new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-i",
                        image.toString()));
            }
        } else {
            throw new IOException("Unsupported clipboard platform: " + os);
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    private static void run(ProcessBuilder command) throws IOException, InterruptedException {
        Process process = command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new IOException("Clipboard helper timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Clipboard helper exited with code " + process.exitValue());
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.inGameHud.getChatHud().addMessage(Text.literal(message)));
    }

    private ScreenshotClipboard() {}
}
