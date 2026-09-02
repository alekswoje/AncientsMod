package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_MININGSIM_SNAPSHOT} — one {@code /miningsim}
 * session snapshot, pushed at 1 Hz while a session runs and once more when it ends.
 *
 * <p>{@link Row#source()} is a full proc chain, e.g. {@code "Proc Party > Shatter >
 * Powerball"}. The first segment is the origin the server credited the reward to; the
 * rest is how it got there. {@link Row#origin()} and {@link Row#path()} split that.
 *
 * <p>Money is carried as thousandths so a small $/hr doesn't round to zero on the wire,
 * and so are blocks — they are prestige-weighted (emerald 1.25, calcite 2, debris 1.5) and
 * so come out fractional. The weighting is deliberate: this is the same number as the
 * Blocks/h readout on the mining HUD, and two block counters on screen at once had better
 * agree.
 *
 * <p>{@code totalBlocks} and {@link Row#blocks()} are zero when the server is old enough
 * not to send the block tail, and on any session archived before it existed.
 */
public record MiningSimPayload(boolean paused, boolean isFinal, boolean noSession,
                               long elapsedMs, long miningElapsedMs,
                               long totalXp, long totalEnergy, double totalMoney,
                               double totalBlocks,
                               List<Row> sources, List<ProcRow> procs,
                               long receivedMs) {

    /** One reward row: what the chain earned, split by kind. */
    public record Row(String source, long xp, long energy, double money, double blocks) {
        /** The origin the reward was credited to — the head of the chain. */
        public String origin() {
            int i = source.indexOf(" > ");
            return i < 0 ? source : source.substring(0, i);
        }

        /** The chain below the origin, or empty when the origin is the whole label. */
        public String path() {
            int i = source.indexOf(" > ");
            return i < 0 ? "" : source.substring(i + 3);
        }

        /** Combined magnitude, used as the default sort key. Blocks stay out of it: they
         *  are a different unit and would dominate the ranking on a Shatter-heavy run. */
        public long weight() {
            return xp + energy + Math.round(money);
        }
    }

    public record ProcRow(String name, int count) {
        public String origin() {
            int i = name.indexOf(" > ");
            return i < 0 ? name : name.substring(0, i);
        }

        public String path() {
            int i = name.indexOf(" > ");
            return i < 0 ? "" : name.substring(i + 3);
        }
    }

    public static MiningSimPayload decode(PacketByteBuf buf) {
        int flags = buf.readByte() & 0xFF;
        boolean paused = (flags & 1) != 0;
        boolean isFinal = (flags & 2) != 0;
        boolean noSession = (flags & 4) != 0;

        long elapsed = Math.max(0L, buf.readVarLong());
        long miningElapsed = Math.max(1L, buf.readVarLong());
        long xp = Math.max(0L, buf.readVarLong());
        long energy = Math.max(0L, buf.readVarLong());
        double money = Math.max(0L, buf.readVarLong()) / 1000.0;

        int sourceCount = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_ROWS);
        List<Row> sources = new ArrayList<>(sourceCount);
        for (int i = 0; i < sourceCount; i++) {
            String label = buf.readString(Protocol.MAX_MININGSIM_LABEL_CHARS);
            long rowXp = Math.max(0L, buf.readVarLong());
            long rowEnergy = Math.max(0L, buf.readVarLong());
            double rowMoney = Math.max(0L, buf.readVarLong()) / 1000.0;
            sources.add(new Row(label, rowXp, rowEnergy, rowMoney, 0.0));
        }

        int procCount = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_ROWS);
        List<ProcRow> procs = new ArrayList<>(procCount);
        for (int i = 0; i < procCount; i++) {
            String label = buf.readString(Protocol.MAX_MININGSIM_LABEL_CHARS);
            int count = Math.max(0, buf.readVarInt());
            procs.add(new ProcRow(label, count));
        }

        // Blocks tail. Appended after the proc table rather than slotted in beside the
        // other totals so that a server that predates it simply stops here — reading it
        // mid-payload would have desynced this jar against the live one.
        double totalBlocks = readBlockTail(buf, sources);

        return new MiningSimPayload(paused, isFinal, noSession, elapsed, miningElapsed,
                xp, energy, money, totalBlocks, List.copyOf(sources), List.copyOf(procs),
                System.currentTimeMillis());
    }

    /**
     * Read the trailing block section and fold it into {@code sources} in place. Returns
     * the session total, or 0 when the server sent no tail.
     *
     * <p>Block values are positional against the source table — the labels are already on
     * the wire once and a 1 Hz packet has no room to repeat 64 of them.
     */
    static double readBlockTail(PacketByteBuf buf, List<Row> sources) {
        if (buf.readableBytes() <= 0) return 0.0;
        double total = Math.max(0L, buf.readVarLong()) / 1000.0;
        int rows = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_ROWS);
        for (int i = 0; i < rows && buf.readableBytes() > 0; i++) {
            double blocks = Math.max(0L, buf.readVarLong()) / 1000.0;
            if (i < sources.size()) {
                Row r = sources.get(i);
                sources.set(i, new Row(r.source(), r.xp(), r.energy(), r.money(), blocks));
            }
        }
        return total;
    }

    /** Per-hour rate for {@code total} over the rate denominator (paused time excluded). */
    public long perHour(long total) {
        if (miningElapsedMs <= 0L) return 0L;
        return Math.round(total * (3_600_000.0 / miningElapsedMs));
    }

    public double moneyPerHour() {
        if (miningElapsedMs <= 0L) return 0.0;
        return totalMoney * (3_600_000.0 / miningElapsedMs);
    }

    public long blocksPerHour() {
        if (miningElapsedMs <= 0L) return 0L;
        return Math.round(totalBlocks * (3_600_000.0 / miningElapsedMs));
    }
}
