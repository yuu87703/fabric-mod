package com.example.fabricmod.networking;

import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import net.minecraft.scoreboard.Team;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * RTS command handler (singleton) for Minecraft 1.21.1 Fabric.
 *
 * Commands:
 *   commandMove    → bind RtsMoveGoal, march to coordinate
 *   commandAttack  → set target, chase
 *   commandFollow  → rebind FollowOwnerGoal + DefendPlayerTargetGoal
 *   commandStop    → clear target, stop navigation
 *   clearAI        → full reset
 *   selectNearbyMobs → scan 16 blocks for skeletons/zombies
 */
public class RtsCommandHandler {

    private static final RtsCommandHandler INSTANCE = new RtsCommandHandler();
    private RtsCommandHandler() {}
    public static RtsCommandHandler getInstance() { return INSTANCE; }

    // ═══════════════════════════════════════════════
    //  Faction — 阵营系统（敌我识别）
    // ═══════════════════════════════════════════════
    //  玩家 + 所有召唤物属于同一队伍 fabricmod_army
    //  friendlyFire=false 防止误伤
    //  selectNearbyMobs 通过队伍 UUID 过滤识别己方单位

    private static final String ARMY_TEAM = "fabricmod_army";

    /**
     * 获取或创建军队队伍。
     */
    public static Team getOrCreateArmyTeam(ServerWorld world) {
        net.minecraft.scoreboard.Scoreboard sb = world.getScoreboard();
        Team t = sb.getTeam(ARMY_TEAM);
        if (t == null) {
            t = sb.addTeam(ARMY_TEAM);
        }
        return t;
    }

    /**
     * 将实体加入军队阵营。玩家用名称，生物用 UUID。
     */
    public static void addToArmyTeam(Entity entity, ServerWorld world) {
        Team team = getOrCreateArmyTeam(world);
        String key = (entity instanceof net.minecraft.entity.player.PlayerEntity)
                ? entity.getName().getString()
                : entity.getUuidAsString();
        team.getPlayerList().add(key);
    }

    /**
     * 检查实体是否属于军队阵营。
     */
    public static boolean isInArmy(Entity entity, ServerWorld world) {
        Team team = world.getScoreboard().getTeam(ARMY_TEAM);
        if (team == null) return false;
        String key = (entity instanceof net.minecraft.entity.player.PlayerEntity)
                ? entity.getName().getString()
                : entity.getUuidAsString();
        return team.getPlayerList().contains(key);
    }

    /**
     * 从军队阵营移除实体。
     */
    public static void removeFromArmyTeam(Entity entity, ServerWorld world) {
        Team team = world.getScoreboard().getTeam(ARMY_TEAM);
        if (team == null) return;
        String key = (entity instanceof net.minecraft.entity.player.PlayerEntity)
                ? entity.getName().getString()
                : entity.getUuidAsString();
        team.getPlayerList().remove(key);
    }

    // ═══════════════════════════════════════════════
    //  Selection — 选择单位（按阵营识别）
    // ═══════════════════════════════════════════════

    public static List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        List<MobEntity> list = new ArrayList<>();
        ServerWorld world = player.getServerWorld();
        Team armyTeam = world.getScoreboard().getTeam(ARMY_TEAM);
        if (armyTeam == null) return list;

