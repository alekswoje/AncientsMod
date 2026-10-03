package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.client.hud.MiningCompState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningCompPayloadTest {
    // Matches the server's DataOutputStream int/byte and VarInt-string wire format.
    private PacketByteBuf header(int rank, int count) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(150); buf.writeString("Emerald"); buf.writeInt(rank);
        buf.writeInt(400); buf.writeByte(count);
        return buf;
    }
    @Test void preservesPersonalPositionOutsideTopFiveAndTies() {
        var buf = header(8, 5);
        try {
            for (int i = 0; i < 5; i++) { buf.writeString("Miner" + i); buf.writeInt(1000 - i / 2); }
            var state = MiningCompPayload.decode(buf);
            assertEquals(8, state.rank()); assertEquals(400, state.blocks());
            assertEquals(5, state.entries().size()); assertEquals(1000, state.entries().get(1).blocks());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void rejectsOversizedAndUnsortedSnapshots() {
        var oversized = header(0, 6);
        try { assertThrows(IllegalArgumentException.class, () -> MiningCompPayload.decode(oversized)); }
        finally { oversized.release(); }
        var unsorted = header(0, 2);
        try {
            unsorted.writeString("A"); unsorted.writeInt(5);
            unsorted.writeString("B"); unsorted.writeInt(10);
            assertThrows(IllegalArgumentException.class, () -> MiningCompPayload.decode(unsorted));
        } finally { unsorted.release(); }
    }
    @Test void handlesEmptyCompetitionAndClearsOnEndOrTransfer() {
        var buf = header(0, 0);
        try {
            var state = MiningCompPayload.decode(buf);
            MiningCompState.update(state);
            assertNotNull(MiningCompState.current());
            assertEquals(0, MiningCompState.current().rank());
            MiningCompState.clear(); assertNull(MiningCompState.current());
            MiningCompState.update(state);
            MiningCompState.update(new MiningCompPayload(0, "", 0, 0, java.util.List.of()));
            assertNull(MiningCompState.current());
        } finally { buf.release(); MiningCompState.clear(); }
    }
}
