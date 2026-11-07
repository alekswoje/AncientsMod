package com.aleks.ancientsmod.client.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HunterTreeScreenTest {
    /** stats.png holds 8 icons: Flight and Marksman borrow, nothing samples past the texture. */
    @Test void statIconsStayInsideTheAtlas() {
        for (int i = 0; i < 8; i++) assertEquals(i, HunterTreeScreen.statIcon(i));
        assertEquals(7, HunterTreeScreen.statIcon(8));
        assertEquals(0, HunterTreeScreen.statIcon(9));
        assertEquals(-1, HunterTreeScreen.statIcon(10));
    }
}
