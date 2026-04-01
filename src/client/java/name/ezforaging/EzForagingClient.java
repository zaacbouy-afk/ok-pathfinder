package name.ezforaging;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import name.ezforaging.pathfinding.EzForagingPathfinder;
import name.ezforaging.pathfinding.PathfinderAction;
import name.ezforaging.pathfinding.PathRenderer;

import java.util.List;

public class EzForagingClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PathRenderer.register();
		PathfinderAction.register();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("ezforaging")
				.executes(context -> {
					context.getSource().getClient().execute(() -> {
						Minecraft.getInstance().setScreen(
							new EzForagingScreen(Component.empty())
						);
					});
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

									List<BlockPos> path = EzForagingPathfinder.findPath(level, playerPos, target, 50000);

									if (path.isEmpty()) {
										Minecraft.getInstance().player.displayClientMessage(
											Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("No path found"), false
										);
									} else {
										PathRenderer.setPath(path);
										PathfinderAction.start(path);
										Minecraft.getInstance().player.displayClientMessage(
											Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Following path " + path.size() + " blocks"), false
										);
									}
									return Command.SINGLE_SUCCESS;
								})
							)
						)
					)
				)
				.then(ClientCommandManager.literal("stop")
					.executes(context -> {
						PathfinderAction.stop();
						PathRenderer.clearPath();
						Minecraft.getInstance().player.displayClientMessage(
							Component.empty().append(Component.literal("[ezForaging] ").withStyle(style -> style.withBold(true).withColor(ChatFormatting.DARK_GREEN))).append("Stopped following path"), false
						);
						return Command.SINGLE_SUCCESS;
					})
				)
			);
		});
	}
}
