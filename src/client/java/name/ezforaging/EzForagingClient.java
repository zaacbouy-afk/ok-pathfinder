package name.ezforaging;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import name.ezforaging.pathfinding.EzForagingPathfinder;
import name.ezforaging.pathfinding.PathfinderAction;
import name.ezforaging.pathfinding.PathfinderConfig;
import name.ezforaging.pathfinding.PathRenderer;

import java.util.List;
import java.util.ArrayList;

/**
 * Mod entry point — runs once when the client initialises.
 * Responsible for:
 *   - Loading saved config values from disk
 *   - Registering the path renderer and pathfinder tick handler
 *   - Registering all /ezforaging client commands
 */
public class EzForagingClient implements ClientModInitializer {

    // Cached path — stored by the `cache` command, reused by `start` if destination matches
    private static BlockPos cachedDestination = null;
    private static List<BlockPos> cachedPath = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        // Load persisted settings before anything else uses PathfinderConfig
        PathfinderConfig.load();

        // Register the debug path visualiser and the per-tick movement controller
        PathRenderer.register();
        PathfinderAction.register();

        // ── Commands ───────────────────────────────────────────────────
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(createRootCommand("ezforaging"));
            dispatcher.register(createRootCommand("tung"));
            dispatcher.register(createRootCommand("tungtung"));
            dispatcher.register(createRootCommand("tungtungsahur"));
            dispatcher.register(createRootCommand("riptung"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createRootCommand(String name) {
        return ClientCommandManager.literal(name)

            .executes(context -> {
                context.getSource().getClient().execute(() ->
                    Minecraft.getInstance().setScreen(
                        new EzForagingScreen(Component.empty())
                    )
                );
                return Command.SINGLE_SUCCESS;
            })

            .then(ClientCommandManager.literal("cache")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                .executes(context -> {
                    int x = IntegerArgumentType.getInteger(context, "x");
                    int y = IntegerArgumentType.getInteger(context, "y");
                    int z = IntegerArgumentType.getInteger(context, "z");

                    Level level = Minecraft.getInstance().level;
                    BlockPos playerPos = EzForagingPathfinder.getStartPos(level, Minecraft.getInstance().player.blockPosition());
                    BlockPos target = EzForagingPathfinder.getStartPos(level, new BlockPos(x, y, z));

                    Minecraft.getInstance().player.displayClientMessage(
                        prefix().append(Component.literal("Computing path to " + target.getX() + " " + target.getY() + " " + target.getZ() + "...")), false
                    );

                    List<BlockPos> path = EzForagingPathfinder.findPath(level, playerPos, target, 500000);

                    if (path.isEmpty()) {
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("No path found — nothing cached")), false
                        );
                    } else {
                        EzForagingPathfinder.prewarmCache(level, path);
                        cachedDestination = target;
                        cachedPath = path;
                        PathRenderer.setPath(path);
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("Cached " + path.size() + " nodes → "
                                + x + " " + y + " " + z)), false
                        );
                    }
                    return Command.SINGLE_SUCCESS;
                }))))
            )

            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                .executes(context -> {
                    int x = IntegerArgumentType.getInteger(context, "x");
                    int y = IntegerArgumentType.getInteger(context, "y");
                    int z = IntegerArgumentType.getInteger(context, "z");

                    Level level = Minecraft.getInstance().level;
                    BlockPos playerPos = EzForagingPathfinder.getStartPos(level, Minecraft.getInstance().player.blockPosition());
                    BlockPos target = EzForagingPathfinder.getStartPos(level, new BlockPos(x, y, z));

                    List<BlockPos> path = EzForagingPathfinder.findPath(level, playerPos, target, 500000);

                    if (path.isEmpty()) {
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("No path found — " + EzForagingPathfinder.lastFailureReason)), false
                        );
                    } else {
                        EzForagingPathfinder.prewarmCache(level, path);
                        cachedDestination = target;
                        cachedPath = path;
                        PathRenderer.setPath(path);
                        PathfinderAction.start(path);
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("Following path — " + path.size() + " nodes")), false
                        );
                    }
                    return Command.SINGLE_SUCCESS;
                }))))
            )

            .then(ClientCommandManager.literal("stop")
                .executes(context -> {
                    PathfinderAction.stop();
                    PathRenderer.clearPath();
                    Minecraft.getInstance().player.displayClientMessage(
                        prefix().append(Component.literal("Stopped following path")), false
                    );
                    return Command.SINGLE_SUCCESS;
                })
            );
    }

    /** Shared bold green [ezForaging] prefix used in chat messages. */
    private static net.minecraft.network.chat.MutableComponent prefix() {
        return Component.empty().append(
            Component.literal("[ezForaging] ")
                .withStyle(s -> s.withBold(true).withColor(ChatFormatting.DARK_GREEN))
        );
    }
}