        java.util.Collection<String> members = armyTeam.getPlayerList();
        for (Entity e : world.getEntitiesByClass(MobEntity.class,
                player.getBoundingBox().expand(16),
                e -> e.isAlive() && members.contains(e.getUuidAsString()))) {
            list.add((MobEntity) e);
        }
        player.sendMessage(Text.literal("§e[RTS] §6" + list.size() + " §e个单位"), false);
        return list;
    }

    // ═══════════════════════════════════════════════
    //  Commands — 指挥命令
    // ═══════════════════════════════════════════════

    public static void commandMove(List<MobEntity> mobs, ServerPlayerEntity p, Vec3d target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop();
            m.setTarget(null);
            m.targetSelector.clear(goal -> true);
            m.goalSelector.clear(goal -> true);
            m.goalSelector.add(0, new RtsMoveGoal(m, target, 1.0));
        }
        p.sendMessage(Text.literal("§e[RTS] §6" + mobs.size() + " §e个单位正在移动"), false);
    }

    public static void commandAttack(List<MobEntity> mobs, ServerPlayerEntity p, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            m.getNavigation().stop();
            m.setTarget(target);
            // 清除旧目标选择器 + 锁定新目标
            m.targetSelector.clear(goal -> true);
            m.targetSelector.add(0, new LockOnGoal(m, target));
            // 清除旧移动 AI + 添加追击 AI（覆盖 FollowOwnerGoal）
            m.goalSelector.clear(goal -> true);
            m.goalSelector.add(0, new net.minecraft.entity.ai.goal.MeleeAttackGoal(
                    (net.minecraft.entity.mob.PathAwareEntity) m, 1.2, true));
        }
        p.sendMessage(Text.literal("§c[RTS] §6" + mobs.size()
                + " §c个单位正在攻击 §e" + target.getName().getString()), false);
    }

    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity p) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
            m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE,
                    net.minecraft.entity.effect.StatusEffectInstance.INFINITE,
                    0, false, false, false));
            m.goalSelector.add(1, new FollowOwnerGoal(m, p, 1.2, 2.0));
            m.targetSelector.add(1, new DefendPlayerTargetGoal(m, p));
        }
        p.sendMessage(Text.literal("§a[RTS] §6" + mobs.size() + " §a个单位切换为跟随模式"), false);
    }

    public static void commandStop(List<MobEntity> mobs, ServerPlayerEntity p) {
        if (mobs == null || mobs.isEmpty()) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
        }
        p.sendMessage(Text.literal("§7[RTS] §6" + mobs.size() + " §7个单位已停止"), false);
    }

    public static void clearAI(List<MobEntity> mobs) {
        if (mobs == null) return;
        for (MobEntity m : mobs) {
            if (!m.isAlive()) continue;
            resetState(m);
        }
    }

    // ──────── Reset mob state (stop + clear target) ────────
    private static void resetState(MobEntity mob) {
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    // ═══════════════════════════════════════════════
    //  Inner class: LockOnGoal — 锁定攻击指定实体
    // ═══════════════════════════════════════════════

    public static class LockOnGoal extends Goal {
        private final MobEntity mob;
        private final LivingEntity target;
        public LockOnGoal(MobEntity mob, LivingEntity target) {
            this.mob = mob; this.target = target;
            this.setControls(EnumSet.of(Control.TARGET));
        }
        @Override public boolean canStart() { return target != null && target.isAlive(); }
        @Override public boolean shouldContinue() { return target != null && target.isAlive(); }
        @Override public void start() { mob.setTarget(target); }
        @Override public void tick() { if (target.isAlive()) mob.setTarget(target); }
    }

    // ═══════════════════════════════════════════════
    //  Inner class: RtsMoveGoal
    // ═══════════════════════════════════════════════

    public static class RtsMoveGoal extends Goal {
        private final MobEntity mob;
        private final Vec3d target;
        private final double speed;
        private final double stopDistance;

        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed, double stopDistance) {
            this.mob = mob;
            this.target = target;
            this.speed = speed;
            this.stopDistance = stopDistance;
            this.setControls(EnumSet.of(Control.MOVE));
        }
        public RtsMoveGoal(MobEntity mob, Vec3d target, double speed) {
            this(mob, target, speed, 1.0);
        }

        @Override public boolean canStart() {
            return mob.squaredDistanceTo(target) > stopDistance * stopDistance;
        }
        @Override public boolean shouldContinue() {
            return mob.squaredDistanceTo(target) > stopDistance * stopDistance;
        }
        @Override public void start() {
            mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
        }
        @Override public void stop() { mob.getNavigation().stop(); }
        @Override public void tick() {
            if (mob.squaredDistanceTo(target) > stopDistance * stopDistance)
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            else mob.getNavigation().stop();
        }
    }
}
