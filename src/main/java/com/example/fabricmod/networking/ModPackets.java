package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BannerItem;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 自定义网络包注册 —— 服务端接收来自客户端的数据包。
 * 使用 Fabric 1.21.1 的 CustomPayload 新 API。
 */
public class ModPackets {

    // 命令模式：记录哪些玩家处于 RTS 命令模式
    private static final Set<UUID> COMMAND_MODE_PLAYERS = new HashSet<>();

    public static boolean isInCommandMode(ServerPlayerEntity player) {
        return COMMAND_MODE_PLAYERS.contains(player.getUuid());
    }

    /**
     * 先向 Fabric API 声明数据包类型（必须！否则崩溃）。
     */
    public static void registerPayload() {
        FabricMod.LOGGER.info("Registering payload type: {}", GKeyPressedPayload.ID.id());
        PayloadTypeRegistry.playC2S().register(GKeyPressedPayload.ID, GKeyPressedPayload.CODEC);

        FabricMod.LOGGER.info("Registering payload type: {}", CommandModePayload.ID.id());
        PayloadTypeRegistry.playC2S().register(CommandModePayload.ID, CommandModePayload.CODEC);
    }

    /**
     * 注册服务端接收器 + 交互回调。
     */
    public static void registerC2SPackets() {
        FabricMod.LOGGER.info("Registering server-bound packet receivers...");

        // G 键 → 召唤
        ServerPlayNetworking.registerGlobalReceiver(GKeyPressedPayload.ID, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayerEntity player = context.player();
            server.execute(() -> handleGKeyPressed(player, payload.message()));
        });

