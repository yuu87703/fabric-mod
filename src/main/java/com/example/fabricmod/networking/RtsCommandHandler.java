package com.example.fabricmod.networking;

import com.example.fabricmod.FabricMod;
import com.example.fabricmod.entity.goal.DefendPlayerTargetGoal;
import com.example.fabricmod.entity.goal.FollowOwnerGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Scoreboard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * RTS 命令处理器（单例模式）
 *
 * 通过原版 Minecraft API 实现生物指挥功能：
 * - 队伍管理（防止误伤）
 * - 选择单位
 * - 移动 / 攻击 / 跟随 / 停止 命令
 */
public class RtsCommandHandler {

    private static final RtsCommandHandler INSTANCE = new RtsCommandHandler();
    private static final String TEAM_PREFIX = "fabricmod_";

    private RtsCommandHandler() {}

    public static RtsCommandHandler getInstance() {
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════
    //  ① 队伍管理 — 静态方法
    // ═══════════════════════════════════════════════

    /**
     * 确保 Scoreboard 上存在本模组的队伍，并返回。
     * 玩家与召唤物各一个独立队伍（共享 TEAM_PREFIX 前缀）。
     */
    public static net.minecraft.scoreboard.Team getOrCreateSummonTeam(ServerWorld world) {
        return INSTANCE._getOrCreateSummonTeam(world);
    }

    public static net.minecraft.scoreboard.Team getOrCreateOwnerTeam(ServerWorld world) {
        return INSTANCE._getOrCreateOwnerTeam(world);
    }

    /**
     * 将玩家加入 owner 队伍（防止召唤物攻击玩家）。
     */
    public static void addPlayerToTeam(ServerPlayerEntity player) {
        INSTANCE._addPlayerToTeam(player);
    }

    /**
     * 将召唤生物加入 summon 队伍。
     */
    public static void addMobToTeam(MobEntity mob, ServerWorld world) {
        INSTANCE._addMobToTeam(mob, world);
    }

    // ────────── 实例方法 ──────────

    private net.minecraft.scoreboard.Team _getOrCreateSummonTeam(ServerWorld world) {
        Scoreboard scoreboard = world.getScoreboard();
        String teamName = TEAM_PREFIX + "summon";
        net.minecraft.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            team.setFriendlyFire(false);
            team.setShowFriendlyInvisibles(true);
        }
        return team;
    }

