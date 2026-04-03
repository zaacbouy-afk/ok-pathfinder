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

/**
 * Mod entry point — runs once when the client initialises.
 * Responsible for:
 *   - Loading saved config values from disk
 *   - Registering the path renderer and pathfinder tick handler
 *   - Registering all /ezforaging client commands
 */
public class EzForagingClient implements ClientModInitializer {

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

            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                .executes(context -> {
                    int x = IntegerArgumentType.getInteger(context, "x");
                    int y = IntegerArgumentType.getInteger(context, "y");
                    int z = IntegerArgumentType.getInteger(context, "z");

                    Level level = Minecraft.getInstance().level;
                    BlockPos playerPos = Minecraft.getInstance().player.blockPosition();
                    BlockPos target = new BlockPos(x, y, z);

                    List<BlockPos> path = EzForagingPathfinder.findPath(level, playerPos, target, 500000);

                    if (path.isEmpty()) {
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("cached route= false")), false
                        );
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("No path found")), false
                        );
                    } else {
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("caching...")), false
                        );
                        EzForagingPathfinder.prewarmCache(level, path);
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("cached route= true")), false
                        );
                        PathRenderer.setPath(path);
                        PathfinderAction.start(path);
                        Minecraft.getInstance().player.displayClientMessage(
                            prefix().append(Component.literal("Following path — " + path.size() + " blocks")), false
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
