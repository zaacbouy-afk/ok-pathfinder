package name.ezforaging.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import java.util.*;

public class EzForagingPathfinder {
    private static class Node {
        BlockPos pos;
        Node parent;
        double gCost; //distance from start
        double hCost; //distance to end
        double fCost; //gCost+hCost

        Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent=parent;
            this.gCost=gCost;
            this.hCost=hCost;
            this.fCost=hCost+gCost;
        }
    }

    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos end, int maxIterations) {
        PriorityQueue<Node> openSet= new PriorityQueue<>(Comparator.comparing(node -> node.fCost));
        Set<BlockPos> closedSet = new HashSet<>();

        Map<BlockPos, Double> bestGCost = new HashMap<>();
        openSet.add(new Node(start, null,0,heuristic(start,end)));
        bestGCost.put(start, 0.0);
        int iterations = 0;
        while( !openSet.isEmpty() && iterations < maxIterations) {
            iterations++;
            Node current =openSet.poll();

            if(current.pos.equals(end)) {
                return reconstructPath(current);
            }

            closedSet.add(current.pos);

            for(BlockPos neighbour : getNeighbours(current.pos, level)) {
                if (closedSet.contains(neighbour)) continue;
                if (!isWalkable(level, current.pos, neighbour)) continue;

                int yDiff = Math.abs(neighbour.getY() - current.pos.getY());
                double moveCost = yDiff > 0 ? 1.0 + (yDiff * 0.5) : 1.0;
                moveCost += wallProximityCost(level, neighbour);
                moveCost += waterCost(level, neighbour);
                moveCost += edgeProximityCost(level, neighbour);
                double newGCost = current.gCost + moveCost;

                // Skip if we already found a better or equal path to this neighbour
                if (bestGCost.containsKey(neighbour) && newGCost >= bestGCost.get(neighbour)) continue;
                bestGCost.put(neighbour, newGCost);

                Node neighbourNode = new Node(neighbour, current, newGCost, heuristic(neighbour, end));
                openSet.add(neighbourNode);
            }
        }
        return Collections.emptyList();
    }
    private static final int WALL_CLEARANCE = 3;
    private static final double WALL_PENALTY = 0.5;
    private static final int WATER_CLEARANCE = 3;
    private static final double WATER_PROXIMITY_PENALTY = 0.8;
    private static final double WATER_DIRECT_PENALTY = 5.0;

    private static boolean isWater(Level level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        return !fluid.isEmpty();
    }

    private static double wallProximityCost(Level level, BlockPos pos) {
        int y = pos.getY();
        for (int dx = -WALL_CLEARANCE; dx <= WALL_CLEARANCE; dx++) {
            for (int dz = -WALL_CLEARANCE; dz <= WALL_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= WALL_CLEARANCE) {
                        return WALL_PENALTY * (1.0 - dist / (WALL_CLEARANCE + 1));
                    }
                }
            }
        }
        return 0.0;
    }

    private static double waterCost(Level level, BlockPos pos) {
        // Heavy penalty for standing in water
        if (isWater(level, pos) || isWater(level, pos.below())) {
            return WATER_DIRECT_PENALTY;
        }

        // Proximity penalty — prefer routes away from water
        int y = pos.getY();
        for (int dx = -WATER_CLEARANCE; dx <= WATER_CLEARANCE; dx++) {
            for (int dz = -WATER_CLEARANCE; dz <= WATER_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > WATER_CLEARANCE) continue;
                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                if (isWater(level, check)) {
                    return WATER_PROXIMITY_PENALTY * (1.0 - dist / (WATER_CLEARANCE + 1));
                }
            }
        }
        return 0.0;
    }

    private static final int EDGE_CLEARANCE = 3;
    private static final double EDGE_PENALTY = 3.0;
    private static final int DROP_THRESHOLD = 3; // a drop of 3+ blocks counts as a dangerous edge

    private static double edgeProximityCost(Level level, BlockPos pos) {
        double highestPenalty = 0.0;
        int y = pos.getY();
        for (int dx = -EDGE_CLEARANCE; dx <= EDGE_CLEARANCE; dx++) {
            for (int dz = -EDGE_CLEARANCE; dz <= EDGE_CLEARANCE; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > EDGE_CLEARANCE) continue;

                // Check if there's a significant drop at this nearby position
                BlockPos check = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                int drop = 0;
                for (int d = 0; d < DROP_THRESHOLD + 1; d++) {
                    if (level.getBlockState(check.below(d)).isSolid()) break;
                    drop++;
                }
                if (drop >= DROP_THRESHOLD) {
                    double penalty = EDGE_PENALTY * (1.0 - dist / (EDGE_CLEARANCE + 1));
                    if (penalty > highestPenalty) {
                        highestPenalty = penalty;
                    }
                }
            }
        }
        return highestPenalty;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX()-b.getX())
              +Math.abs(a.getY()-b.getY())
              +Math.abs(a.getZ()-b.getZ());
    }
    private static List<BlockPos> getNeighbours(BlockPos pos, Level level) {
        List<BlockPos> neighbors = new ArrayList<>();

        BlockPos[] horizontal = { pos.north(), pos.south(), pos.east(), pos.west() };

        // flat movement
        for (BlockPos h : horizontal) {
            neighbors.add(h);
        }

        // step up
        for (BlockPos h : horizontal) {
            neighbors.add(h.above());
        }

        // step down
        for (BlockPos h : horizontal) {
            BlockPos landing = h;
            for (int drop = 1; drop <= 7; drop++) {
                landing = landing.below();
                if (level.getBlockState(landing.below()).isSolid()) {
                    neighbors.add(landing);
                    break;
                }
            }
        }

        return neighbors;
    }
    // Can the player pass through this block?
    private static boolean isPassable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isStandable(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isWalkable(Level level, BlockPos from, BlockPos to) {
        // Feet and head must be passable
        if (!isPassable(level, to)) return false;
        if (!isPassable(level, to.above())) return false;

        // Must have ground to stand on
        if (!isStandable(level, to.below())) return false;

        // Headroom check for stepping up
        if (to.getY() > from.getY()) {
            if (!isPassable(level, from.above().above())) return false;
        }

        // Headroom check for stepping down
        if (to.getY() < from.getY()) {
            if (!isPassable(level, to.above().above())) return false;
        }

        return true;
    }
    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path =new ArrayList<>();
        while(node!=null) {
            path.add(node.pos);
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
