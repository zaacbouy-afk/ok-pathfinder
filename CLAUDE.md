# ezForaging Notes

## Repath wall-hugging bug

- Symptom: when the player got stuck against a wall, repath attempts 1-2 found a new path but the player kept wall-hugging as if the new path was ignored. The final repath attempt sometimes broke free only because the longer detour happened to survive the controller behavior.
- Root cause: the problem was in path adoption, not only path search. After `repath()` installed a new path, `PathfinderAction` still treated it like a mature path and immediately collapsed it:
  - repath setup could advance `currentNode` past nearby detour nodes
  - normal tick logic could quickly scan ahead and skip nodes
  - aim lookahead could steer several nodes ahead instead of through the escape segment
- Effect: the freshly generated detour was effectively bypassed, so movement kept steering back into the wall area.

## Fix implemented

- File: `src/client/java/name/ezforaging/pathfinding/PathfinderAction.java`
- Repath now resets `tickCounter` when applying the new path.
- After repath, node skipping is conservative:
  - only skip the exact start block column
  - do not proximity-skip multiple nearby nodes from the new path
- Added a short repath recovery window:
  - node scan window is reduced while escaping the stuck area
  - skip-behind collapsing is disabled during recovery
  - aim lookahead is reduced during recovery so the player follows the detour instead of cutting past it
- Recovery ends early once the player has moved away from the repath origin.

## If this regresses

- Re-check `repath()` and post-repath handling in `PathfinderAction.java`.
- Treat freshly repathed paths differently from stable paths.
- If the controller logic looks correct, then investigate path quality next:
  - blacklist coverage around the stuck area
  - wall proximity costs in `EzForagingPathfinder`
  - whether the detour path itself still routes too close to the obstacle
