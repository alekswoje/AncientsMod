package com.aleks.ancientsmod.render;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.net.ExcavationPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import java.io.*;
import java.util.*;

/** Local orbiting pieces, curved trails, countdown rings, and a converging recovery reveal.
 * The server keeps the central artifact/labels visible to every client, including Bedrock. */
public final class ExcavationRenderer {
    private static final Map<Integer, Visual> VISUALS = new HashMap<>();
    private static ClientWorld trackedWorld;
    private static int nextEntityId = 1_850_000_000;
    private static long windowStart;
    private static int packets, tick;

    private static final class Visual {
        final int id, instability, travel;
        int kind;
        final double fx, fy, fz, tx, ty, tz;
        final List<ItemEntity> pieces = new ArrayList<>();
        int age, remaining;
        long syncMs, recoveringAt;
        Visual(int id, int kind, double[] coords, int travel, int age, int remaining, int instability, long now) {
            this.id = id; this.kind = kind; this.instability = instability; this.travel = travel;
            fx = coords[0]; fy = coords[1]; fz = coords[2]; tx = coords[3]; ty = coords[4]; tz = coords[5];
            sync(age, remaining, now);
        }
        void sync(int age, int remaining, long now) { this.age = age; this.remaining = remaining; syncMs = now; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ExcavationPayload.ID, ExcavationPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ExcavationPayload.ID, (payload, context) -> {
            if (!ServerAllowlist.isAllowed()) return;
            receive(payload.data());
        });
    }

