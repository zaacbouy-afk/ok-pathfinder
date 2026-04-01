package name.ezforaging.pathfinding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;

public class PathfinderAction {
    private static List<BlockPos> path = null;
    private static int currentNode = 0;
    public static boolean active = false;
    private static final double REACH_DISTANCE = 1.2;
    private static final float SMOOTH_SPEED_MIN = 0.1f;
    private static final float SMOOTH_SPEED_MAX = 0.25f;
    private static final float MAX_YAW_PER_FRAME = 5f;
    private static final float MAX_PITCH_PER_FRAME = 3f;

    // Stuck detection & repathing
    private static BlockPos destination = null;
    private static double lastX, lastY, lastZ;
    private static int stuckTicks = 0;
    private static final int STUCK_CHECK_INTERVAL = 10; // check every 0.5 seconds
    private static final double STUCK_THRESHOLD = 1.0;  // must move at least 1 block per check
    private static final int STUCK_REPATH_TICKS = 20;   // repath after ~1 second stuck
    private static int tickCounter = 0;
    private static final float FORWARD_ANGLE_THRESHOLD = 50f; // don't walk forward if facing > 50° off target

    // Target angles computed in tick(), interpolated every frame in renderTick()
    private static float targetYaw = 0f;
    private static float targetPitch = 0f;
    private static long lastRenderNano = 0;
    private static final double TARGET_FRAME_NS = 1_000_000_000.0 / 60.0; // 60 ticks/sec baseline

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || path == null || client.player == null) return;
            tick(client.player);
        });
    }

    public static void start(List<BlockPos> newPath) {
        path = newPath;
        active = true;
        currentNode = 0;
        stuckTicks = 0;
        tickCounter = 0;
        if (newPath != null && !newPath.isEmpty()) {
            destination = newPath.get(newPath.size() - 1);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
        }
    }

    public static void stop() {
        active = false;
        path = null;
        currentNode = 0;
        destination = null;
        stuckTicks = 0;
        tickCounter = 0;
        releaseAllKeys();
    }

    public static boolean isActive() {
        return active;
    }

    public static int getCurrentNode() {
        return currentNode;
    }

    public static void releaseAllKeys() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static float smoothYaw(float current, float target, float scale) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        float absDiff = Math.abs(diff);
        float speed = SMOOTH_SPEED_MIN + (SMOOTH_SPEED_MAX - SMOOTH_SPEED_MIN) * Math.min(absDiff / 90f, 1f);
        float delta = diff * speed * scale;
        float maxYaw = MAX_YAW_PER_FRAME * scale;
        delta = Math.max(-maxYaw, Math.min(maxYaw, delta));
        return current + delta;
    }

    private static float smoothPitch(float current, float target, float scale) {
        float diff = target - current;
        float absDiff = Math.abs(diff);
        float speed = SMOOTH_SPEED_MIN + (SMOOTH_SPEED_MAX - SMOOTH_SPEED_MIN) * Math.min(absDiff / 45f, 1f);
        float delta = diff * speed * scale;
        float maxPitch = MAX_PITCH_PER_FRAME * scale;
        delta = Math.max(-maxPitch, Math.min(maxPitch, delta));
        return current + delta;
    }

    private static void repath(LocalPlayer player) {
        if (destination == null || Minecraft.getInstance().level == null) return;

        BlockPos playerPos = player.blockPosition();
        List<BlockPos> newPath = EzForagingPathfinder.findPath(
                Minecraft.getInstance().level, playerPos, destination, 50000
        );

        if (!newPath.isEmpty()) {
            path = newPath;
            currentNode = 0;
            stuckTicks = 0;
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            PathRenderer.setPath(newPath);
            player.displayClientMessage(
                    Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Repathing " + newPath.size() + " blocks"), false
            );
        } else {
            player.displayClientMessage(
                    Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("No path found, stopping"), false
            );
            stop();
        }
    }

    private static boolean hasLineOfSight(LocalPlayer player, BlockPos target) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;

        double x = player.getX();
        double z = player.getZ();
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        int y = target.getY();

        double dist = Math.sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z));
        int steps = (int) Math.ceil(dist / 0.5);
        if (steps == 0) return true;

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double sx = x + (tx - x) * t;
            double sz = z + (tz - z) * t;
            BlockPos check = new BlockPos((int) Math.floor(sx), y, (int) Math.floor(sz));
            if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void renderTick(LocalPlayer player) {
        long now = System.nanoTime();
        long elapsed = now - lastRenderNano;
        lastRenderNano = now;

        // Scale smoothing so it behaves the same as 60 ticks/sec regardless of FPS
        float scale = (float) (elapsed / TARGET_FRAME_NS);
        scale = Math.min(scale, 3f); // cap to avoid huge jumps on lag spikes

        float newYaw = smoothYaw(player.getYRot(), targetYaw, scale);
        float newPitch = smoothPitch(player.getXRot(), targetPitch, scale);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.setXRot(newPitch);
    }

    private static void stopWithReason(LocalPlayer player, String reason) {
        player.displayClientMessage(
                Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Script stopped (" + reason + ")"), false
        );
        stop();
    }

    private static void tick(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();

        // Stop if a screen is opened (inventory, chat, etc.)
        if (mc.screen != null) {
            stopWithReason(player, "screen opened");
            return;
        }

        if (currentNode >= path.size()) {
            player.displayClientMessage(
                    Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Path complete"), false
            );
            stop();
            return;
        }

        // Stuck detection: check if we've barely moved
        tickCounter++;
        if (tickCounter >= STUCK_CHECK_INTERVAL) {
            tickCounter = 0;
            double movedX = player.getX() - lastX;
            double movedY = player.getY() - lastY;
            double movedZ = player.getZ() - lastZ;
            double distMoved = Math.sqrt(movedX * movedX + movedY * movedY + movedZ * movedZ);

            if (distMoved < STUCK_THRESHOLD) {
                stuckTicks += STUCK_CHECK_INTERVAL;
            } else {
                stuckTicks = 0;
            }

            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();

            if (stuckTicks >= STUCK_REPATH_TICKS) {
                stuckTicks = 0;
                repath(player);
                return;
            }
        }

        releaseAllKeys();

        // Check if the path is currently going upward
        boolean climbing = currentNode < path.size()
                && path.get(currentNode).getY() > player.getY() + 0.2;
        boolean airborne = !player.onGround();

        // Scan ahead and skip to the furthest node we've reached
        // Works for flat, climbing, and airborne states
        int scanLimit = Math.min(path.size(), currentNode + 15);
        int furthestReached = -1;
        for (int i = currentNode; i < scanLimit; i++) {
            BlockPos node = path.get(i);
            double ndx = node.getX() + 0.5 - player.getX();
            double ndz = node.getZ() + 0.5 - player.getZ();
            double ndy = node.getY() - player.getY();
            double horizDist = Math.sqrt(ndx * ndx + ndz * ndz);

            if (airborne) {
                // Airborne: permissive scan — skip any node the player is above/at and near
                if (horizDist < REACH_DISTANCE && ndy <= 1.0) {
                    furthestReached = i;
                }
            } else if (climbing) {
                // Climbing: player must be at or above the node and horizontally close
                if (horizDist < REACH_DISTANCE && ndy <= 0.5) {
                    furthestReached = i;
                }
            } else {
                // Flat: stop scanning past Y changes to avoid skipping jump/fall nodes
                if (node.getY() != path.get(currentNode).getY()) break;
                if (horizDist < REACH_DISTANCE && Math.abs(ndy) < 1.5) {
                    furthestReached = i;
                }
            }
        }
        if (furthestReached >= currentNode) {
            currentNode = furthestReached + 1;
        }

        if (currentNode >= path.size()) {
            player.displayClientMessage(
                    Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Path complete"), false
            );
            stop();
            return;
        }

        BlockPos target = path.get(currentNode);
        double targetY = target.getY();

        // Aim a few nodes ahead for smoother movement
        // Allows up to 2 blocks of Y difference for diagonal/slope aiming
        int aimNode = currentNode;
        for (int i = currentNode + 1; i < Math.min(path.size(), currentNode + 5); i++) {
            if (Math.abs(path.get(i).getY() - targetY) > 2) break;

            // Check the path doesn't reverse direction (sign of going around a wall)
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            if (i >= 2) {
                BlockPos beforePrev = path.get(i - 2);
                int prevDirX = prev.getX() - beforePrev.getX();
                int prevDirZ = prev.getZ() - beforePrev.getZ();
                int currDirX = curr.getX() - prev.getX();
                int currDirZ = curr.getZ() - prev.getZ();
                // Dot product < 0 means direction reversed (U-turn around obstacle)
                if (prevDirX * currDirX + prevDirZ * currDirZ < 0) break;
            }

            // Check line of sight — don't aim through walls
            if (!hasLineOfSight(player, path.get(i))) break;

            aimNode = i;
        }

        BlockPos aimTarget = path.get(aimNode);
        double dx = aimTarget.getX() + 0.5 - player.getX();
        double dy = aimTarget.getY() - player.getY();
        double dz = aimTarget.getZ() + 0.5 - player.getZ();

        // Compute target angles — actual rotation is applied in renderTick()
        targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));

        // Keep pitch neutral while airborne so head doesn't snap down to nodes below
        if (!player.onGround()) {
            targetPitch = 0f;
        } else {
            targetPitch = (float) (Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        }

        // Only walk forward if we're roughly facing the target direction
        // This prevents sliding along walls while rotating
        float yawDiff = targetYaw - player.getYRot();
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        if (Math.abs(yawDiff) < FORWARD_ANGLE_THRESHOLD) {
            // Walk forward
            mc.options.keyUp.setDown(true);

            // Jump if current target node is above player
            if (targetY > player.getY() + 0.5) {
                mc.options.keyJump.setDown(true);
            }

            // Sprint on flat stretches with room ahead
            if (aimNode > currentNode + 2) {
                mc.options.keySprint.setDown(true);
            }
        }
    }
}