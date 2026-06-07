package com.example.fabricmod;

import com.example.fabricmod.networking.ModPackets;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Fabric mod.
 * This class is loaded on both the server and client (unless specified otherwise).
 */
public class FabricMod implements ModInitializer {
    public static final String MOD_ID = "fabricmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Create an Identifier for the given path under this mod's namespace.
     */
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("{} v{} initialized!", MOD_ID, FabricMod.class.getPackage().getImplementationVersion());

        // === Register custom packets (声明包类型 + 注册服务端接收器) ===
        ModPackets.registerPayload();
        ModPackets.registerC2SPackets();

        // === Register items here ===
        // Example:
        // ModItems.register();

        // === Register blocks here ===
        // Example:
        // ModBlocks.register();

        // === Register block entities here ===
        // Example:
        // ModBlockEntities.register();

        // === Register entities here ===
        // Example:
        // ModEntities.register();
    }
}
