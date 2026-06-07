package com.example.fabricmod.mixin;

import com.example.fabricmod.FabricMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Example mixin that logs a message when the server starts.
 * To enable this, add "ExampleMixin" to the mixins list in fabricmod.mixins.json.
 */
@Mixin(MinecraftServer.class)
public class ExampleMixin {
    @Inject(at = @At("HEAD"), method = "runServer")
    private void onRunServer(CallbackInfo info) {
        FabricMod.LOGGER.info("Hello from {} mixin!", FabricMod.MOD_ID);
    }
}
