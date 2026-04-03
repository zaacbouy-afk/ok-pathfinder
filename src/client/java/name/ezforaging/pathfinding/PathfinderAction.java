package name.ezforaging.pathfinding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes path-following by controlling player movement keys each tick.
 * Handles stuck detection, automatic repathing with blacklisting, and
 * smooth camera rotation interpolated every render frame.
 */
public class PathfinderAction {

    // ── State ──────────────────────────────────────────────────────────
    private static List<BlockPos> path = null;
    private static int currentNode = 0;
    public static boolean active = false;

    // ── Rotation constants ─────────────────────────────────────────────
    private static final float SMOOTH_SPEED_MIN = 0.1f;   // minimum rotation lerp speed
    private static final float MAX_PITCH_PER_FRAME = 2f;   // cap vertical rotation per frame

    // ── Stuck detection ────────────────────────────────────────────────
    private static BlockPos destination = null;
    private static double lastX, lastY, lastZ;              // position snapshot for movement check
    private static int stuckTicks = 0;                      // how many ticks we've been stuck
    private static final int STUCK_CHECK_INTERVAL = 10;     // check movement every 10 ticks (0.5s)
    private static int tickCounter = 0;                     // counts ticks between stuck checks
    private static int repathAttempts = 0;                  // how many times we've repathed this segment
    private static double repathOriginX, repathOriginY, repathOriginZ; // position when last repath fired
    private static int repathGraceTicks = 0;                // ticks to skip node-scan after repath
    private static int repathRecoveryTicks = 0;             // ticks to follow a new repath conservatively
    private static final Set<BlockPos> blacklist = new HashSet<>(); // positions excluded from pathfinding

    // ── Camera smoothing ───────────────────────────────────────────────
    private static float targetYaw = 0f;                    // desired yaw, set in tick()
    private static float targetPitch = 0f;                  // desired pitch, set in tick()
    private static long lastRenderNano = 0;                 // timestamp of last render frame

    // ── Debug state ────────────────────────────────────────────────────
    private static int lastDebugAimNode = -1;               // tracks aimNode changes for debug logging
    private static int debugYawGateTick = 0;                // throttle counter for yaw-gate messages
    private static final double TARGET_FRAME_NS = 1_000_000_000.0 / 60.0; // baseline 60fps for scaling

    // ── Lifecycle ──────────────────────────────────────────────────────

    /** Registers the tick handler that drives path following. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || path == null || client.player == null) return;
            tick(client.player);
        });
    }

    /** Begins following a new path. Resets all state. */
    public static void start(List<BlockPos> newPath) {
        path = newPath;
        active = true;
        currentNode = 0;
        stuckTicks = 0;
        tickCounter = 0;
        repathAttempts = 0;
        repathGraceTicks = 0;
        repathRecoveryTicks = 0;
        blacklist.clear();
        lastDebugAimNode = -1;
        debugYawGateTick = 0;

        if (newPath != null && !newPath.isEmpty()) {
            destination = newPath.get(newPath.size() - 1);
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            sendMessage(player, "Path started: " + (newPath != null ? newPath.size() : 0) + " nodes → "
                    + (destination != null ? destination.getX() + " " + destination.getY() + " " + destination.getZ() : "?"));
        }
    }

    /** Stops path following and releases all held keys. */
    public static void stop() {
        active = false;
        path = null;
        currentNode = 0;
        destination = null;
        stuckTicks = 0;
        tickCounter = 0;
        repathAttempts = 0;
        repathGraceTicks = 0;
        repathRecoveryTicks = 0;
        blacklist.clear();
        releaseAllKeys();
    }

    public static boolean isActive() { return active; }
    public static int getCurrentNode() { return currentNode; }

    /** Releases all movement keys so the player stops. */
    public static void releaseAllKeys() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    // ── Camera smoothing ───────────────────────────────────────────────

