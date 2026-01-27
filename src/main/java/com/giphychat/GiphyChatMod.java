package com.giphychat;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(GiphyChatMod.MOD_ID)
public class GiphyChatMod {
    public static final String MOD_ID = "giphychat";

    public GiphyChatMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientInit::onClientSetup);
    }
}
