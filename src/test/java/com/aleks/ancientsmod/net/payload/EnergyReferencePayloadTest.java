package com.aleks.ancientsmod.net.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The energy reference table with and without the player block. A server from before the
 * block sends a ladder row per stored level; one with it sends a row per rung (P1..P4) and
 * says which stored level each rung is.
 */
class EnergyReferencePayloadTest {

    /** Tax byte, no gear tiers, one Diamond pick tier with the given ladder rows {prestige, energy, blocks}. */
    private static PacketByteBuf table(int taxPercent, long[][] ladder) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(taxPercent);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeString("Diamond");
        buf.writeVarInt(1);
        buf.writeVarInt(10);
        buf.writeVarLong(5_000L);
        buf.writeVarInt(ladder.length);
        for (long[] row : ladder) {
            buf.writeVarInt((int) row[0]);
            buf.writeVarLong(row[1]);
            buf.writeString("Diamond Ore");
            buf.writeVarLong(row[2]);
        }
        return buf;
    }

    private static final long[][] RUNGS = {
            {1, 100, 70_000}, {2, 900, 555_000}, {3, 5_000, 6_325_000}, {4, 9_000, 36_000_000}};

    private static void profile(PacketByteBuf buf, int taxBp, int flags, long capacity, int multMilli, long perHour) {
        buf.writeVarInt(1);
        buf.writeVarInt(taxBp);
        buf.writeByte(flags);
        buf.writeVarLong(capacity);
        buf.writeVarInt(multMilli);
        buf.writeVarLong(perHour);
        buf.writeVarInt(4);
        for (int stored : new int[] {1, 5, 10, 11}) buf.writeVarInt(stored);
    }

    @Test
    void olderServerHasNoProfileAndKeepsOneStepPerLevel() {
        PacketByteBuf buf = table(20, new long[][] {{1, 100, 70_000}, {2, 200, 125_000}, {3, 300, 195_000}});
        try {
            EnergyReferencePayload p = EnergyReferencePayload.decode(buf);
            assertFalse(p.isEmpty());
            assertNull(p.profile());
            assertEquals(0.20, p.taxRate(), 1e-9);
            EnergyReferencePayload.PickTier tier = p.pickTiers().get(0);
            assertEquals(2, p.shownPrestige(2));
            assertEquals(3, p.nextStep(tier, 2).prestige());
            assertNull(p.nextStep(tier, 3));
            assertFalse(p.reached(0, 3));
        } finally { buf.release(); }
    }

    @Test
    void profileMapsStoredLevelsOntoTheFourRungs() {
        PacketByteBuf buf = table(8, RUNGS);
        profile(buf, 840, 2, 1_000, 5_312, 3_600);
        try {
            EnergyReferencePayload p = EnergyReferencePayload.decode(buf);
            EnergyReferencePayload.Profile profile = p.profile();
            assertNotNull(profile);
            assertEquals(0, buf.readableBytes());
            assertEquals("8.4", profile.taxText());
            assertEquals(0.084, p.taxRate(), 1e-9);
            assertFalse(profile.inSafeZone());
            assertTrue(profile.holdingPickaxe());
            assertEquals(5.312, profile.energyMultiplier(), 1e-9);

            // The item stores 0, 1, 5, 10, 11; players see P0..P4.
            int[] stored = {0, 1, 5, 10, 11};
            for (int shown = 0; shown < stored.length; shown++) assertEquals(shown, p.shownPrestige(stored[shown]));

            EnergyReferencePayload.PickTier tier = p.pickTiers().get(0);
            assertEquals(1, p.nextStep(tier, 0).prestige());
            assertEquals(2, p.nextStep(tier, 1).prestige());
            assertEquals(900, p.nextStep(tier, 1).energyCost());
            assertEquals(3, p.nextStep(tier, 5).prestige());
            assertEquals(4, p.nextStep(tier, 10).prestige());
            assertNull(p.nextStep(tier, 11));

            assertTrue(p.reached(0, 5));
            assertTrue(p.reached(1, 5));
            assertFalse(p.reached(2, 5));
            assertEquals(15_000, tier.totalPrestigeEnergy());
        } finally { buf.release(); }
    }

    @Test
    void fillsAndTimesComeFromCapacityAndTheLiveRate() {
        PacketByteBuf buf = table(0, RUNGS);
        profile(buf, 0, 3, 1_000, 1_000, 3_600);
        try {
            EnergyReferencePayload.Profile profile = EnergyReferencePayload.decode(buf).profile();
            assertNotNull(profile);
            assertTrue(profile.inSafeZone());
            assertEquals("0", profile.taxText());
            assertEquals(0, profile.fillsFor(0));
            assertEquals(1, profile.fillsFor(1_000));
            assertEquals(2, profile.fillsFor(1_001));
            assertEquals(500, profile.secondsToGain(500));
            assertEquals(0, profile.secondsToGain(0));
        } finally { buf.release(); }

        PacketByteBuf idle = table(0, RUNGS);
        profile(idle, 0, 2, 1_000, 1_000, 0);
        try {
            assertEquals(-1, EnergyReferencePayload.decode(idle).profile().secondsToGain(500));
        } finally { idle.release(); }
    }

    @Test
    void aBrokenProfileKeepsTheTables() {
        PacketByteBuf buf = table(12, RUNGS);
        buf.writeVarInt(1);
        buf.writeVarInt(1_200);   // then the packet ends mid-block
        try {
            EnergyReferencePayload p = EnergyReferencePayload.decode(buf);
            assertFalse(p.isEmpty());
            assertNull(p.profile());
            assertEquals(4, p.pickTiers().get(0).ladder().size());
        } finally { buf.release(); }
    }
}