        // R 键 → 切换命令模式
        ServerPlayNetworking.registerGlobalReceiver(CommandModePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (payload.active()) {
                COMMAND_MODE_PLAYERS.add(player.getUuid());
            } else {
                COMMAND_MODE_PLAYERS.remove(player.getUuid());
            }
        });

        // 命令模式下的右键交互
        registerInteractionCallbacks();
    }

    /**
     * 注册右键交互回调：命令模式下右键=指挥。
     */
    private static void registerInteractionCallbacks() {
        // 右键方块 → move 命令（命令模式 或 手持旗帜/木棍）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (hand != net.minecraft.util.Hand.MAIN_HAND) return ActionResult.PASS;

            boolean inCommandMode = isInCommandMode(sp);
            boolean holdingBanner = sp.getMainHandStack().getItem() instanceof BannerItem;
            boolean holdingStick = sp.getMainHandStack().getItem() == net.minecraft.item.Items.STICK;
            if (!inCommandMode && !holdingBanner && !holdingStick) return ActionResult.PASS;

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            Vec3d pos = blockHit.getBlockPos().toCenterPos();

            List<MobEntity> selected = LegendsCommandHandler.selectNearbyMobs(sp);
            LegendsCommandHandler.commandMove(selected, pos);

            return ActionResult.FAIL;
        });

        // 右键实体 → attack 命令（两种模式：命令模式 或 手持旗帜/木棍直接指挥）
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!(entity instanceof LivingEntity target)) return ActionResult.PASS;

            // 只处理主手
            if (hand != net.minecraft.util.Hand.MAIN_HAND) return ActionResult.PASS;

            boolean inCommandMode = isInCommandMode(sp);
            boolean holdingBanner = sp.getMainHandStack().getItem() instanceof BannerItem;
            boolean holdingStick = sp.getMainHandStack().getItem() == net.minecraft.item.Items.STICK;

            // 条件：命令模式下 或 手持旗帜/木棍
            if (!inCommandMode && !holdingBanner && !holdingStick) return ActionResult.PASS;

            List<MobEntity> selected = LegendsCommandHandler.selectNearbyMobs(sp);
            LegendsCommandHandler.commandAttack(selected, target);

            sp.sendMessage(
                    Text.literal("§c[RTS] §6" + selected.size() + " §c个单位正在攻击 §e" + target.getName().getString()),
                    true
            );

            return ActionResult.FAIL;  // 阻止原版右键交互
        });
    }

    /**
     * 在玩家周围显示半径为 5 格的圆形粒子边界并填充内部。
     */
    private static void showRangeAnimation(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        double cx = player.getX();
        double cy = player.getY();
        double cz = player.getZ();

        // --- 边界圆环：半径 5 格，每隔 10° 一个粒子 ---
        for (int deg = 0; deg < 360; deg += 10) {
            double rad = Math.toRadians(deg);
            double x = cx + 5 * Math.cos(rad);
            double z = cz + 5 * Math.sin(rad);
            world.spawnParticles(ParticleTypes.END_ROD,
                    x, cy + 0.2, z, 1, 0, 0, 0, 0.01);
        }

        // --- 内部填充：圆内随机散布 60 个粒子（半径 0~5 均匀分布）---
        for (int i = 0; i < 60; i++) {
            double r = Math.sqrt(world.random.nextDouble()) * 5;
            double rad = world.random.nextDouble() * 2 * Math.PI;
            double x = cx + r * Math.cos(rad);
            double z = cz + r * Math.sin(rad);
            world.spawnParticles(ParticleTypes.END_ROD,
                    x, cy + 0.2, z, 1, 0, 0, 0, 0.005);
        }

        // --- 中心标记：向上光柱 ---
        for (int h = 0; h < 8; h++) {
            world.spawnParticles(ParticleTypes.END_ROD,
                    cx, cy + 0.5 + h * 0.5, cz,
                    3, 0.3, 0, 0.3, 0.01);
        }
    }

    /**
     * 为生成的生物绑定 AI（方法声明）。
     * 优先级说明：
     *   goalSelector 优先级 1 — 跟随玩家移动（最高，覆盖默认游荡）
     *   targetSelector 优先级 1 — 攻击玩家目标（最高，覆盖默认目标）
     */
    private static void addGoalToEntity(MobEntity entity, ServerPlayerEntity player) {
        // —— ③ 防燃烧：给予永久火焰抗性，防止亡灵在阳光下自燃 ——
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.FIRE_RESISTANCE,
                StatusEffectInstance.INFINITE,  // 永久持续
                0,                              // 等级 I
                false,                          // 非环境效果
                false,                          // 不显示粒子
                false                           // 不显示图标
        ));

        // —— ① 跟随：以 1.2 速度走向玩家，距离 < 2 格时停止（避免挤撞） ——
        entity.goalSelector.add(1, new FollowOwnerGoal(entity, player, 1.2, 2.0));

        // —— ② 护卫：攻击玩家正在攻击的目标，或攻击玩家的实体 ——
        entity.targetSelector.add(1, new DefendPlayerTargetGoal(entity, player));
    }

    /**
     * 生成骷髅和僵尸，添加跟随 AI。
     */
    private static void spawnAndFollow(ServerPlayerEntity player) {
        showRangeAnimation(player);

        World world = player.getWorld();
        int totalMobs = 8;

        for (int i = 0; i < totalMobs; i++) {
            double angle = world.random.nextDouble() * 2 * Math.PI;
            double radius = Math.sqrt(world.random.nextDouble()) * 5;
            double dx = radius * Math.cos(angle);
            double dz = radius * Math.sin(angle);

            double spawnX = player.getX() + dx;
            double spawnZ = player.getZ() + dz;
            double spawnY = player.getY();

            MobEntity mob;
            if (i % 2 == 0) {
                mob = EntityType.SKELETON.create(world);
            } else {
                mob = EntityType.ZOMBIE.create(world);
            }

            if (mob != null) {
                mob.setPosition(spawnX, spawnY, spawnZ);
                addGoalToEntity(mob, player);
                world.spawnEntity(mob);
            }
        }

        player.sendMessage(
                Text.literal("§c⚔ 旗帜挥动！你周围涌出了 §e" + totalMobs + " §c只怪物（骷髅 + 僵尸）！"),
                false
        );
    }

    /**
     * 在主线程上处理 G 键按下事件。
     */
    private static void handleGKeyPressed(ServerPlayerEntity player, String message) {
        FabricMod.LOGGER.info("Received G key packet from player {}: {}", player.getName().getString(), message);

        ItemStack mainHandStack = player.getMainHandStack();

        if (mainHandStack.getItem() instanceof BannerItem) {
            spawnAndFollow(player);
        } else {
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