    /**
     * Smoothly interpolates yaw toward the target angle.
     * Speed scales with how far off we are — fast for big corrections, gentle for small ones.
     * @param scale frame-time multiplier so rotation is FPS-independent
     */
    private static float smoothYaw(float current, float target, float scale) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;

        float absDiff = Math.abs(diff);
        float speed = SMOOTH_SPEED_MIN + (PathfinderConfig.smoothSpeedMax - SMOOTH_SPEED_MIN) * Math.min(absDiff / 90f, 1f);
        float delta = diff * speed * scale;

        float maxYaw = PathfinderConfig.maxYawPerFrame * scale;
        delta = Math.max(-maxYaw, Math.min(maxYaw, delta));
        return current + delta;
    }

    /** Same as smoothYaw but for pitch, with a tighter divisor. */
    private static float smoothPitch(float current, float target, float scale) {
        float diff = target - current;
        float absDiff = Math.abs(diff);
        float speed = SMOOTH_SPEED_MIN + (PathfinderConfig.smoothSpeedMax - SMOOTH_SPEED_MIN) * Math.min(absDiff / 45f, 1f);
        float delta = diff * speed * scale;

        float maxPitch = MAX_PITCH_PER_FRAME * scale;
        delta = Math.max(-maxPitch, Math.min(maxPitch, delta));
        return current + delta;
    }

    /**
     * Called every render frame (not game tick) to smoothly rotate the camera
     * toward the target angles computed in tick().
     */
    public static void renderTick(LocalPlayer player) {
        long now = System.nanoTime();
        long elapsed = now - lastRenderNano;
        lastRenderNano = now;

        // Scale so rotation behaves the same regardless of FPS
        float scale = (float) (elapsed / TARGET_FRAME_NS);
        scale = Math.min(scale, 3f); // cap to avoid huge jumps on lag spikes

        float newYaw = smoothYaw(player.getYRot(), targetYaw, scale);
        float newPitch = smoothPitch(player.getXRot(), targetPitch, scale);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.setXRot(newPitch);
    }

    // ── Repath ─────────────────────────────────────────────────────────

    /**
     * Attempts to find a new path around the obstacle the player is stuck on.
     * Blacklists the stuck position and current target node so the pathfinder
     * avoids routing through them. Uses more iterations on the final attempt.
     */
    private static void repath(LocalPlayer player) {
        if (destination == null || Minecraft.getInstance().level == null) return;

        // Only reset repath counter if we've genuinely moved closer to the destination
        // (raw distance moved lets wall-oscillation reset the counter forever)
        double prevDistToDest = distanceTo(repathOriginX, repathOriginY, repathOriginZ, destination);
        double currDistToDest = distanceTo(player.getX(), player.getY(), player.getZ(), destination);
        if (prevDistToDest - currDistToDest > 3.0) {
            repathAttempts = 0;
        }

        repathAttempts++;
        if (repathAttempts > PathfinderConfig.maxRepathAttempts) {
            stopWithReason(player, "stuck, unable to repath");
            return;
        }

        // Snapshot current position as the new repath origin
        repathOriginX = player.getX();
        repathOriginY = player.getY();
        repathOriginZ = player.getZ();

        // Blacklist the node we're stuck trying to reach
        if (path != null && currentNode < path.size()) {
            blacklist.add(path.get(currentNode));
        }

        // Also blacklist the player's rounded block position
        BlockPos stuckPos = new BlockPos(
                (int) Math.round(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.round(player.getZ())
        );
        blacklist.add(stuckPos);

        sendMessage(player, "Blacklisted " + stuckPos.getX() + " " + stuckPos.getY() + " " + stuckPos.getZ());

        // Last attempt gets 4x more iterations to find longer detours
        int iterations = (repathAttempts == PathfinderConfig.maxRepathAttempts) ? 200000 : 50000;
        Level repathLevel = Minecraft.getInstance().level;
        BlockPos repathStart = EzForagingPathfinder.getStartPos(repathLevel, player.blockPosition());
        List<BlockPos> newPath = EzForagingPathfinder.findPath(
                repathLevel, repathStart, destination, iterations, blacklist
        );

        sendMessage(player, "Pathfinder: " + EzForagingPathfinder.lastSearchMs + "ms"
                + ", " + EzForagingPathfinder.lastIterations + " iters"
                + " | Cache: +" + EzForagingPathfinder.lastCacheAdded + " new blocks"
                + " (" + EzForagingPathfinder.lastCacheTotal + " total)");

        if (newPath.isEmpty()) {
            sendMessage(player, "No path found, stopping");
            stop();
            return;
        }

        // Apply new path
        path = newPath;
        currentNode = 0;
        stuckTicks = 0;
        tickCounter = 0;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();

        // Advance past nodes the player is already standing in — but no further.
        // The grace period ensures the player turns to face the first detour node
        // before the full scan resumes.
        while (currentNode < newPath.size() - 1 && sameBlockColumn(player, newPath.get(currentNode))) {
            currentNode++;
        }
        repathGraceTicks = 5;
        repathRecoveryTicks = 20;

        PathRenderer.setPath(newPath);
        sendMessage(player, "Repathing " + newPath.size() + " blocks");
    }

    // ── Line of sight ──────────────────────────────────────────────────

    /**
     * Horizontal raytrace from the player to a target block.
     * Samples every 0.5 blocks and checks for solid collision at the target's Y level.
     * Used to prevent aiming/skipping through walls.
     */
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

    // ── Main tick ──────────────────────────────────────────────────────

    private static void stopWithReason(LocalPlayer player, String reason) {
        sendMessage(player, "Script stopped (" + reason + ")");
        stop();
    }

    /** Main tick — runs once per game tick while path following is active. */
    private static void tick(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();

        // Abort if a GUI screen is open (inventory, chat, etc.)
        if (mc.screen != null) {
            stopWithReason(player, "screen opened");
            return;
        }

        // Check if we've reached the end of the path
        if (currentNode >= path.size()) {
            sendMessage(player, "Path complete");
            stop();
            return;
        }

        // ── Stuck detection ────────────────────────────────────────────
        // Every STUCK_CHECK_INTERVAL ticks, check if we've moved enough.
        // If stuck for stuckRepathTicks, trigger a repath.
        tickCounter++;
        if (tickCounter >= STUCK_CHECK_INTERVAL) {
            tickCounter = 0;
            double distMoved = distanceTo(player.getX(), player.getY(), player.getZ(), lastX, lastY, lastZ);

            if (distMoved < PathfinderConfig.stuckThreshold) {
                stuckTicks += STUCK_CHECK_INTERVAL;
                sendMessage(player, "Stuck: " + stuckTicks + "/" + PathfinderConfig.stuckRepathTicks
                        + " ticks (moved " + String.format("%.2f", distMoved) + " blocks)");
            } else {
                stuckTicks = 0;
            }

            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();

            if (stuckTicks >= PathfinderConfig.stuckRepathTicks) {
                stuckTicks = 0;
                repath(player);
                return;
            }
        }

        releaseAllKeys();

        // ── Repath grace period ────────────────────────────────────────
        // After repath, pause node-scanning for a few ticks so the player
        // can turn toward the detour before the scan eats the nodes.
        if (repathGraceTicks > 0) {
            repathGraceTicks--;
        }
        if (repathRecoveryTicks > 0 && repathGraceTicks <= 0 && movedAwayFromRepathOrigin(player)) {
            repathRecoveryTicks = 0;
            sendMessage(player, "Repath recovery ended (moved away from origin)");
        } else if (repathRecoveryTicks > 0) {
            repathRecoveryTicks--;
        }
        boolean repathRecovering = repathRecoveryTicks > 0;

        boolean climbing = currentNode < path.size()
                && path.get(currentNode).getY() > player.getY() + 0.2;
        boolean airborne = !player.onGround();

        // ── Node scan ──────────────────────────────────────────────────
        // Scans ahead up to 15 nodes and advances currentNode past any
        // we've already reached. Skipped during repath grace period.
        if (repathGraceTicks <= 0) {
            int prevNode = currentNode;
            int scanWindow = repathRecovering ? 2 : 6;
            int scanLimit = Math.min(path.size(), currentNode + scanWindow);
            int furthestReached = -1;

            for (int i = currentNode; i < scanLimit; i++) {
                if (i == path.size() - 1) continue; // last node requires standing on it
                BlockPos node = path.get(i);
                double ndx = node.getX() + 0.5 - player.getX();
                double ndz = node.getZ() + 0.5 - player.getZ();
                double ndy = node.getY() - player.getY();
                double horizDist = Math.sqrt(ndx * ndx + ndz * ndz);

                if (!hasLineOfSight(player, node)) continue;

                if (airborne) {
                    if (horizDist < PathfinderConfig.reachDistance && ndy <= 1.0) {
                        furthestReached = i;
                    }
                } else if (climbing) {
                    if (horizDist < PathfinderConfig.reachDistance && ndy <= 0.5) {
                        furthestReached = i;
                    }
                } else {
                    // Flat: stop at Y changes to avoid skipping jump/fall nodes
                    if (node.getY() != path.get(currentNode).getY()) break;
                    if (horizDist < PathfinderConfig.reachDistance && Math.abs(ndy) < 1.5) {
                        furthestReached = i;
                    }
                }
            }

            if (furthestReached >= currentNode) {
                currentNode = furthestReached + 1;
            }

            // ── Skip-behind check ──────────────────────────────────────
            // If the next node is closer than the current one, we've overshot —
            // advance past it. Requires line of sight to prevent skipping through walls.
            int preSkipNode = currentNode;
            if (!repathRecovering && !hasSharpTurnAt(currentNode)) {
                while (currentNode + 1 < path.size() && currentNode + 1 != path.size() - 1) {
                    BlockPos curr = path.get(currentNode);
                    BlockPos next = path.get(currentNode + 1);
                    if (!hasLineOfSight(player, next)) break;
                    if (!isNodeAhead(player, next)) break;
                    if (hasSharpTurnAt(currentNode + 1)) break;

                    double distCurr = player.distanceToSqr(curr.getX() + 0.5, curr.getY(), curr.getZ() + 0.5);
                    double distNext = player.distanceToSqr(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
                    if (distNext < distCurr) {
                        currentNode++;
                    } else {
                        break;
                    }
                }
            }

            if (PathfinderConfig.debugEnabled && currentNode != prevNode) {
                String how = (currentNode > preSkipNode && preSkipNode > prevNode)
                        ? " (scan+" + (preSkipNode - prevNode) + ", skip+" + (currentNode - preSkipNode) + ")"
                        : (currentNode > preSkipNode ? " (skip-behind)" : " (scan)");
                sendMessage(player, "Node " + prevNode + " → " + currentNode + "/" + (path.size() - 1) + how);
            }
        }

        // ── Last node check ────────────────────────────────────────────
        // Final node requires the player to be standing in its block column.
        if (currentNode == path.size() - 1) {
            BlockPos lastNode = path.get(currentNode);
            BlockPos playerBlock = player.blockPosition();
            if (playerBlock.getX() == lastNode.getX()
                    && playerBlock.getZ() == lastNode.getZ()
                    && Math.abs(lastNode.getY() - player.getY()) < 1.5) {
                currentNode++;
            }
        }

        if (currentNode >= path.size()) {
            sendMessage(player, "Path complete");
            stop();
            return;
        }

        BlockPos target = path.get(currentNode);
        double targetY = target.getY();
        Level level = mc.level;

        // ── Aim-ahead (lookahead) ──────────────────────────────────────
        // Look up to 15 nodes ahead and aim at the furthest one we can see.
        // Stops on direction reversals (U-turns around walls) or blocked sight.
        int aimNode = currentNode;
        int aimWindow = repathRecovering ? 2 : 4;
        for (int i = currentNode + 1; i < Math.min(path.size(), currentNode + aimWindow); i++) {
            // Direction reversal check: if the path does a U-turn, stop looking ahead
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            if (curr.getY() != prev.getY()) break;
            if (i >= 2) {
                BlockPos beforePrev = path.get(i - 2);
                int prevDirX = prev.getX() - beforePrev.getX();
                int prevDirZ = prev.getZ() - beforePrev.getZ();
                int currDirX = curr.getX() - prev.getX();
                int currDirZ = curr.getZ() - prev.getZ();
                if (prevDirX * currDirX + prevDirZ * currDirZ <= 0) break;
            }

            if (!hasLineOfSight(player, path.get(i))) break;
            aimNode = i;
        }

        if (PathfinderConfig.debugEnabled && aimNode != lastDebugAimNode) {
            BlockPos a = path.get(aimNode);
            sendMessage(player, "Aim → node " + aimNode + " (" + a.getX() + "," + a.getY() + "," + a.getZ() + ")"
                    + (aimNode == currentNode ? " [no lookahead]" : " [+" + (aimNode - currentNode) + "]"));
            lastDebugAimNode = aimNode;
        }

        // ── Compute target rotation ────────────────────────────────────
        BlockPos aimTarget = path.get(aimNode);

        double dx = aimTarget.getX() + 0.5 - player.getX();
        double dy = aimTarget.getY() - player.getY();
        double dz = aimTarget.getZ() + 0.5 - player.getZ();

        // Detect a direction change in the lookahead window.
        // On straight segments the player holds their committed heading — no camera drift.
        // Rotation only fires when the path actually turns, or when we're >15° off-course
        // (handles path start and large corrections after repathing).
        boolean turnInWindow = false;
        for (int i = Math.max(1, currentNode); i <= Math.min(aimNode + 1, path.size() - 2); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);
            if (curr.getY() != prev.getY() || curr.getY() != next.getY()) {
                turnInWindow = true;
                break;
            }
            if (Integer.signum(curr.getX() - prev.getX()) != Integer.signum(next.getX() - curr.getX())
             || Integer.signum(curr.getZ() - prev.getZ()) != Integer.signum(next.getZ() - curr.getZ())) {
                turnInWindow = true;
                break;
            }
        }

        float newYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        float yawChange = newYaw - targetYaw;
        while (yawChange > 180) yawChange -= 360;
        while (yawChange < -180) yawChange += 360;

        if ((turnInWindow || Math.abs(yawChange) >= 15.0f) && Math.abs(yawChange) >= 2.0f) {
            float yawMin = PathfinderConfig.yawBlendSpeed * 0.15f;
            float yawBlend = Math.min(Math.abs(yawChange) / 60f, 1f) * (PathfinderConfig.yawBlendSpeed - yawMin) + yawMin;
            targetYaw += yawChange * yawBlend;
            while (targetYaw > 180) targetYaw -= 360;
            while (targetYaw < -180) targetYaw += 360;
        }

        // Smoothly blend pitch toward the natural look-at angle each tick via EMA.
        // Ground: pitch scales proportionally to slope — gentle steps get low pitch,
        // steep climbs get full pitch, with no threshold snap.
        // Airborne: blend toward level gaze instead of hard-assigning 0.
        {
            float idealPitch;
            if (!player.onGround()) {
                idealPitch = 0f;
            } else {
                double horizDist = Math.sqrt(dx * dx + dz * dz);
                double vertAngleDeg = Math.toDegrees(Math.atan2(Math.abs(dy), Math.max(horizDist, 0.1)));
                float rawPitch = (float) (Math.atan2(-dy, horizDist) * (180.0 / Math.PI));
                double pitchScale = Math.min(vertAngleDeg / 25.0, 1.0);
                idealPitch = (float) (rawPitch * pitchScale);
            }
            targetPitch += (idealPitch - targetPitch) * PathfinderConfig.pitchBlendSpeed;
        }

        // ── Movement ───────────────────────────────────────────────────
        // Only walk forward when roughly facing the target to prevent wall-sliding
        float yawDiff = targetYaw - player.getYRot();
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        if (Math.abs(yawDiff) < PathfinderConfig.forwardAngleThreshold) {
            mc.options.keyUp.setDown(true);
            debugYawGateTick = 0;

            // ── Jump logic ─────────────────────────────────────────────
            // Jump when the target node is a full block above the player.
            // Exceptions: bottom slabs (walkable at half height, no jump needed)
            // and stairs approached from the front (walkable side, no jump needed).
            BlockPos belowTarget = new BlockPos(target.getX(), target.getY() - 1, target.getZ());
            double effectiveSurfaceY = targetY;

            if (level != null && level.getBlockState(belowTarget).getBlock() instanceof SlabBlock
                    && level.getBlockState(belowTarget).getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                effectiveSurfaceY = targetY - 0.5;
            }

            if (player.onGround() && effectiveSurfaceY > player.getY() + 0.8) {
                boolean shouldJump = true;

                if (level != null && level.getBlockState(belowTarget).getBlock() instanceof StairBlock) {
                    Direction stairFacing = level.getBlockState(belowTarget).getValue(StairBlock.FACING);
                    double adx = target.getX() + 0.5 - player.getX();
                    double adz = target.getZ() + 0.5 - player.getZ();
                    Direction approachDir = Math.abs(adx) > Math.abs(adz)
                            ? (adx > 0 ? Direction.EAST : Direction.WEST)
                            : (adz > 0 ? Direction.SOUTH : Direction.NORTH);

                    // FACING points toward the stair's back; approaching from
                    // that direction means we're on the walkable front side
                    if (approachDir == stairFacing) {
                        shouldJump = false;
                    }
                }

                if (shouldJump) {
                    mc.options.keyJump.setDown(true);
                    sendMessage(player, "Jump: node " + currentNode
                            + " (surface " + String.format("%.2f", effectiveSurfaceY)
                            + ", feet " + String.format("%.2f", player.getY()) + ")");
                }
            }

            // Auto-jump: if there's a 1-block step directly in front, jump over it.
            // Guard: step block must be toward the current target, not a side wall the player grazed.
            if (player.onGround() && level != null) {
                double yawRad = Math.toRadians(player.getYRot());
                double fwdX = -Math.sin(yawRad);
                double fwdZ = Math.cos(yawRad);
                BlockPos stepBlock = new BlockPos(
                    (int) Math.floor(player.getX() + fwdX * 0.6),
                    player.blockPosition().getY(),
                    (int) Math.floor(player.getZ() + fwdZ * 0.6)
                );
                var stepShape = level.getBlockState(stepBlock).getCollisionShape(level, stepBlock);
                if (!stepShape.isEmpty()) {
                    double blockSurface = stepBlock.getY() + stepShape.max(net.minecraft.core.Direction.Axis.Y);
                    boolean requiresJump = blockSurface - player.getY() > 0.8;
                    boolean clearAboveStep = level.getBlockState(stepBlock.above()).getCollisionShape(level, stepBlock.above()).isEmpty();
                    boolean clearHeadRoom  = level.getBlockState(stepBlock.above().above()).getCollisionShape(level, stepBlock.above().above()).isEmpty();
                    // Only jump if the step block is actually toward the current target, not a side wall
                    double tdx = target.getX() + 0.5 - player.getX();
                    double tdz = target.getZ() + 0.5 - player.getZ();
                    double tDist = Math.sqrt(tdx * tdx + tdz * tdz);
                    double sDx = stepBlock.getX() + 0.5 - player.getX();
                    double sDz = stepBlock.getZ() + 0.5 - player.getZ();
                    double sDist = Math.sqrt(sDx * sDx + sDz * sDz);
                    boolean towardTarget = tDist < 0.5 || sDist < 0.1
                            || ((sDx / sDist) * (tdx / tDist) + (sDz / sDist) * (tdz / tDist)) > 0.6;
                    if (requiresJump && clearAboveStep && clearHeadRoom && towardTarget) {
                        mc.options.keyJump.setDown(true);
                        sendMessage(player, "Auto-jump: block at " + stepBlock.getX() + "," + stepBlock.getY() + "," + stepBlock.getZ()
                                + " surface " + String.format("%.2f", blockSurface) + ", feet " + String.format("%.2f", player.getY()));
                    }
                }
            }

            // Cut sprint only if a drop is directly 1-2 blocks ahead in the facing direction.
            // The pathfinder already avoids edges — this is just a last-resort safety net.
            boolean edgeAhead = false;
            if (player.onGround() && level != null) {
                double yawRad2 = Math.toRadians(player.getYRot());
                int mDirX = (int) Math.round(-Math.sin(yawRad2));
                int mDirZ = (int) Math.round( Math.cos(yawRad2));
                edgeAhead = EzForagingPathfinder.isApproachingEdge(level, player.blockPosition(), mDirX, mDirZ, 2);
            }
            mc.options.keySprint.setDown(!edgeAhead);
        } else {
            // Walking is gated — player hasn't turned far enough yet
            debugYawGateTick++;
            if (PathfinderConfig.debugEnabled && debugYawGateTick % 5 == 1) {
                sendMessage(player, "Turning: yaw diff " + String.format("%.1f", Math.abs(yawDiff))
                        + "° (target " + String.format("%.1f", targetYaw)
                        + "°, facing " + String.format("%.1f", player.getYRot()) + "°)");
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static double distanceTo(double x, double y, double z, BlockPos pos) {
        return Math.sqrt(Math.pow(x - pos.getX(), 2) + Math.pow(y - pos.getY(), 2) + Math.pow(z - pos.getZ(), 2));
    }

    private static double distanceTo(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2));
    }

    private static boolean sameBlockColumn(LocalPlayer player, BlockPos pos) {
        BlockPos playerBlock = player.blockPosition();
        return playerBlock.getX() == pos.getX()
                && playerBlock.getZ() == pos.getZ()
                && Math.abs(pos.getY() - player.getY()) < 1.5;
    }

    private static boolean movedAwayFromRepathOrigin(LocalPlayer player) {
        return distanceTo(
                player.getX(), player.getY(), player.getZ(),
                repathOriginX, repathOriginY, repathOriginZ
        ) > 1.75;
    }

    private static boolean isNodeAhead(LocalPlayer player, BlockPos node) {
        double dx = node.getX() + 0.5 - player.getX();
        double dz = node.getZ() + 0.5 - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist < 0.2) return true;

        double yawRad = Math.toRadians(player.getYRot());
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double dot = (dx / horizDist) * fwdX + (dz / horizDist) * fwdZ;
        return dot > 0.15;
    }

    private static boolean hasSharpTurnAt(int index) {
        if (path == null || index < 0 || index + 2 >= path.size()) return false;

        BlockPos a = path.get(index);
        BlockPos b = path.get(index + 1);
        BlockPos c = path.get(index + 2);
        if (a.getY() != b.getY() || b.getY() != c.getY()) return true;

        int abX = Integer.compare(b.getX(), a.getX());
        int abZ = Integer.compare(b.getZ(), a.getZ());
        int bcX = Integer.compare(c.getX(), b.getX());
        int bcZ = Integer.compare(c.getZ(), b.getZ());
        return abX != bcX || abZ != bcZ;
    }

    private static void sendMessage(LocalPlayer player, String text) {
        if (!PathfinderConfig.debugEnabled) return;
        player.displayClientMessage(
                Component.empty()
                        .append(Component.literal("[ezForaging] ").withStyle(s -> s.withBold(true).withColor(ChatFormatting.DARK_GREEN)))
                        .append(text),
                false
        );
    }
}
