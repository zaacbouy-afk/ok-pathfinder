package name.ezforaging;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import name.ezforaging.pathfinding.PathfinderConfig;

import java.util.function.Consumer;

public class EzForagingScreen extends Screen {
    public EzForagingScreen(Component title) {
        super(Component.empty());
    }

    @Override
    protected void init() {
        int paddingX = this.width / 6;
        int paddingY = this.height / 6;

        int boxWidth = this.width - paddingX * 2;
        int widgetWidth = (int)(boxWidth / 2.8);
        int widgetX1 = paddingX + 15;
        int widgetX2 = (int)(this.width - paddingX - widgetWidth - 15);

        CustomWidget customWidget1 = new CustomWidget(widgetX1, paddingY + 40, widgetWidth, 30, () -> {
            Minecraft.getInstance().player.displayClientMessage(Component.literal("Left Widget Clicked!"), false);
        });
        this.addRenderableWidget(customWidget1);

        CustomWidget customWidget2 = new CustomWidget(widgetX2, paddingY + 40, widgetWidth, 30, () -> {
            Minecraft.getInstance().player.displayClientMessage(Component.literal("Right Widget Clicked!"), false);
        });
        this.addRenderableWidget(customWidget2);

        int sliderX = paddingX + 15;
        int sliderW = boxWidth - 30;
        int sliderStartY = paddingY + 85;
        int spacing = (int)((this.height - paddingY * 2 - 95) / 7.5);

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY, sliderW,
                "Node Reach", 0.5, 4.0, PathfinderConfig.reachDistance, false,
                v -> PathfinderConfig.reachDistance = v));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing, sliderW,
                "Forward Angle", 15.0, 90.0, PathfinderConfig.forwardAngleThreshold, true,
                v -> PathfinderConfig.forwardAngleThreshold = v.floatValue()));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 2, sliderW,
                "Stuck Threshold", 0.1, 5.0, PathfinderConfig.stuckThreshold, false,
                v -> PathfinderConfig.stuckThreshold = v));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 3, sliderW,
                "Repath Delay (ticks)", 5.0, 60.0, PathfinderConfig.stuckRepathTicks, true,
                v -> PathfinderConfig.stuckRepathTicks = v.intValue()));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 4, sliderW,
                "Max Retries", 1.0, 10.0, PathfinderConfig.maxRepathAttempts, true,
                v -> PathfinderConfig.maxRepathAttempts = v.intValue()));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 5, sliderW,
                "Turn Speed", 2.0, 20.0, PathfinderConfig.maxYawPerFrame, false,
                v -> PathfinderConfig.maxYawPerFrame = v.floatValue()));

        addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 6, sliderW,
                "Smooth Speed", 0.1, 1.0, PathfinderConfig.smoothSpeedMax, false,
                v -> PathfinderConfig.smoothSpeedMax = v.floatValue()));
    }

    @Override
    public void onClose() {
        PathfinderConfig.save();
        super.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int paddingX = this.width / 6;
        int paddingY = this.height / 6;

        graphics.fill(paddingX, paddingY, this.width - paddingX, this.height - paddingY, 0xFFFFFFFF);
        graphics.drawString(minecraft.font, "ezForagingHUD", paddingX + 15, paddingY + 15, 0xFF000000, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    public class CustomWidget extends AbstractWidget {
        private final Runnable onPress;

        public CustomWidget(int x, int y, int width, int height, Runnable onPress) {
            super(x, y, width, height, Component.empty());
            this.onPress = onPress;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean flag) {
            if (this.isHovered()) {
                onPress.run();
                return true;
            }
            return super.mouseClicked(event, flag);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int color = isHovered() ? 0xFF666666 : 0xFF333333;
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {
        }
    }

    private static class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final boolean isInt;
        private final Consumer<Double> setter;

        SettingSlider(int x, int y, int width, String label, double min, double max, double current, boolean isInt, Consumer<Double> setter) {
            super(x, y, width, 20, Component.empty(), (current - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.isInt = isInt;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double val = min + value * (max - min);
            String display = isInt ? String.valueOf((int) Math.round(val)) : String.format("%.2f", val);
            setMessage(Component.literal(label + ": " + display));
        }

        @Override
        protected void applyValue() {
            setter.accept(min + value * (max - min));
        }
    }
}
