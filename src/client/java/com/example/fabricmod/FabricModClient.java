package com.example.fabricmod;

import com.example.fabricmod.client.ModKeyBindings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side entry point.
 * This class is only loaded on the client.
 */
@Environment(EnvType.CLIENT)
public class FabricModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricMod.LOGGER.info("{} client initialized!", FabricMod.MOD_ID);

        // === Register key bindings (注册按键 G，按下时向服务端发包) ===
        ModKeyBindings.register();

        // === Register screen handlers here ===
        // Example:
        // ModScreenHandlers.register();

        // === Register entity renderers here ===
        // Example:
        // ModEntityRenderers.register();
    }
}
