package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.BannerItem;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * 《传奇》风格指挥系统 — 单例模式
 *
 * 职责：
 * - 队伍/阵营管理（fabricmod_army）
 * - 包注册 + 服务端接收器
 * - 右键交互回调（移动/攻击）
 * - 召唤逻辑 + 粒子动画
 * - 命令执行（move / attack / follow / stop）
 * - AI 目标绑定（三级优先级）
 */
public class LegendsCommandHandler {

    private static final LegendsCommandHandler INSTANCE = new LegendsCommandHandler();
    private static final String ARMY_TEAM = "fabricmod_army";
    private static final Set<UUID> COMMAND_MODE_PLAYERS = new HashSet<>();

    private LegendsCommandHandler() {}
    public static LegendsCommandHandler getInstance() { return INSTANCE; }

    // ═══════════════════════════════════════════════════
    //  初始化（在 ModInitializer.onInitialize 中调用）
    // ═══════════════════════════════════════════════════

    public static void initialize() {
        registerPayloads();
        registerReceivers();
        registerInteractionCallbacks();
        registerDamageEvents();
        FabricMod.LOGGER.info("LegendsCommandHandler initialized");
    }

    // ═══════════════════════════════════════════════════
    //  包注册
    // ═══════════════════════════════════════════════════

    private static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(GKeyPressedPayload.ID, GKeyPressedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CommandModePayload.ID, CommandModePayload.CODEC);
    }

    private static void registerReceivers() {
        // G 键 → 召唤
        ServerPlayNetworking.registerGlobalReceiver(GKeyPressedPayload.ID, (payload, context) -> {
            context.server().execute(() -> handleSummon(context.player()));
        });
        // R 键 → 切换命令模式
        ServerPlayNetworking.registerGlobalReceiver(CommandModePayload.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            if (payload.active()) COMMAND_MODE_PLAYERS.add(p.getUuid());
            else COMMAND_MODE_PLAYERS.remove(p.getUuid());
        });
    }

    private static boolean isInCommandMode(ServerPlayerEntity p) {
        return COMMAND_MODE_PLAYERS.contains(p.getUuid());
    }

    // ═══════════════════════════════════════════════════
    //  交互回调
    // ═══════════════════════════════════════════════════

    private static void registerInteractionCallbacks() {
        // 右键方块 → 移动
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!canCommand(sp)) return ActionResult.PASS;

            Vec3d pos = ((BlockHitResult) hitResult).getBlockPos().toCenterPos();
            commandMove(selectNearbyMobs(sp), pos);
            sp.sendMessage(Text.literal("§e[指挥] §6" + getNearbyCount(sp) + " §e个单位正在移动"), false);
            return ActionResult.FAIL;
        });

        // 右键实体 → 攻击
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(entity instanceof LivingEntity target)) return ActionResult.PASS;
            if (!canCommand(sp)) return ActionResult.PASS;

            commandAttack(selectNearbyMobs(sp), target);
            return ActionResult.FAIL;
        });
    }

    private static boolean canCommand(ServerPlayerEntity sp) {
        if (isInCommandMode(sp)) return true;
        return sp.getMainHandStack().getItem() instanceof BannerItem
                || sp.getMainHandStack().getItem() == Items.STICK;
    }

    // ═══════════════════════════════════════════════════
    //  伤害事件 — 自动防御
    // ═══════════════════════════════════════════════════

    private static void registerDamageEvents() {
        // 自动防御：玩家受伤时召唤物反击（Fabric API AFTER_DAMAGE）
        // 暂时移除，用 DefendPlayerTargetGoal 替代
    }

    // ═══════════════════════════════════════════════════
    //  阵营管理
    // ═══════════════════════════════════════════════════

    public static Team getOrCreateArmyTeam(ServerWorld world) {
        var sb = world.getScoreboard();
        Team t = sb.getTeam(ARMY_TEAM);
        if (t == null) { t = sb.addTeam(ARMY_TEAM); }
        return t;
    }

    public static void addToArmy(Entity entity, ServerWorld world) {
        Team t = getOrCreateArmyTeam(world);
        String key = entity instanceof net.minecraft.entity.player.PlayerEntity
                ? entity.getName().getString() : entity.getUuidAsString();
        t.getPlayerList().add(key);
    }

    // ═══════════════════════════════════════════════════
    //  选择单位
    // ═══════════════════════════════════════════════════

    public static int getNearbyCount(ServerPlayerEntity player) {
        Team t = player.getServerWorld().getScoreboard().getTeam(ARMY_TEAM);
        if (t == null) return 0;
        var members = t.getPlayerList();
        return (int) player.getServerWorld().getEntitiesByClass(
                MobEntity.class, player.getBoundingBox().expand(16),
                e -> e.isAlive() && members.contains(e.getUuidAsString())).size();
    }

    public static List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        List<MobEntity> list = new ArrayList<>();
        ServerWorld world = player.getServerWorld();
        Team t = world.getScoreboard().getTeam(ARMY_TEAM);
        if (t == null) return list;
        var members = t.getPlayerList();
        for (Entity e : world.getEntitiesByClass(MobEntity.class,
                player.getBoundingBox().expand(16),
                e -> e.isAlive() && members.contains(e.getUuidAsString())))
            list.add((MobEntity) e);
        return list;
    }

    // ═══════════════════════════════════════════════════
    //  召唤
    // ═══════════════════════════════════════════════════

    public static void handleSummon(ServerPlayerEntity player) {
        if (!(player.getMainHandStack().getItem() instanceof BannerItem)) {
            player.sendMessage(Text.literal("§c✘ 你需要手持旗帜才能召唤"), false);
            return;
        }

        ServerWorld world = player.getServerWorld();
        addToArmy(player, world);
        showSummonAnimation(world, player.getX(), player.getY(), player.getZ());

        int total = 8;
        for (int i = 0; i < total; i++) {
            double angle = world.random.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(world.random.nextDouble()) * 5;
            double x = player.getX() + r * Math.cos(angle);
            double z = player.getZ() + r * Math.sin(angle);

            MobEntity mob = (i % 2 == 0)
                    ? net.minecraft.entity.EntityType.SKELETON.create(world)
                    : net.minecraft.entity.EntityType.ZOMBIE.create(world);
            if (mob == null) continue;

            mob.setPosition(x, player.getY(), z);
            world.spawnEntity(mob);
            addToArmy(mob, world);
            bindGoals(mob, player);
        }

        player.sendMessage(Text.literal("§c⚔ 旗帜挥动！周围涌出 §e" + total + " §c只怪物"), false);
    }

    private static void showSummonAnimation(ServerWorld world, double cx, double cy, double cz) {
        // 边界圆环
        for (int deg = 0; deg < 360; deg += 10) {
            double rad = Math.toRadians(deg);
            world.spawnParticles(ParticleTypes.END_ROD,
                    cx + 5 * Math.cos(rad), cy + 0.2, cz + 5 * Math.sin(rad),
                    1, 0, 0, 0, 0.01);
        }
        // 内部填充
        for (int i = 0; i < 60; i++) {
            double r = Math.sqrt(world.random.nextDouble()) * 5;
            double rad = world.random.nextDouble() * 2 * Math.PI;
            world.spawnParticles(ParticleTypes.END_ROD,
                    cx + r * Math.cos(rad), cy + 0.2, cz + r * Math.sin(rad),
                    1, 0, 0, 0, 0.005);
        }
        // 光柱
        for (int h = 0; h < 8; h++)
            world.spawnParticles(ParticleTypes.END_ROD,
                    cx, cy + 0.5 + h * 0.5, cz, 3, 0.3, 0, 0.3, 0.01);
    }

    // ═══════════════════════════════════════════════════
    //  AI 绑定（三级目标 + 移动 + 攻击）
    // ═══════════════════════════════════════════════════

    public static void bindGoals(MobEntity mob, ServerPlayerEntity player) {
        // 防燃烧
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE,
                StatusEffectInstance.INFINITE, 0, false, false, false));
        // 跟随
        mob.goalSelector.add(1, new FollowOwnerGoal(mob, player, 1.2, 2.0));
        // 三级目标选择器
        mob.targetSelector.add(0, new LockOnGoalWrapper(mob));               // p0 集火
        mob.targetSelector.add(1, new DefendPlayerTargetGoal(mob, player));  // p1 防御
        mob.targetSelector.add(2, new AutoTargetGoal(mob));                  // p2 索敌
    }

    // ═══════════════════════════════════════════════════
    //  命令执行
    // ═══════════════════════════════════════════════════

    public static void commandMove(List<MobEntity> mobs, Vec3d target) {
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop(); m.setTarget(null);
            m.goalSelector.clear(g -> true); m.targetSelector.clear(g -> true);
            m.goalSelector.add(0, new RtsMoveGoal(m, target, 1.0));
        }
    }

    public static void commandAttack(List<MobEntity> mobs, LivingEntity target) {
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop();
            m.targetSelector.clear(g -> true);
            m.goalSelector.clear(g -> true);
            m.setTarget(target);
            m.targetSelector.add(0, new LockOnGoalWrapper(m, target));
            m.goalSelector.add(0, new MeleeAttackGoal((PathAwareEntity) m, 1.2, true));
        }
    }

    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity p) {
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop(); m.setTarget(null);
            m.goalSelector.clear(g -> true); m.targetSelector.clear(g -> true);
            bindGoals(m, p);
        }
    }

    public static void commandStop(List<MobEntity> mobs) {
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop(); m.setTarget(null);
            m.goalSelector.clear(g -> true); m.targetSelector.clear(g -> true);
        }
    }

    // ═══════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════

    /**
     * 集火目标包装器（p0）。
     * 外部通过 commandAttack 设置目标，锁定后不会丢失。
     */
    public static class LockOnGoalWrapper extends Goal {
        private final MobEntity mob;
        private LivingEntity lockedTarget;
        public LockOnGoalWrapper(MobEntity mob) { this.mob = mob; this.setControls(EnumSet.of(Control.TARGET)); }
        public LockOnGoalWrapper(MobEntity mob, LivingEntity target) { this(mob); this.lockedTarget = target; }
        public void setTarget(LivingEntity t) { this.lockedTarget = t; }
        @Override public boolean canStart() { return lockedTarget != null && lockedTarget.isAlive(); }
        @Override public boolean shouldContinue() { return lockedTarget != null && lockedTarget.isAlive(); }
        @Override public void start() { mob.setTarget(lockedTarget); }
        @Override public void tick() { if (lockedTarget != null && lockedTarget.isAlive()) mob.setTarget(lockedTarget); }
    }

    /**
     * RTS 定点移动
     */
    public static class RtsMoveGoal extends Goal {
        private final MobEntity mob; private final Vec3d target;
        private final double speed; private final double stopDist;
        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed, double stopDist) {
            this.mob=mob; this.target=target; this.speed=speed; this.stopDist=stopDist;
            this.setControls(EnumSet.of(Control.MOVE));
        }
        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed) { this(mob, target, speed, 1.0); }
        @Override public boolean canStart() { return mob.squaredDistanceTo(target) > stopDist*stopDist; }
        @Override public boolean shouldContinue() { return mob.squaredDistanceTo(target) > stopDist*stopDist; }
        @Override public void start() { mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed); }
        @Override public void stop() { mob.getNavigation().stop(); }
        @Override public void tick() {
            if (mob.squaredDistanceTo(target) > stopDist*stopDist)
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            else mob.getNavigation().stop();
        }
    }
}
