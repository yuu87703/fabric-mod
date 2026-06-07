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

    // 当前跟随玩家的召唤物映射：玩家UUID → [召唤物UUID列表]
    private static final Map<UUID, List<UUID>> FOLLOWING_MOBS = new HashMap<>();

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
        registerBeaconCleanup();
        FabricMod.LOGGER.info("LegendsCommandHandler initialized");
    }

    // ═══════════════════════════════════════════════════
    //  包注册
    // ═══════════════════════════════════════════════════

    private static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(GKeyPressedPayload.ID, GKeyPressedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CommandModePayload.ID, CommandModePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BannerStatePayload.ID, BannerStatePayload.CODEC);
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
        // 旗帜状态同步
        ServerPlayNetworking.registerGlobalReceiver(BannerStatePayload.ID, (payload, context) -> {
            if (!payload.raised()) {
                // 放下旗帜 → 移除跟随目标，恢复自主攻击
                context.server().execute(() -> {
                    onBannerLowered(context.player());
                });
            }
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
            // 潜行+右键 → 冲锋信标；普通右键 → 移动命令
            if (sp.isSneaking()) {
                commandCharge(sp.getServerWorld(), pos, sp);
                sp.sendMessage(
                    Text.literal("§c⚡ 冲锋信标已放置 — 召唤物正在突击该区域！"), false);
            } else {
                commandMove(selectNearbyMobs(sp), pos);
                sp.sendMessage(
                    Text.literal("§e[指挥] §6" + getNearbyCount(sp) + " §e个单位正在移动"), false);
            }
            return ActionResult.FAIL;
        });

        // 右键实体 → 集火目标
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(entity instanceof LivingEntity target)) return ActionResult.PASS;
            if (!canCommand(sp)) return ActionResult.PASS;

            // 集火：只对当前跟随中的召唤物生效
            List<MobEntity> followingMobs = getFollowingMobs(sp);
            if (!followingMobs.isEmpty()) {
                commandAttack(followingMobs, target);
                sp.sendMessage(
                    Text.literal("§c⚔ 集火目标：§e" + target.getName().getString()
                        + " §c—— §6" + followingMobs.size() + " §c个单位正在攻击！"),
                    false
                );
            }
            return ActionResult.FAIL;
        });
    }

    private static boolean canCommand(ServerPlayerEntity sp) {
        // 命令前置条件：手持旗帜或木棍（跟随状态）
        // 不手持则无法指挥——放下旗帜后怪物原地待命
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
    //  跟随状态管理
    // ═══════════════════════════════════════════════════
    //  玩家举起旗帜时触发跟随，放下旗帜时停止。
    //  服务端维护 Map<玩家UUID, List<召唤物UUID>> 以便快速查询。

    /**
     * 将召唤物注册为跟随当前玩家。
     */
    public static void registerFollowing(ServerPlayerEntity player, MobEntity mob) {
        FOLLOWING_MOBS
            .computeIfAbsent(player.getUuid(), k -> new ArrayList<>())
            .add(mob.getUuid());
    }

    /**
     * 取消召唤物的跟随状态（放下旗帜、死亡、消失时调用）。
     */
    public static void unregisterFollowing(ServerPlayerEntity player, MobEntity mob) {
        List<UUID> list = FOLLOWING_MOBS.get(player.getUuid());
        if (list != null) list.remove(mob.getUuid());
    }

    /**
     * 清除玩家所有跟随召唤物（玩家断开连接时调用）。
     */
    public static void clearFollowing(ServerPlayerEntity player) {
        FOLLOWING_MOBS.remove(player.getUuid());
    }

    /**
     * 获取玩家当前跟随的召唤物数量。
     */
    public static int getFollowingCount(ServerPlayerEntity player) {
    public static List<MobEntity> getFollowingMobs(ServerPlayerEntity player) {
        List<MobEntity> result = new ArrayList<>();
        List<UUID> uuids = FOLLOWING_MOBS.get(player.getUuid());
        if (uuids == null) return result;
        for (UUID uid : uuids) {
            Entity e = player.getServerWorld().getEntity(uid);
            if (e instanceof MobEntity m && m.isAlive()) result.add(m);
        }
        return result;
    }
        List<UUID> list = FOLLOWING_MOBS.get(player.getUuid());
        return list == null ? 0 : list.size();
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
            registerFollowing(player, mob);
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

    /**
     * 绑定 AI：根据现有跟随数量分配阵型位置索引，实现扇形散开。
     */
    public static void bindGoals(MobEntity mob, ServerPlayerEntity player) {
        // 防燃烧
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE,
                StatusEffectInstance.INFINITE, 0, false, false, false));

        // 计算阵型索引：当前已有多少只召唤物在跟随
        int formationIndex = countFollowingMobs(player);

        // 跟随（阵型扇形散开）
        mob.goalSelector.add(1, new FollowOwnerGoal(mob, player, 1.2, 2.0, 12.0, formationIndex));

        // 三级目标选择器
        mob.targetSelector.add(0, new LockOnGoalWrapper(mob));               // p0 集火
        mob.targetSelector.add(1, new ChargeTargetGoal(mob));                // p1 冲锋信标
        mob.targetSelector.add(1, new DefendPlayerTargetGoal(mob, player));  // p1 防御
        mob.targetSelector.add(2, new AutoTargetGoal(mob));                  // p2 索敌
    }

    /**
     * 统计当前有多少己方召唤物存活，用于分配阵型索引。
     */
    private static int countFollowingMobs(ServerPlayerEntity player) {
        return selectNearbyMobs(player).size();
    }

    /**
     * 移除跟随目标：放下旗帜时调用。
     * 清除 goalSelector 中的 FollowOwnerGoal，
     * 添加 MeleeAttackGoal 让怪物能自主攻击，
     * 保留 targetSelector 中的自动索敌和防御目标。
     */
    public static void removeFollowGoal(MobEntity mob) {
        mob.goalSelector.clear(g -> true);
        // 添加近战攻击 AI，使怪物能自主攻击目标选择器锁定的敌人
        mob.goalSelector.add(0, new MeleeAttackGoal((PathAwareEntity) mob, 1.2, true));
    }

    /**
     * 放下旗帜时，对所有跟随中的召唤物执行 removeFollowGoal。
     */
    public static void onBannerLowered(ServerPlayerEntity player) {
        List<UUID> following = FOLLOWING_MOBS.get(player.getUuid());
        if (following == null) return;

        ServerWorld world = player.getServerWorld();
        for (UUID mobUuid : following) {
            Entity entity = world.getEntity(mobUuid);
            if (entity instanceof MobEntity mob && mob.isAlive()) {
                removeFollowGoal(mob);
            }
        }
        // 注意：不清除 FOLLOWING_MOBS 列表，下次举起旗帜时可以继续使用
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

    /**
     * 冲锋信标：在指定位置放置信标，激活所有召唤物的 ChargeTargetGoal，
     * 使它们优先攻击信标区域内的敌对生物。
     */
    // 存储活跃的冲锋信标（盔甲架UUID → [盔甲架, 玩家UUID]）
    private static final Map<UUID, net.minecraft.entity.decoration.ArmorStandEntity> ACTIVE_BEACONS = new HashMap<>();
    private static final Map<UUID, UUID> BEACON_OWNERS = new HashMap<>();

    public static void commandCharge(ServerWorld world, Vec3d beaconPos, ServerPlayerEntity player) {
        // ── ① 生成浮空旗帜信标（隐形盔甲架 + 旗帜头饰）──
        net.minecraft.entity.decoration.ArmorStandEntity beacon = new net.minecraft.entity.decoration.ArmorStandEntity(
                world, beaconPos.x, beaconPos.y - 0.5, beaconPos.z);
        beacon.setInvisible(true);
        beacon.setNoGravity(true);
        beacon.setInvulnerable(true);
        beacon.equipStack(net.minecraft.entity.EquipmentSlot.HEAD,
                new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_BANNER));
        world.spawnEntity(beacon);
        ACTIVE_BEACONS.put(beacon.getUuid(), beacon);
        BEACON_OWNERS.put(beacon.getUuid(), player.getUuid());

        // ── ② 粒子效果：火焰光柱 + 魂火环 ──
        for (int h = 0; h < 20; h++) {
            world.spawnParticles(net.minecraft.particle.ParticleTypes.FLAME,
                    beaconPos.x, beaconPos.y + h * 0.4, beaconPos.z,
                    2, 0.3, 0, 0.3, 0.03);
        }
        for (int deg = 0; deg < 360; deg += 15) {
            double rad = Math.toRadians(deg);
            world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                    beaconPos.x + 2.5 * Math.cos(rad), beaconPos.y + 0.3,
                    beaconPos.z + 2.5 * Math.sin(rad),
                    1, 0, 0, 0, 0.02);
        }
        // ── ③ 激活附近召唤物的冲锋 AI ──
        // --- 3. Charge all FOLLOWING mobs toward the beacon ---
        List<UUID> following = FOLLOWING_MOBS.get(player.getUuid());
        if (following != null) {
            for (UUID uid : following) {
                Entity e = world.getEntity(uid);
                if (!(e instanceof MobEntity m) || !m.isAlive()) continue;
                m.getNavigation().stop();
                m.setTarget(null);
                m.goalSelector.clear(g -> true);
                m.goalSelector.add(0, new net.minecraft.entity.ai.goal.MeleeAttackGoal((PathAwareEntity) m, 1.3, true));
                m.getNavigation().startMovingTo(beaconPos.x, beaconPos.y, beaconPos.z, 1.3);
                activateChargeOnMob(m, beaconPos);
            }
        }
    }

    private static void activateChargeOnMob(MobEntity mob, Vec3d beaconPos) {
        mob.targetSelector.clear(g -> true);
        mob.targetSelector.add(0, new LockOnGoalWrapper(mob));
        ChargeTargetGoal charge = new ChargeTargetGoal(mob);
        charge.activate(beaconPos);
        mob.targetSelector.add(1, charge);
        mob.targetSelector.add(2, new AutoTargetGoal(mob));
    }

    /**
     * 初始化信标过期清理（在 initialize 中注册）。
     */
    private static void registerBeaconCleanup() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ACTIVE_BEACONS.isEmpty()) return;
            ACTIVE_BEACONS.entrySet().removeIf(entry -> {
                net.minecraft.entity.decoration.ArmorStandEntity stand = entry.getValue();
                if (!stand.isAlive()) return true;
                if (stand.age > 160) {
                    stand.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                    UUID ownerUuid = BEACON_OWNERS.remove(entry.getKey());
                    if (ownerUuid != null) {
                        net.minecraft.server.network.ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                        if (owner != null) recoverFollowState(owner);
                    }
                    return true;
                }
                return false;
            });
        });
    }
    // ═══════════════════════════════════════════════════
    /**
     * 信标过期后，恢复所有跟随中的召唤物的跟随状态。
     */
    private static void recoverFollowState(ServerPlayerEntity player) {
        List<UUID> following = FOLLOWING_MOBS.get(player.getUuid());
        if (following == null) return;
        for (UUID uid : following) {
            Entity e = player.getServerWorld().getEntity(uid);
            if (e instanceof MobEntity mob && mob.isAlive()) {
                bindGoals(mob, player);
            }
        }
        player.sendMessage(
                Text.literal("§7⬆ 冲锋结束，召唤物回到跟随状态"), false);
    }
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