    private net.minecraft.scoreboard.Team _getOrCreateOwnerTeam(ServerWorld world) {
        Scoreboard scoreboard = world.getScoreboard();
        String teamName = TEAM_PREFIX + "owner";
        net.minecraft.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            team.setFriendlyFire(false);
            team.setShowFriendlyInvisibles(true);
        }
        return team;
    }

    private void _addPlayerToTeam(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        net.minecraft.scoreboard.Team team = _getOrCreateOwnerTeam(world);
        Scoreboard scoreboard = world.getScoreboard();
        String entryName = player.getName().getString();

        net.minecraft.scoreboard.Team current = scoreboard.getPlayerTeam(entryName);
        if (current != null && !current.getName().equals(team.getName())) {
            scoreboard.removePlayerFromTeam(entryName, current);
        }
        scoreboard.addPlayerToTeam(entryName, team);
    }

    private void _addMobToTeam(MobEntity mob, ServerWorld world) {
        net.minecraft.scoreboard.Team team = _getOrCreateSummonTeam(world);
        Scoreboard scoreboard = world.getScoreboard();
        String entryName = mob.getUuidAsString();

        net.minecraft.scoreboard.Team current = scoreboard.getTeam(entryName);
        if (current != null && !current.getName().equals(team.getName())) {
            scoreboard.removePlayerFromTeam(entryName, current);
        }
        scoreboard.addPlayerToTeam(entryName, team);
    }

    // ═══════════════════════════════════════════════
    //  ② 选择单位
    // ═══════════════════════════════════════════════

    /**
     * 选中玩家周围 16 格内所有本模组生成的生物。
     */
    public List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        List<MobEntity> selected = new ArrayList<>();
        ServerWorld world = player.getServerWorld();

        for (Entity entity : world.getEntitiesByClass(MobEntity.class,
                player.getBoundingBox().expand(16),
                e -> (e.getType() == net.minecraft.entity.EntityType.SKELETON
                        || e.getType() == net.minecraft.entity.EntityType.ZOMBIE)
                        && e.isAlive())) {
            selected.add((MobEntity) entity);
        }

        player.sendMessage(Text.literal("§e[RTS] 选中了 §6" + selected.size() + " §e个单位"), false);
        return selected;
    }

    // ═══════════════════════════════════════════════
    //  ③ 指挥命令 — 静态方法
    // ═══════════════════════════════════════════════

    /**
     * 移动命令：指定生物移动到精确坐标。
     * 清除旧 AI，绑定 RtsMoveGoal，到达后原地待命。
     */
    public static void commandMove(List<MobEntity> mobs, ServerPlayerEntity player, Vec3d target) {
        INSTANCE._commandMove(mobs, player, target);
    }

    public static void commandAttack(List<MobEntity> mobs, ServerPlayerEntity player, LivingEntity target) {
        INSTANCE._commandAttack(mobs, player, target);
    }

    public static void commandFollow(List<MobEntity> mobs, ServerPlayerEntity player) {
        INSTANCE._commandFollow(mobs, player);
    }

    public static void commandStop(List<MobEntity> mobs, ServerPlayerEntity player) {
        INSTANCE._commandStop(mobs, player);
    }

    public static List<MobEntity> selectNearbyMobs(ServerPlayerEntity player) {
        return INSTANCE._selectNearbyMobs(player);
    }

    // ────────── 实例方法 ──────────

    private void _commandMove(List<MobEntity> mobs, ServerPlayerEntity player, Vec3d target) {
        if (mobs == null || mobs.isEmpty()) return;

        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();
            mob.setTarget(null);
            mob.goalSelector.add(1, new RtsMoveGoal(mob, target, 1.0));
        }

        player.sendMessage(Text.literal("§e[RTS] §6" + mobs.size() + " §e个单位正在移动"), false);
    }

    private void _commandAttack(List<MobEntity> mobs, ServerPlayerEntity player, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;

        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();
            mob.setTarget(target);
            mob.getNavigation().startMovingTo(target, 1.2);
        }

        player.sendMessage(
                Text.literal("§c[RTS] §6" + mobs.size() + " §c个单位正在攻击 §e" + target.getName().getString()),
                false
        );
    }

    /**
     * 清空生物当前所有 AI 和目标选择器，重置状态。
     */
    public static void clearAI(List<MobEntity> mobs) {
        INSTANCE._clearAI(mobs);
    }

    private void _clearAI(List<MobEntity> mobs) {
        if (mobs == null) return;
        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();
            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setAttacker(null);
            mob.setAttacking(null);
        }
    }

    /**
     * 攻击命令：设置生物的 target 并走向目标实体。
     */
    public void commandAttack(List<MobEntity> mobs, ServerPlayerEntity player, LivingEntity target) {
        if (mobs == null || mobs.isEmpty()) return;

        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();
            mob.setTarget(target);
            mob.getNavigation().startMovingTo(target, 1.2);
        }

        player.sendMessage(
                Text.literal("§c[RTS] §6" + mobs.size() + " §c个单位正在攻击 §e" + target.getName().getString()),
                false
        );
    }

    /**
     * 跟随命令：清除旧 AI，重新绑定 FollowOwnerGoal + DefendPlayerTargetGoal。
     */
    public void commandFollow(List<MobEntity> mobs, ServerPlayerEntity player) {
        if (mobs == null || mobs.isEmpty()) return;

        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();

            mob.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE,
                    net.minecraft.entity.effect.StatusEffectInstance.INFINITE,
                    0, false, false, false
            ));

            mob.goalSelector.add(1, new FollowOwnerGoal(mob, player, 1.2, 2.0));
            mob.targetSelector.add(1, new DefendPlayerTargetGoal(mob, player));
        }

        player.sendMessage(Text.literal("§a[RTS] §6" + mobs.size() + " §a个单位切换为跟随模式"), false);
    }

    /**
     * 停止命令：清除 AI 并停下当前动作。
     */
    public void commandStop(List<MobEntity> mobs, ServerPlayerEntity player) {
        if (mobs == null || mobs.isEmpty()) return;

        for (MobEntity mob : mobs) {
            if (!mob.isAlive()) continue;
            mob.goalSelector.clear();
            mob.targetSelector.clear();
            mob.getNavigation().stop();
            mob.setTarget(null);
        }

        player.sendMessage(Text.literal("§7[RTS] §6" + mobs.size() + " §7个单位已停止"), false);
    }

    // ═══════════════════════════════════════════════
    //  ④ 内部类：RtsMoveGoal
    // ═══════════════════════════════════════════════

    /**
     * RTS 定点移动 AI（内部类）
     *
     * 让生物持续走向目标坐标，到达后原地待命，
     * 不会自动退回跟随玩家，不会攻击沿途目标。
     */
    public static class RtsMoveGoal extends Goal {

        private final PathAwareEntity mob;
        private final Vec3d target;
        private final double speed;
        private final double stopDistance;

        public RtsMoveGoal(PathAwareEntity mob, Vec3d target, double speed, double stopDistance) {
            this.mob = mob;
            this.target = target;
            this.speed = speed;
            this.stopDistance = stopDistance;
            this.setControls(EnumSet.of(Control.MOVE));
        }

        public RtsMoveGoal(PathAwareEntity mob, Vec3d target, double speed) {
            this(mob, target, speed, 1.0);
        }

        @Override
        public boolean canStart() {
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }

        @Override
        public boolean shouldContinue() {
            return mob.distanceTo(target.x, target.y, target.z) > stopDistance;
        }

        @Override
        public void start() {
            mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (mob.distanceTo(target.x, target.y, target.z) > stopDistance) {
                mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
            } else {
                mob.getNavigation().stop();
            }
        }
    }
}