    private static void receive(byte[] bytes) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !FeatureToggles.isExcavationEffectsEnabled()) return;
        if (trackedWorld != client.world) { reset(); trackedWorld = client.world; }
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000) { windowStart = now; packets = 0; }
        if (++packets > 40 || bytes.length < 1 || bytes.length > 96) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int op = in.readUnsignedByte();
            if (op == 0 && bytes.length == 1) {
                // End-of-encounter clears live points while allowing the last recovery reveal to finish.
                VISUALS.values().removeIf(v -> {
                    if (v.recoveringAt != 0) return false;
                    remove(v); return true;
                });
            } else if (op == 1 && bytes.length == 67) {
                int id = in.readInt(), kind = in.readUnsignedByte();
                double[] coords = new double[6];
                for (int i = 0; i < coords.length; i++) {
                    coords[i] = in.readDouble();
                    if (!Double.isFinite(coords[i]) || Math.abs(coords[i]) > 30_000_000) return;
                }
                int travel = in.readInt(), age = in.readInt(), remaining = in.readInt(), instability = in.readUnsignedByte();
                if (kind > 4 || travel < 0 || travel > 60 || age < 0 || age > 12_000
                        || remaining < 1 || remaining > 12_000 || instability > 3) return;
                // Never render a remote or malformed packet far from this player.
                if (client.player == null || client.player.squaredDistanceTo(coords[3], coords[4], coords[5]) > 96 * 96) return;
                Visual v = VISUALS.get(id);
                if (v == null) {
                    if (VISUALS.size() >= 8) return;
                    v = new Visual(id, kind, coords, travel, age, remaining, instability, now);
                    VISUALS.put(id, v);
                } else if (v.recoveringAt == 0) v.sync(age, remaining, now);
            } else if (op == 2 && bytes.length == 7) {
                Visual v = VISUALS.get(in.readInt()); boolean recovered = in.readBoolean();
                int kind = in.readUnsignedByte();
                if (v == null || kind > 4) return;
                if (recovered) {
                    if (v.kind != kind) { remove(v); v.kind = kind; }
                    v.recoveringAt = now;
                }
                else { remove(v); VISUALS.remove(v.id); }
            }
        } catch (IOException | RuntimeException ignored) { /* Cosmetic packets cannot break the connection. */ }
    }

    public static void tick(long now) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != trackedWorld || !ServerAllowlist.isAllowed() || !FeatureToggles.isExcavationEffectsEnabled()) {
            reset(); return;
        }
        if (trackedWorld == null) return;
        tick++;
        Iterator<Visual> iterator = VISUALS.values().iterator();
        while (iterator.hasNext()) {
            Visual v = iterator.next();
            double elapsed = (now - v.syncMs) / 50.0;
            if ((v.recoveringAt != 0 && now - v.recoveringAt > 900)
                    || (v.recoveringAt == 0 && (elapsed > v.remaining + 20 || now - v.syncMs > 3000))) {
                remove(v); iterator.remove(); continue;
            }
            double age = v.age + elapsed;
            double t = v.travel == 0 ? 1 : Math.min(1, age / v.travel);
            double x = v.fx + (v.tx - v.fx) * t;
            double y = v.fy + (v.ty - v.fy) * t + Math.sin(t * Math.PI) * 1.5;
            double z = v.fz + (v.tz - v.fz) * t;
            if (v.recoveringAt != 0) { x = v.tx; y = v.ty; z = v.tz; }
            ensurePieces(v, x, y, z);
            double collapse = v.recoveringAt == 0 ? 0 : Math.min(1, (now - v.recoveringAt) / 700.0);
            double radius = (.3 + v.instability * .09) * (1 - collapse);
            for (int i = 0; i < v.pieces.size(); i++) {
                double angle = age * (.14 + v.instability * .025) + i * Math.PI * 2 / v.pieces.size();
                double wobble = Math.sin(age * .8 + i) * v.instability * .015;
                v.pieces.get(i).setPosition(x + Math.cos(angle) * radius + wobble,
                        y + Math.sin(angle * 1.4) * radius * .5, z + Math.sin(angle) * radius + wobble);
            }
            if (tick % 2 == 0) {
                client.particleManager.addParticle(t < 1 ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT,
                        x, y, z, 0, .01, 0);
                if (v.recoveringAt != 0) client.particleManager.addParticle(ParticleTypes.ELECTRIC_SPARK,
                        x, y, z, Math.cos(age) * .08, .03, Math.sin(age) * .08);
            }
            if (t >= 1 && v.kind > 0 && v.recoveringAt == 0 && tick % 5 == 0) {
                double fraction = Math.min(1, Math.max(0, v.remaining - elapsed) / 240.0);
                for (int i = 0; i < 24 * fraction; i++) {
                    double a = i * Math.PI * 2 / 24;
                    client.particleManager.addParticle(ParticleTypes.ELECTRIC_SPARK,
                            v.tx + Math.cos(a) * .72, v.ty - .15, v.tz + Math.sin(a) * .72, 0, 0, 0);
                }
            }
        }
    }

    private static void ensurePieces(Visual v, double x, double y, double z) {
        if (!v.pieces.isEmpty()) return;
        Item item = switch (v.kind) {
            case 1 -> Items.PRISMARINE_CRYSTALS; case 2 -> Items.ECHO_SHARD;
            case 3 -> Items.NAUTILUS_SHELL; case 4 -> Items.HEART_OF_THE_SEA; default -> Items.AMETHYST_SHARD;
        };
        for (int i = 0; i < 3; i++) {
            ItemStack stack = new ItemStack(item); stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            ItemEntity piece = new ItemEntity(trackedWorld, x, y, z, stack);
            piece.setId(nextEntityId++);
            if (nextEntityId > 1_851_000_000) nextEntityId = 1_850_000_000;
            piece.setNoGravity(true); piece.noClip = true; piece.setNeverDespawn();
            piece.setPickupDelay(32767); piece.setVelocity(0, 0, 0);
            trackedWorld.addEntity(piece); v.pieces.add(piece);
        }
    }

    private static void remove(Visual v) {
        for (ItemEntity piece : v.pieces) {
            if (trackedWorld != null) trackedWorld.removeEntity(piece.getId(), Entity.RemovalReason.DISCARDED);
            else piece.discard();
        }
        v.pieces.clear();
    }
    public static void reset() {
        for (Visual v : VISUALS.values()) remove(v);
        VISUALS.clear(); trackedWorld = null;
    }
    private ExcavationRenderer() {}
}
