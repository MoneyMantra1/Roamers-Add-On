package com.giphychat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(GiphyChatMod.MOD_ID)
public class GiphyChatMod {
    public static final String MOD_ID = "giphychat";

    public GiphyChatMod(IEventBus modEventBus) {
        modEventBus.addListener(ClientInit::onClientSetup);
    }
}
