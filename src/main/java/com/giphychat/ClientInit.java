package com.giphychat;

import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

public class ClientInit {
    private ClientInit() {
    }

    public static void onClientSetup(final FMLClientSetupEvent event) {
        // Client-only setup hooks are in event subscribers.
    }
}
