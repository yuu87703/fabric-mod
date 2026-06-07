package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 旗帜状态同步包：客户端→服务端
 * 当玩家举起/放下旗帜时发送。
 */
public record BannerStatePayload(boolean raised) implements CustomPayload {
    public static final CustomPayload.Id<BannerStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(FabricMod.MOD_ID, "banner_state"));
    public static final PacketCodec<PacketByteBuf, BannerStatePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.BOOL, BannerStatePayload::raised,
                    BannerStatePayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
