package com.example.fabricmod.client;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.networking.CommandModePayload;
import com.example.fabricmod.networking.GKeyPressedPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定：
 *   G → 召唤（持旗帜时）
 *   R → 切换 RTS 命令模式（再次按关闭）
 *   命令模式下右键地面/实体 → 执行 move/attack
 */
public class ModKeyBindings {

    private static final KeyBinding G_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.fabricmod.g_key", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "category.fabricmod"));

    private static final KeyBinding R_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.fabricmod.command_mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R,
            "category.fabricmod"));

    private static boolean commandModeActive = false;

    public static boolean isCommandModeActive() {
        return commandModeActive;
    }

    public static void register() {
        FabricMod.LOGGER.info("Registering key bindings...");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getNetworkHandler() == null) return;

            // G 键 → 召唤
            while (G_KEY.wasPressed()) {
                ClientPlayNetworking.send(new GKeyPressedPayload("G key pressed"));
            }

            // R 键 → 切换命令模式
            while (R_KEY.wasPressed()) {
                commandModeActive = !commandModeActive;
                ClientPlayNetworking.send(new CommandModePayload(commandModeActive));

                if (commandModeActive) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal("§e[RTS] 命令模式 §a开启§e — 右键地面=移动, 右键实体=攻击"),
                            true
                    );
                } else {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal("§7[RTS] 命令模式已关闭"),
                            true
                    );
                }
            }
        });
    }
}
