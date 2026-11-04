package com.aleks.ancientsmod.net.payload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningTreePayloadTest {
    @Test void readsEveryRealServerViewAndKeepsClassTreesSeparate() throws Exception {
        for(int view=0;view<7;view++) {
            byte[] body;
            try(var in=getClass().getClassLoader().getResourceAsStream("mining-tree-"+view+".bin")) {
                assertNotNull(in);body=in.readAllBytes();
            }
            var l=HunterTreeLayoutPayload.decode(body);
            assertEquals(view==0?487:13,l.nodes().size());
            assertEquals(1,l.nodes().stream().filter(n->n.kind()==0).count());
            assertEquals(view==0?4:0,l.nodes().stream().filter(n->n.kind()==4).count());
            assertEquals(11,l.stats().size());
            for(var n:l.nodes()) {
                assertTrue(Float.isFinite(n.px())&&Float.isFinite(n.py()));
                assertTrue(n.stat()<l.stats().size());assertTrue(n.ability()<l.abilities().size());
            }
            assertEquals(0,l.labels().size());
            if(view>0)assertTrue(l.nodes().stream().noneMatch(n->n.stat()>=0),"Class nodes use their own descriptions");
        }
    }
}
