package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 自定义网络包 Payload：客户端按下 G 键时向服务端发送的信号。
 * Fabric 1.21.1 使用 CustomPayload 系统替代旧的 Identifier 通道。
 *
 * @param message 携带的文本消息
 */
public record GKeyPressedPayload(String message) implements CustomPayload {

    // 包的唯一标识符
    public static final CustomPayload.Id<GKeyPressedPayload> ID =
            new CustomPayload.Id<>(new Identifier(FabricMod.MOD_ID, "g_key_pressed"));

    // 序列化/反序列化编解码器
    public static final PacketCodec<PacketByteBuf, GKeyPressedPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, GKeyPressedPayload::message,
                    GKeyPressedPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
