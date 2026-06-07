package com.example.fabricmod.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * 修复后的跟随 AI：
 * - 修复卡地形：导航失败时重试 + 卡住检测 + 自动传送
 * - 修复远距离脱队：降低传送阈值 + 导航失败立即传送
 * - 保留原有仿马匹行为：速度 1.2，停止距离 2 格
 */
public class FollowOwnerGoal extends Goal {

    private final MobEntity mob;
    private final PlayerEntity player;
    private final double speed;
    private final double stopDistance;
    private final double teleportDistance;       // 超出此距离 → 传送
    private final double stuckTeleportDistance;  // 小于此距离但卡住 → 也传送

    private int cooldown;                        // 传送冷却 tick
    private int stuckTimer;                      // 卡住计时器
    private Vec3d lastPos;                       // 上一帧位置，用于检测卡住
    private int failedPathCount;                 // 连续寻路失败次数
    private int retryCooldown;                   // 寻路重试冷却

    private static final int STUCK_THRESHOLD = 40;       // 卡住判定：40 tick (~2秒) 位置没变
    private static final int MAX_FAILED_PATH = 10;       // 连续 10 次寻路失败就传送
    private static final int TELEPORT_COOLDOWN = 30;     // 传送冷却：30 tick (~1.5秒)

    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed,
                            double stopDistance, double teleportDistance) {
        this.mob = mob;
        this.player = player;
        this.speed = speed;
        this.stopDistance = stopDistance;
        this.teleportDistance = teleportDistance;
        this.stuckTeleportDistance = teleportDistance * 1.5;
        this.setControls(EnumSet.of(Control.MOVE, Control.JUMP));
    }

    public FollowOwnerGoal(MobEntity mob, PlayerEntity player, double speed, double stopDistance) {
        this(mob, player, speed, stopDistance, 12.0);
    }

    @Override
    public boolean canStart() {
        if (player == null || !player.isAlive()) return false;
        double dist = mob.distanceTo(player);
        return dist > stopDistance;
    }

    @Override
    public boolean shouldContinue() {
        return player != null && player.isAlive();
    }

    @Override
    public void start() {
        resetState();
        tryTeleportIfFar();
        tryNavigate();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        resetState();
    }

    @Override
    public void tick() {
        if (player == null || player.isRemoved()) return;
        double dist = mob.distanceTo(player);

        // —— ① 超距或寻路频繁失败 → 传送 ——
        if (dist > teleportDistance || failedPathCount >= MAX_FAILED_PATH) {
            tryTeleport();
            return;
        }

        // —— ② 卡住检测 ——
        Vec3d currentPos = mob.getPos();
        if (lastPos != null && dist > stopDistance) {
            double moved = currentPos.distanceTo(lastPos);
            if (moved < 0.05) {  // 几乎没动
                stuckTimer++;
                if (stuckTimer > STUCK_THRESHOLD) {
                    // 卡住了 → 尝试跳 + 传送
                    mob.getJumpControl().setActive();
                    tryTeleport();
                    stuckTimer = 0;
                    return;
                }
            } else {
                stuckTimer = 0;  // 在移动 → 重置卡住计时
            }
        }
        lastPos = currentPos;

        // —— ③ 正常跟随/停步 ——
        if (dist > stopDistance) {
            tryNavigate();
        } else {
            mob.getNavigation().stop();
            failedPathCount = 0;  // 到达了 → 重置失败计数
        }
    }

    // ────────── 辅助方法 ──────────

    private void resetState() {
        cooldown = 0;
        stuckTimer = 0;
        lastPos = null;
        failedPathCount = 0;
        retryCooldown = 0;
    }

    /**
     * 尝试导航走向玩家。返回值表示是否成功找到路径。
     */
    private boolean tryNavigate() {
        if (retryCooldown > 0) {
            retryCooldown--;
            return false;
        }

        boolean foundPath = mob.getNavigation().startMovingTo(player, speed);
        if (!foundPath) {
            failedPathCount++;
            retryCooldown = 5;  // 5 tick 后再试
        } else {
            failedPathCount = 0;
        }
        return foundPath;
    }

    /**
     * 距离远时直接传送，不走寻路。
     */
    private void tryTeleportIfFar() {
        if (mob.distanceTo(player) > stuckTeleportDistance) {
            tryTeleport();
        }
    }

    /**
     * 尝试将生物传送至玩家附近。
     */
    private void tryTeleport() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = TELEPORT_COOLDOWN;

        if (!(mob.getWorld() instanceof ServerWorld serverWorld)) return;
        if (player == null || player.isRemoved()) return;

        // 尝试多个位置，优先找安全的方块
        for (int attempt = 0; attempt < 12; attempt++) {
            double rad = mob.getRandom().nextDouble() * 2 * Math.PI;
            double r = 2 + mob.getRandom().nextDouble() * 3;  // 2~5 格
            double x = player.getX() + r * Math.cos(rad);
            double z = player.getZ() + r * Math.sin(rad);
            double y = player.getY();

            mob.refreshPositionAndAngles(x, y, z, mob.getYaw(), mob.getPitch());
            if (!serverWorld.isSpaceEmpty(mob)) continue;

            // 传送成功
            serverWorld.spawnParticles(
                    net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
                    mob.getX(), mob.getBodyY(0.5), mob.getZ(),
                    8, 0.5, 0.5, 0.5, 0.1
            );
            mob.getNavigation().stop();
            failedPathCount = 0;
            stuckTimer = 0;
            return;
        }
    }
}
