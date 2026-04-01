package name.ezforaging.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
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
