package name.ezforaging.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;
import java.util.HashMap;

/**
 * A* pathfinder that finds walkable routes between two block positions.
 * Applies avoidance costs for walls, water, and drop-off edges so generated
 * paths prefer open, safe ground over tight corridors and cliff edges.
 */
public class EzForagingPathfinder {

    // ── A* node ────────────────────────────────────────────────────────

    private static class Node {
        BlockPos pos;
        Node parent;
        double gCost; // actual cost from the start
        double hCost; // heuristic estimate to the goal
        double fCost; // gCost + hCost (priority queue key)
        int dirX;     // normalized X direction from parent (-1, 0, 1)
        int dirZ;     // normalized Z direction from parent (-1, 0, 1)

        Node(BlockPos pos, Node parent, double gCost, double hCost, int dirX, int dirZ) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
            this.dirX  = dirX;
            this.dirZ  = dirZ;
        }
    }

    // ── Block cache ────────────────────────────────────────────────────
    // Caches three block properties per position as bit-flags in a single byte.
    // Survives across findPath calls for the same dimension — subsequent paths
    // over already-visited terrain pay zero block-lookup cost.
    // Cleared automatically on dimension change, or manually via clearCache().

    private static final byte BIT_PASSABLE = 1; // collision shape is empty
    private static final byte BIT_SOLID    = 2; // block.isSolid()
    private static final byte BIT_WATER    = 4; // has fluid

    private static final HashMap<Long, Byte> blockCache = new HashMap<>(16384);
    private static ResourceKey<Level> cachedDimension = null;

    // ── Last-search diagnostics (read by call sites for debug output) ───
    public static long lastSearchMs      = 0; // total findPath duration
    public static int  lastIterations    = 0; // A* iterations used
    public static int  lastPathNodes     = 0; // nodes in returned path
    public static int  lastCacheAdded    = 0; // new blocks cached this search
    public static int  lastCacheTotal    = 0; // total cached blocks after search

    private static void recordDiagnostics(long startNs, int cacheAtStart, int iters, int nodes) {
        lastSearchMs   = (System.nanoTime() - startNs) / 1_000_000L;
        lastIterations = iters;
        lastPathNodes  = nodes;
        lastCacheAdded = blockCache.size() - cacheAtStart;
        lastCacheTotal = blockCache.size();
    }

    /** Clears the block cache — call when you know blocks have changed. */
    public static void clearCache() {
        blockCache.clear();
        cachedDimension = null;
    }

    /**
     * Pre-warms the block cache for the area around every node in a path.
     * Caches a WALL_CLEARANCE-radius horizontal slice at each node's Y so that
     * repath searches over the same terrain pay zero block-lookup cost.
     * Call this after findPath succeeds, before starting movement.
     */
    public static void prewarmCache(Level level, List<BlockPos> path) {
        checkDimension(level);
        for (BlockPos node : path) {
            int y = node.getY();
            for (int dx = -WALL_CLEARANCE; dx <= WALL_CLEARANCE; dx++) {
                for (int dz = -WALL_CLEARANCE; dz <= WALL_CLEARANCE; dz++) {
                    BlockPos p = new BlockPos(node.getX() + dx, y, node.getZ() + dz);
                    lookup(level, p);
                    lookup(level, p.above());
                    lookup(level, p.below());
                }
            }
        }
    }

    private static void checkDimension(Level level) {
        ResourceKey<Level> dim = level.dimension();
        if (!dim.equals(cachedDimension)) {
            blockCache.clear();
            cachedDimension = dim;
        }
    }

    /** Returns cached bit-flags for pos, querying the level only on first access. */
    private static byte lookup(Level level, BlockPos pos) {
        long key = pos.asLong();
        Byte hit = blockCache.get(key);
        if (hit != null) return hit;
        BlockState state = level.getBlockState(pos);
        byte flags = 0;
        if (state.getCollisionShape(level, pos).isEmpty()) flags |= BIT_PASSABLE;
        if (state.isSolid())                               flags |= BIT_SOLID;
        if (!level.getFluidState(pos).isEmpty())           flags |= BIT_WATER;
        blockCache.put(key, flags);
        return flags;
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** Find a path with no blacklisted positions. */
    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos end, int maxIterations) {
        return findPath(level, start, end, maxIterations, Collections.emptySet());
    }

    /**
     * Core A* search. Blacklisted positions are treated as walls — the
     * pathfinder will never expand or route through them.
     *
     * @param maxIterations safety cap to prevent runaway searches
     * @param blacklist     positions to exclude (used by repath to avoid stuck spots)
     * @return ordered list of BlockPos from start to end, or empty if no path found
     */
    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos end,
                                          int maxIterations, Set<BlockPos> blacklist) {
        checkDimension(level);
        long searchStart   = System.nanoTime();
        int  cacheAtStart  = blockCache.size();

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparing(n -> n.fCost));
        Set<BlockPos> closedSet = new HashSet<>(blacklist); // blacklisted positions start "already visited"

        Map<BlockPos, Double> bestGCost = new HashMap<>();
        openSet.add(new Node(start, null, 0, heuristic(start, end), 0, 0));
        bestGCost.put(start, 0.0);

        int iterations = 0;
        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;
            Node current = openSet.poll();

            // Goal reached — reconstruct and return the path
            if (current.pos.equals(end)) {
                List<BlockPos> result = reconstructPath(current);
                recordDiagnostics(searchStart, cacheAtStart, iterations, result.size());
                return result;
            }

            closedSet.add(current.pos);

            // Expand neighbours
            for (BlockPos neighbour : getNeighbours(current.pos, level)) {
                if (closedSet.contains(neighbour)) continue;
                if (!isWalkable(level, current.pos, neighbour)) continue;

                int nDirX = Integer.signum(neighbour.getX() - current.pos.getX());
                int nDirZ = Integer.signum(neighbour.getZ() - current.pos.getZ());

                // Calculate movement cost with avoidance penalties
                int yDiff = Math.abs(neighbour.getY() - current.pos.getY());
                double moveCost = yDiff > 0 ? 1.0 + (yDiff * 0.5) : 1.0;
                moveCost += wallProximityCost(level, neighbour);
                moveCost += localHazardCost(level, neighbour);
                moveCost += waterCost(level, neighbour);
                moveCost += edgeProximityCost(level, neighbour);

                // Turn penalty: prefer straight paths over zigzag movement
                if ((current.dirX != 0 || current.dirZ != 0) && (nDirX != current.dirX || nDirZ != current.dirZ)) {
                    moveCost += 0.5;
                }

                double newGCost = current.gCost + moveCost;

                // Skip if we already found a cheaper route to this position
                if (bestGCost.containsKey(neighbour) && newGCost >= bestGCost.get(neighbour)) continue;
                bestGCost.put(neighbour, newGCost);

                openSet.add(new Node(neighbour, current, newGCost, heuristic(neighbour, end), nDirX, nDirZ));
            }
        }

        recordDiagnostics(searchStart, cacheAtStart, iterations, 0);
        return Collections.emptyList(); // no path found within iteration budget
    }

    // ── Avoidance costs ────────────────────────────────────────────────
    // Each cost function scans a radius around the candidate position and
    // returns the HIGHEST single penalty found (closest hazard wins).

    private static final int WALL_CLEARANCE = 4;
    private static final double WALL_PENALTY = 1.0;
    private static final double LOCAL_HAZARD_PENALTY = 3.5;
    private static final double CRAMPED_PENALTY = 0.25;

    /** Penalises positions near solid walls so paths prefer open areas. */
    private static double wallProximityCost(Level level, BlockPos pos) {
        double highestPenalty = 0.0;
        int y = pos.getY();

        for (int dx = -WALL_CLEARANCE; dx <= WALL_CLEARANCE; dx++) {
            for (int dz = -WALL_CLEARANCE; dz <= WALL_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > WALL_CLEARANCE) continue;

                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                if (!isPassable(level, check)) {
                    double penalty = WALL_PENALTY * (1.0 - dist / (WALL_CLEARANCE + 1));
                    if (penalty > highestPenalty) highestPenalty = penalty;
                }
            }
        }
        return highestPenalty;
    }

    /**
     * Scores cramped nodes using the same 3x3x3 neighborhood idea previously used
     * only by follow-time steering. This affects route choice directly.
     */
    private static double localHazardCost(Level level, BlockPos pos) {
        double penalty = 0.0;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockPos sample = pos.offset(dx, dy, dz);
                    double distSq = dx * dx + dy * dy + dz * dz;

                    // Hazard penalty for obstructions, drop-offs, and non-passable above/below
                    boolean isHazard;
                    if (dy == 0) {
                        isHazard = isObstructedColumn(level, sample) || isDropOff(level, sample);
                    } else {
                        isHazard = !isPassable(level, sample);
                    }
                    if (isHazard) penalty += LOCAL_HAZARD_PENALTY / distSq;

                    // Openness tiebreaker: any solid block in the 3×3×3 neighbourhood adds a
                    // small proximity penalty so open routes are preferred when costs are similar.
                    if ((lookup(level, sample) & BIT_SOLID) != 0) {
                        penalty += CRAMPED_PENALTY / distSq;
                    }
                }
            }
        }

        return penalty;
    }

    private static final int WATER_CLEARANCE = 3;
    private static final double WATER_PROXIMITY_PENALTY = 0.8;
    private static final double WATER_DIRECT_PENALTY = 5.0;

    /** Heavy penalty for standing in water, lighter penalty for being near it. */
    private static double waterCost(Level level, BlockPos pos) {
        // Direct contact — strong deterrent
        if (isWater(level, pos) || isWater(level, pos.below())) {
            return WATER_DIRECT_PENALTY;
        }

        // Proximity scan
        double highestPenalty = 0.0;
        int y = pos.getY();
        for (int dx = -WATER_CLEARANCE; dx <= WATER_CLEARANCE; dx++) {
            for (int dz = -WATER_CLEARANCE; dz <= WATER_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > WATER_CLEARANCE) continue;

                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                if ((lookup(level, check) & BIT_WATER) != 0) {
                    double penalty = WATER_PROXIMITY_PENALTY * (1.0 - dist / (WATER_CLEARANCE + 1));
                    if (penalty > highestPenalty) highestPenalty = penalty;
                }
            }
        }
        return highestPenalty;
    }

    private static final int EDGE_CLEARANCE = 6;
    private static final double EDGE_PENALTY = 28.0;
    private static final int DROP_THRESHOLD = 2; // 2+ block drop = dangerous edge

    /** Penalises positions near steep drop-offs to keep paths away from cliffs. */
    private static double edgeProximityCost(Level level, BlockPos pos) {
        double highestPenalty = 0.0;
        int y = pos.getY();

        for (int dx = -EDGE_CLEARANCE; dx <= EDGE_CLEARANCE; dx++) {
            for (int dz = -EDGE_CLEARANCE; dz <= EDGE_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > EDGE_CLEARANCE) continue;

                // Count how many blocks down until we hit solid ground
                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                int drop = 0;
                for (int d = 0; d < DROP_THRESHOLD + 1; d++) {
                    if ((lookup(level, check.below(d)) & BIT_SOLID) != 0) break;
                    drop++;
                }

                if (drop >= DROP_THRESHOLD) {
                    double penalty = EDGE_PENALTY * (1.0 - dist / (EDGE_CLEARANCE + 1));
                    if (penalty > highestPenalty) highestPenalty = penalty;
                }
            }
        }
        return highestPenalty;
    }

    // ── Heuristic & helpers ────────────────────────────────────────────

    /**
     * Weighted Manhattan distance heuristic.
     * Scaled to match the dominant cost on SkyBlock terrain. With EDGE_PENALTY=28,
     * most nodes cost 10–24 per step. A low multiplier leaves the heuristic far
     * below reality so A* degrades to near-Dijkstra and exhausts its iteration
     * budget covering only ~70 blocks. 15x keeps the estimate above typical node
     * costs, making the search strongly directional and able to cover long routes
     * within the iteration budget. Trades strict optimality for practical range.
     */
    private static double heuristic(BlockPos a, BlockPos b) {
        return (Math.abs(a.getX() - b.getX())
              + Math.abs(a.getY() - b.getY())
              + Math.abs(a.getZ() - b.getZ())) * 15.0;
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return (lookup(level, pos) & BIT_WATER) != 0;
    }

    private static boolean isObstructedColumn(Level level, BlockPos pos) {
        return (lookup(level, pos) & BIT_PASSABLE) == 0
                || (lookup(level, pos.above()) & BIT_PASSABLE) == 0;
    }

    private static boolean isDropOff(Level level, BlockPos pos) {
        for (int d = 1; d <= 3; d++) {
            if ((lookup(level, pos.below(d)) & BIT_SOLID) != 0) return false;
        }
        return true;
    }

    private static boolean isUnsafeEdgeNode(Level level, BlockPos pos) {
        BlockPos[] adjacent = { pos.north(), pos.south(), pos.east(), pos.west() };
        for (BlockPos check : adjacent) {
            if (isDropOff(level, check)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given position (or any block within `lookaheadBlocks`
     * steps in direction (dirX, dirZ)) is on a platform with an adjacent drop-off.
     * Used by PathfinderAction to cut sprint before the player slides off an edge.
     */
    public static boolean isApproachingEdge(Level level, BlockPos pos, int dirX, int dirZ, int lookaheadBlocks) {
        checkDimension(level);
        for (int i = 0; i <= lookaheadBlocks; i++) {
            BlockPos check = new BlockPos(pos.getX() + dirX * i, pos.getY(), pos.getZ() + dirZ * i);
            if (isUnsafeEdgeNode(level, check)) return true;
        }
        return false;
    }

    // ── Neighbour generation ───────────────────────────────────────────

    /**
     * Generates candidate neighbours for a position:
     * - 4 cardinal flat moves
     * - 4 cardinal step-ups (1 block higher)
     * - 4 cardinal drops (scan down up to 7 blocks to find a landing)
     */
    private static List<BlockPos> getNeighbours(BlockPos pos, Level level) {
        List<BlockPos> neighbors = new ArrayList<>();
        BlockPos[] horizontal = { pos.north(), pos.south(), pos.east(), pos.west() };

        // Flat movement
        for (BlockPos h : horizontal) {
            if (level.isLoaded(h)) neighbors.add(h);
        }

        // Step up (1 block)
        for (BlockPos h : horizontal) {
            if (level.isLoaded(h)) neighbors.add(h.above());
        }

        // Drop down — scan up to 7 blocks for a landing
        for (BlockPos h : horizontal) {
            if (!level.isLoaded(h)) continue;
            BlockPos landing = h;
            for (int drop = 1; drop <= 7; drop++) {
                landing = landing.below();
                if ((lookup(level, landing.below()) & BIT_SOLID) != 0) {
                    neighbors.add(landing);
                    break;
                }
            }
        }

        return neighbors;
    }

    // ── Walkability checks ─────────────────────────────────────────────

    /** Can the player pass through this block? (no collision shape) */
    private static boolean isPassable(Level level, BlockPos pos) {
        return (lookup(level, pos) & BIT_PASSABLE) != 0;
    }

    /** Can the player stand on this block? (has collision shape) */
    private static boolean isStandable(Level level, BlockPos pos) {
        return (lookup(level, pos) & BIT_PASSABLE) == 0;
    }

    /**
     * Checks whether a player can move from 'from' to 'to':
     * - Feet and head positions must be passable
     * - Must have solid ground below
     * - Extra headroom check for step-up/step-down transitions
     */
    private static boolean isWalkable(Level level, BlockPos from, BlockPos to) {
        if (!isPassable(level, to)) return false;
        if (!isPassable(level, to.above())) return false;
        if (!isStandable(level, to.below())) return false;

        // Headroom when stepping up
        if (to.getY() > from.getY()) {
            if (!isPassable(level, from.above().above())) return false;
        }

        // Headroom when stepping down
        if (to.getY() < from.getY()) {
            if (!isPassable(level, to.above().above())) return false;
        }

        // Block corner nodes (drops on both axes) but allow bridge centerlines.
        // N+S drops = bridge running E–W; E+W drops = bridge running N–S — both fine.
        // Drops on both axes simultaneously = exposed corner — block those.
        boolean nsAxisDrop = isDropOff(level, to.north()) || isDropOff(level, to.south());
        boolean ewAxisDrop = isDropOff(level, to.east())  || isDropOff(level, to.west());
        if (nsAxisDrop && ewAxisDrop) return false;

        return true;
    }

    // ── Path reconstruction ────────────────────────────────────────────

    /** Walks the parent chain from the goal node back to the start and reverses it. */
    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(node.pos);
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
