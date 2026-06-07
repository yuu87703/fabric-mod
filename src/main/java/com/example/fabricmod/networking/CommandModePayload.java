package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 命令模式切换包：客户端按 R 键时发送给服务端。
 */
public record CommandModePayload(boolean active) implements CustomPayload {
    public static final CustomPayload.Id<CommandModePayload> ID =
            new CustomPayload.Id<>(Identifier.of(FabricMod.MOD_ID, "command_mode"));
    public static final PacketCodec<PacketByteBuf, CommandModePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.BOOL, CommandModePayload::active,
                    CommandModePayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
