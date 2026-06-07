package com.example.fabricmod.client;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.networking.GKeyPressedPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键绑定注册 —— 按下 G 键时向服务端发送自定义包。
 */
public class ModKeyBindings {

    // 定义 G 键绑定
    private static final KeyBinding G_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.fabricmod.g_key",          // 翻译键（用于在语言文件中显示名称）
            InputUtil.Type.KEYSYM,           // 键位类型：键盘按键
            GLFW.GLFW_KEY_G,                // 默认按键：G
            "category.fabricmod"             // 所属分类
    ));

    /**
     * 注册客户端 Tick 事件，在每一帧检测按键状态。
     * 此方法在 ClientModInitializer.onInitializeClient() 中调用。
     */
    public static void register() {
        FabricMod.LOGGER.info("Registering key bindings...");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 当玩家按下 G 键时（仅触发一次，不是按住不放）
            while (G_KEY.wasPressed()) {
                // 仅在连接至服务端时发包（防止单机世界崩溃）
                if (client.getNetworkHandler() == null) continue;

                // 向服务端发送自定义包（使用 Fabric 1.21.1 CustomPayload API）
                ClientPlayNetworking.send(new GKeyPressedPayload("G key was pressed at tick!"));

                FabricMod.LOGGER.info("Sent G_KEY_PRESSED packet to server.");
            }
        });
    }
}
