package com.giphychat;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.eventbus.api.SubscribeEvent;

@Mod.EventBusSubscriber(modid = GiphyChatMod.MOD_ID, value = Dist.CLIENT)
public class ClientTickHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen instanceof GiphyScreen) {
            return;
        }

        while (ClientKeys.OPEN_GIPHY.consumeClick()) {
            minecraft.setScreen(new GiphyScreen());
        }
    }
}
