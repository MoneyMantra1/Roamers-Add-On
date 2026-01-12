package com.aetria.roamersaddon;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RoamersAddonMod.MODID)
public final class RoamersAddonMod {
    public static final String MODID = "roamers_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public RoamersAddonMod() {
        NeoForge.EVENT_BUS.register(new RoamersAddonEvents());
        LOGGER.info("Roamers Add-On loaded.");
    }
}
