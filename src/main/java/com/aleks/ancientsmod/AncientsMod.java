package com.aleks.ancientsmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the mod's logger. This class itself is not an entrypoint —
 * AncientsMod is a client-only mod; see {@link com.aleks.ancientsmod.client.AncientsModClient}.
 */
public final class AncientsMod {
    public static final String MOD_ID = "ancientsmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AncientsMod() {}
}
