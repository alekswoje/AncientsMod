package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The jewel socket and loadout packets at every width a server sends: three (before the fourth
 * socket), four (every account without a fifth) and five (an account that has opened it).
 *
 * <p>The bytes are the ones PrisonsCore's JewelSocketRegression pins for the same account, so the
 * two sides are held to one wire format.
 */
class JewelPayloadTest {

    private static final String SLOTS_FOUR =
            "33040200060841657468657269630fc2a764c2a76c477261766563616c6c166a6577656c5f756e697175655f67726176"
            + "6563616c6c0217c2a7612b31322520c2a7374d696e696e672053706565640ec2a7622b3320c2a73752656163680113c2"
            + "a738c2a76f49742072656d656d626572732e010000000000000000020000000000000003000000000000";
    private static final String SLOTS_FIVE =
            "33050200060841657468657269630fc2a764c2a76c477261766563616c6c166a6577656c5f756e697175655f67726176"
            + "6563616c6c0217c2a7612b31322520c2a7374d696e696e672053706565640ec2a7622b3320c2a73752656163680113c2"
            + "a738c2a76f49742072656d656d626572732e010000000000000000020000000000000003000000000000020003054761"
            + "69616e17c2a765556c74696d61746520476169616e204a6577656c146a6577656c5f676169616e5f756c74696d617465"
            + "0112c2a7612b352520c2a7374f7265204c75636b00";
    private static final String LOADOUTS_FOUR =
            "3d0002064d696e696e67010fc2a764c2a76c477261766563616c6c166a6577656c5f756e697175655f67726176656361"
            + "6c6c00000000000000000000000000000000";
    private static final String LOADOUTS_FIVE =
            "3d0002064d696e696e67010fc2a764c2a76c477261766563616c6c166a6577656c5f756e697175655f67726176656361"
            + "6c6c00000000000017c2a765556c74696d61746520476169616e204a6577656c146a6577656c5f676169616e5f756c74"
            + "696d617465000000000000000000000000";

    /** The packet body as NetworkHandler hands it over: everything after the type byte. */
    private static PacketByteBuf body(String hex) {
        byte[] bytes = new byte[hex.length() / 2 - 1];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 + i * 2, 4 + i * 2), 16);
        }
        return new PacketByteBuf(Unpooled.wrappedBuffer(bytes));
    }

    @Test
    void thisClientAdvertisesTheFifthSocket() {
        assertEquals(13, Protocol.PROTOCOL_MINOR);
        assertEquals(5, Protocol.MAX_JEWEL_SLOTS);
        assertEquals(4, Protocol.PRESTIGE_JEWEL_SLOTS);
        assertEquals(3, Protocol.LEGACY_MAX_JEWEL_SLOTS);
    }

    @Test
    void fourSocketsDecodeAsBefore() {
        PacketByteBuf buf = body(SLOTS_FOUR);
        JewelSlotsPayload p = JewelSlotsPayload.decode(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(4, p.slots().size());
        assertTrue(p.slots().get(0).isFilled());
        assertEquals("Aetheric", p.slots().get(0).familyName());
        assertEquals("jewel_unique_gravecall", p.slots().get(0).modelPath());
        assertEquals(2, p.slots().get(0).statLines().size());
        assertEquals(1, p.slots().get(0).loreLines().size());
        assertFalse(p.slots().get(1).isFilled());
        assertFalse(p.slots().get(1).isLocked());
        assertTrue(p.slots().get(2).isLocked());
        assertEquals(2, p.slots().get(2).requiredPrestige());
        assertEquals(3, p.slots().get(3).requiredPrestige());
    }

    @Test
    void fiveSocketsDecode() {
        PacketByteBuf buf = body(SLOTS_FIVE);
        JewelSlotsPayload p = JewelSlotsPayload.decode(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(5, p.slots().size());
        JewelSlotsPayload.Slot fifth = p.slots().get(4);
        assertTrue(fifth.isFilled());
        assertEquals("Gaian", fifth.familyName());
        assertEquals("jewel_gaian_ultimate", fifth.modelPath());
        assertEquals(0, fifth.requiredPrestige());
        // The first four are the same sockets the four-socket packet carries.
        JewelSlotsPayload four = JewelSlotsPayload.decode(body(SLOTS_FOUR));
        assertEquals(four.slots(), p.slots().subList(0, 4));
    }

    @Test
    void loadoutsDecodeAtTheWidthTheServerUsed() {
        // Whatever the first guess is, the packet is read at the width that consumes it exactly.
        for (int guess = Protocol.LEGACY_MAX_JEWEL_SLOTS; guess <= Protocol.MAX_JEWEL_SLOTS; guess++) {
            JewelLoadoutsPayload four = JewelLoadoutsPayload.decode(body(LOADOUTS_FOUR), guess);
            assertEquals(2, four.pages().size(), "guess " + guess);
            assertEquals(4, four.pages().get(0).jewelNames().size(), "guess " + guess);
            assertEquals("Mining", four.pages().get(0).name());
            assertTrue(four.pages().get(0).unlocked());
            assertEquals(1, four.pages().get(0).filled());
            assertFalse(four.pages().get(1).unlocked());

            JewelLoadoutsPayload five = JewelLoadoutsPayload.decode(body(LOADOUTS_FIVE), guess);
            assertEquals(2, five.pages().size(), "guess " + guess);
            assertEquals(5, five.pages().get(0).jewelNames().size(), "guess " + guess);
            assertEquals(2, five.pages().get(0).filled());
            assertEquals("jewel_gaian_ultimate", five.pages().get(0).modelPaths().get(4));
            assertEquals(0, five.pages().get(1).filled());
        }
    }

    @Test
    void anOlderServersThreeWideLoadoutsStillDecode() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(1);                 // active page
        buf.writeByte(2);                 // pages
        for (int page = 0; page < 2; page++) {
            buf.writeString("Page " + page);
            buf.writeByte(1);
            for (int slot = 0; slot < 3; slot++) {
                buf.writeString(slot == 0 ? "Jewel " + page : "");
                buf.writeString(slot == 0 ? "jewel_simple" : "");
            }
        }
        for (int guess = Protocol.LEGACY_MAX_JEWEL_SLOTS; guess <= Protocol.MAX_JEWEL_SLOTS; guess++) {
            buf.readerIndex(0);
            JewelLoadoutsPayload p = JewelLoadoutsPayload.decode(buf, guess);
            assertEquals(1, p.activePage());
            assertEquals(2, p.pages().size());
            assertEquals(3, p.pages().get(1).jewelNames().size(), "guess " + guess);
            assertEquals("Jewel 1", p.pages().get(1).jewelNames().get(0));
        }
    }

    @Test
    void aSixthSocketFromAFutureServerIsLeftInTheBuffer() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(6);
        for (int i = 0; i < 6; i++) {
            buf.writeByte(Protocol.JEWEL_STATE_EMPTY);
            buf.writeByte(0);
            buf.writeByte(0);
            buf.writeString("");
            buf.writeString("");
            buf.writeString("");
            buf.writeByte(0);
            buf.writeByte(0);
        }
        JewelSlotsPayload p = JewelSlotsPayload.decode(buf);
        assertEquals(Protocol.MAX_JEWEL_SLOTS, p.slots().size());
        assertTrue(buf.readableBytes() > 0);
    }
}
