package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.net.payload.HunterTreeLayoutPayload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HunterTreeScreenTest {
    /** stats.png holds 10 icons: Flight and Marksman borrow, nothing samples past the texture. */
    @Test void statIconsStayInsideTheAtlas() {
        for (int i = 0; i < 8; i++) assertEquals(i, HunterTreeScreen.statIcon(i));
        assertEquals(7, HunterTreeScreen.statIcon(8));
        assertEquals(0, HunterTreeScreen.statIcon(9));
        assertEquals(8, HunterTreeScreen.statIcon(10));
        assertEquals(9, HunterTreeScreen.statIcon(11));
        assertEquals(-1, HunterTreeScreen.statIcon(12));
        assertEquals(-1, HunterTreeScreen.statIcon(13));
    }

    private static HunterTreeLayoutPayload.Node node(int kind, int icon, int stat) {
        return new HunterTreeLayoutPayload.Node(0, 0, 0f, 0f, false, 0f, 0f, kind, icon, stat, -1);
    }

    /** Crit and path nodes use this mod's own rows; the rest keep the server's kind. */
    @Test void nodeIconsForCritAndPathNodes() {
        assertEquals(23, HunterTreeScreen.nodeIcon(node(1, 6, 10)));
        assertEquals(26, HunterTreeScreen.nodeIcon(node(2, 12, 10)));
        assertEquals(24, HunterTreeScreen.nodeIcon(node(1, 4, 11)));
        assertEquals(27, HunterTreeScreen.nodeIcon(node(2, 10, 11)));
        assertEquals(25, HunterTreeScreen.nodeIcon(node(1, 1, 12)));
        assertEquals(1, HunterTreeScreen.nodeIcon(node(1, 1, 0)));
        assertEquals(15, HunterTreeScreen.nodeIcon(node(3, 15, -1)));
        assertEquals(27, HunterTreeScreen.nodeIcon(node(3, 99, -1)));
    }
}
