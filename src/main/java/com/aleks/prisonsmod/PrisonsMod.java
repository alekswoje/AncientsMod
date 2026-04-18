package com.aleks.prisonsmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the mod's logger. This class itself is not an entrypoint —
 * PrisonsMod is a client-only mod; see {@link com.aleks.prisonsmod.client.PrisonsModClient}.
 */
public final class PrisonsMod {
    public static final String MOD_ID = "prisonsmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PrisonsMod() {}
}
