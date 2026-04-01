package name.ezforaging.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import name.ezforaging.pathfinding.PathfinderAction;
import name.ezforaging.pathfinding.PathRenderer;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (PathfinderAction.active && mc.player != null) {
            PathfinderAction.renderTick(mc.player);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void onClose(CallbackInfo ci) {
        PathRenderer.close();
    }
}