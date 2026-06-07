package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.FollowPlayerGoal;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * 自定义网络包注册 —— 服务端接收来自客户端的数据包。
 */
public class ModPackets {

    // 包标识符：此包表示"客户端按下了 G 键"
    public static final Identifier G_KEY_PRESSED_ID = Identifier.of(FabricMod.MOD_ID, "g_key_pressed");

    /**
     * 在服务端注册全局接收器，监听来自客户端的 G 键按下包。
     * 此方法在 ModInitializer.onInitialize() 中调用。
     */
    public static void registerC2SPackets() {
        FabricMod.LOGGER.info("Registering server-bound packet receivers...");

        ServerPlayNetworking.registerGlobalReceiver(G_KEY_PRESSED_ID, ModPackets::onGKeyPressed);
    }

    /**
     * 当服务端收到客户端发送的 G_KEY_PRESSED 包时调用。
     */
    private static void onGKeyPressed(
            MinecraftServer server,
            ServerPlayerEntity player,
            ServerPlayNetworkHandler handler,
            PacketByteBuf buf,
            PacketSender responseSender
    ) {
        // 从 buf 中读取客户端发送的数据（可选）
        String message = buf.readString(32767);

        // 注意：此回调在网络线程中执行，操作游戏逻辑需切到服务端主线程
        server.execute(() -> {
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
        });
    }
}
