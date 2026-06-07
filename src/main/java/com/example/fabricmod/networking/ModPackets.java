package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.FollowPlayerGoal;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/**
 * 自定义网络包注册 —— 服务端接收来自客户端的数据包。
 * 使用 Fabric 1.21.1 的 CustomPayload 新 API。
 */
public class ModPackets {

    /**
     * 注册服务端接收器。
     * 此方法在 ModInitializer.onInitialize() 中调用。
     */
    public static void registerC2SPackets() {
        FabricMod.LOGGER.info("Registering server-bound packet receivers...");

        ServerPlayNetworking.registerGlobalReceiver(GKeyPressedPayload.ID, (payload, context) -> {
            // 此回调在网络线程中执行
            MinecraftServer server = context.server();
            ServerPlayerEntity player = context.player();

            server.execute(() -> handleGKeyPressed(player, payload.message()));
        });
    }

    /**
     * 在主线程上处理 G 键按下事件。
     */
    private static void handleGKeyPressed(ServerPlayerEntity player, String message) {
        FabricMod.LOGGER.info("Received G key packet from player {}: {}", player.getName().getString(), message);

        // 检查玩家主手是否持有旗帜 (BannerItem)
        ItemStack mainHandStack = player.getMainHandStack();

        if (mainHandStack.getItem() instanceof BannerItem) {
            // 主手持旗帜 → 在周围 0~5 格圆内随机生成骷髅和僵尸（各一半）
            World world = player.getWorld();
            int totalMobs = 8;  // 4 骷髅 + 4 僵尸

            for (int i = 0; i < totalMobs; i++) {
                // 在半径 0~5 的圆内均匀随机选点
                double angle = world.random.nextDouble() * 2 * Math.PI;
                double radius = Math.sqrt(world.random.nextDouble()) * 5;
                double dx = radius * Math.cos(angle);
                double dz = radius * Math.sin(angle);

                double spawnX = player.getX() + dx;
                double spawnZ = player.getZ() + dz;
                double spawnY = player.getY();

                // 按序号奇偶决定生成骷髅还是僵尸（各一半）
                MobEntity mob;
                if (i % 2 == 0) {
                    mob = EntityType.SKELETON.create(world);
                } else {
                    mob = EntityType.ZOMBIE.create(world);
                }

                if (mob != null) {
                    mob.setPosition(spawnX, spawnY, spawnZ);

                    // 添加自定义目标：以 1.2 速度跟随玩家，距离 < 2 格时停止移动
                    mob.goalSelector.add(1, new FollowPlayerGoal(mob, player, 1.2, 2.0));

                    world.spawnEntity(mob);
                }
            }

            player.sendMessage(
                    Text.literal("§c⚔ 旗帜挥动！你周围涌出了 §e" + totalMobs + " §c只怪物（骷髅 + 僵尸）！"),
                    false
            );
        } else {
            // 主手未持旗帜
            String itemName = mainHandStack.isEmpty()
                    ? "空手"
                    : mainHandStack.getName().getString();
            player.sendMessage(
                    Text.literal("§c✘ 你主手没有拿旗帜，无法召唤，当前是: §e" + itemName),
                    false
            );
        }
    }
}
