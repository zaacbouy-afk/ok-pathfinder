package name.ezforaging;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.MouseButtonEvent;

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
            Minecraft.getInstance().player.displayClientMessage(Component.literal("Left Widget Clicked!"),false);
        });
        this.addRenderableWidget(customWidget1);

        CustomWidget customWidget2 = new CustomWidget(widgetX2, paddingY + 40, widgetWidth , 30, () -> {
            Minecraft.getInstance().player.displayClientMessage(Component.literal("Right Widget Clicked!"),false);
        });
        this.addRenderableWidget(customWidget2);
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
            return;
        }
    }
}