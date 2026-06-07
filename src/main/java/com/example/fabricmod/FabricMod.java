package com.example.fabricmod;

import com.example.fabricmod.networking.LegendsCommandHandler;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 《我的世界：传奇》风格指挥模组 — 主入口
 * Minecraft 1.21.1 · Fabric API 0.102+
 */
public class FabricMod implements ModInitializer {
    public static final String MOD_ID = "fabricmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("{} v{} initialized!", MOD_ID, FabricMod.class.getPackage().getImplementationVersion());

        // 初始化《传奇》风格指挥系统（包注册 + 回调 + 阵营）
        LegendsCommandHandler.initialize();
    }
}
