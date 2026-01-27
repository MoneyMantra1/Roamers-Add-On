package com.giphychat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.eventbus.api.SubscribeEvent;

@Mod.EventBusSubscriber(modid = GiphyChatMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientKeys {
    public static final KeyMapping OPEN_GIPHY = new KeyMapping(
            "key.giphychat.open",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_G,
            "key.categories.giphychat"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GIPHY);
    }
}
