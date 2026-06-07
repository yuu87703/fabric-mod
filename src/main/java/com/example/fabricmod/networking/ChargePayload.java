package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * 冲锋信标包：客户端→服务端
 * 玩家潜行+右键地面时，发送射线检测到的坐标。
 */
public record ChargePayload(double x, double y, double z) implements CustomPayload {
    public static final CustomPayload.Id<ChargePayload> ID =
            new CustomPayload.Id<>(Identifier.of(FabricMod.MOD_ID, "charge"));
    public static final PacketCodec<PacketByteBuf, ChargePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.DOUBLE, ChargePayload::x,
                    PacketCodecs.DOUBLE, ChargePayload::y,
                    PacketCodecs.DOUBLE, ChargePayload::z,
                    ChargePayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public Vec3d toVec3d() { return new Vec3d(x, y, z); }
}
