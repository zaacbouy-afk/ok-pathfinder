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

/**
 * The in-game settings screen, opened with /ezforaging.
 * Two arrow buttons at the top cycle between pages.
 *
 * Page 0 — Pathfinding: reach, angle, stuck detection, repath settings.
 * Page 1 — Rotation: yaw/pitch speed, EMA blend rates.
 *
 * All changes take effect immediately. Values saved to disk on close.
 */
public class EzForagingScreen extends Screen {

    private int currentPage = 0;
    private static final String[] PAGE_NAMES = { "Pathfinding", "Rotation" };

    public EzForagingScreen(Component title) {
        super(Component.empty());
    }

    // ── Layout ─────────────────────────────────────────────────────────

    @Override
    protected void init() {
        int paddingX    = this.width / 6;
        int paddingY    = this.height / 6;
        int boxWidth    = this.width - paddingX * 2;
        int widgetWidth = (int) (boxWidth / 2.8);
        int widgetX1    = paddingX + 15;
        int widgetX2    = (int) (this.width - paddingX - widgetWidth - 15);

        // ── Navigation arrows ──────────────────────────────────────────
        addRenderableWidget(new NavButton(widgetX1, paddingY + 35, widgetWidth, 30,
                "\u25C4", () -> {
                    currentPage = (currentPage - 1 + PAGE_NAMES.length) % PAGE_NAMES.length;
                    rebuildWidgets();
                }));

        addRenderableWidget(new NavButton(widgetX2, paddingY + 35, widgetWidth, 30,
                "\u25BA", () -> {
                    currentPage = (currentPage + 1) % PAGE_NAMES.length;
                    rebuildWidgets();
                }));

        // ── Page sliders ───────────────────────────────────────────────
        int sliderX      = paddingX + 15;
        int sliderW      = boxWidth - 30;
        int sliderStartY = paddingY + 80;

        if (currentPage == 0) {
            int n       = 5;
            int spacing = (int) ((this.height - paddingY * 2 - 90) / (n + 0.5));

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

        } else {
            int n       = 4;
            int spacing = (int) ((this.height - paddingY * 2 - 90) / (n + 0.5));

            addRenderableWidget(new SettingSlider(sliderX, sliderStartY, sliderW,
                    "Turn Speed (deg/frame)", 1.0, 20.0, PathfinderConfig.maxYawPerFrame, false,
                    v -> PathfinderConfig.maxYawPerFrame = v.floatValue()));

            addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing, sliderW,
                    "Render Smooth Speed", 0.05, 1.0, PathfinderConfig.smoothSpeedMax, false,
                    v -> PathfinderConfig.smoothSpeedMax = v.floatValue()));

            addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 2, sliderW,
                    "Yaw Blend Speed", 0.1, 1.0, PathfinderConfig.yawBlendSpeed, false,
                    v -> PathfinderConfig.yawBlendSpeed = v.floatValue()));

            addRenderableWidget(new SettingSlider(sliderX, sliderStartY + spacing * 3, sliderW,
                    "Pitch Blend Speed", 0.02, 0.5, PathfinderConfig.pitchBlendSpeed, false,
                    v -> PathfinderConfig.pitchBlendSpeed = v.floatValue()));
        }

        int toggleWidth = 110;
        int toggleHeight = 20;
        int toggleX = this.width - paddingX - toggleWidth - 15;
        int toggleY = this.height - paddingY - toggleHeight - 15;
        addRenderableWidget(new ToggleButton(toggleX, toggleY, toggleWidth, toggleHeight,
                "Debug", PathfinderConfig.debugEnabled,
                enabled -> PathfinderConfig.debugEnabled = enabled));
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onClose() {
        PathfinderConfig.save();
        super.onClose();
    }

    // ── Rendering ──────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int paddingX = this.width / 6;
        int paddingY = this.height / 6;

        // White panel background
        graphics.fill(paddingX, paddingY, this.width - paddingX, this.height - paddingY, 0xFFFFFFFF);

        // Title
        graphics.drawString(minecraft.font, "ezForagingHUD", paddingX + 15, paddingY + 15, 0xFF000000, false);

        // Page name centered between the two nav buttons
        String pageName = PAGE_NAMES[currentPage];
        int textW = minecraft.font.width(pageName);
        graphics.drawString(minecraft.font, pageName,
                this.width / 2 - textW / 2, paddingY + 43, 0xFF000000, false);

        // Page indicator dots (e.g. "1 / 2")
        String pageNum = (currentPage + 1) + " / " + PAGE_NAMES.length;
        int numW = minecraft.font.width(pageNum);
        graphics.drawString(minecraft.font, pageNum,
                this.width / 2 - numW / 2, paddingY + 56, 0xFF888888, false);

        super.render(graphics, mouseX, mouseY, delta);
    }

    // ── Widgets ────────────────────────────────────────────────────────

    /**
     * Navigation arrow button. Shows a centred label (arrow glyph) on a dark fill.
     */
    public class NavButton extends AbstractWidget {
        private final String label;
        private final Runnable onPress;

        public NavButton(int x, int y, int width, int height, String label, Runnable onPress) {
            super(x, y, width, height, Component.literal(label));
            this.label   = label;
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
            int bg = isHovered() ? 0xFF555555 : 0xFF333333;
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, bg);
            int textW = minecraft.font.width(label);
            int textX = getX() + (this.width - textW) / 2;
            int textY = getY() + (this.height - 8) / 2;
            graphics.drawString(minecraft.font, label, textX, textY, 0xFFFFFFFF, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {}
    }

    private static class ToggleButton extends AbstractWidget {
        private final String label;
        private final Consumer<Boolean> setter;
        private boolean enabled;

        ToggleButton(int x, int y, int width, int height, String label,
                     boolean enabled, Consumer<Boolean> setter) {
            super(x, y, width, height, Component.empty());
            this.label = label;
            this.enabled = enabled;
            this.setter = setter;
            updateMessage();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean flag) {
            if (this.isHovered()) {
                enabled = !enabled;
                setter.accept(enabled);
                updateMessage();
                return true;
            }
            return super.mouseClicked(event, flag);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            Minecraft mc = Minecraft.getInstance();
            int bg = enabled ? 0xFF1F6F3D : 0xFF444444;
            if (isHovered()) {
                bg = enabled ? 0xFF2A8A4D : 0xFF5A5A5A;
            }
            graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, bg);
            int textW = mc.font.width(getMessage());
            int textX = getX() + (this.width - textW) / 2;
            int textY = getY() + (this.height - 8) / 2;
            graphics.drawString(mc.font, getMessage(), textX, textY, 0xFFFFFFFF, false);
        }

        private void updateMessage() {
            setMessage(Component.literal(label + ": " + (enabled ? "ON" : "OFF")));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {}
    }

    /**
     * A labelled slider backed by a min/max range.
     * Supports float (isInt=false) and rounded integer (isInt=true) values.
     * Calls the provided setter immediately on every drag update.
     */
    private static class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final boolean isInt;
        private final Consumer<Double> setter;

        SettingSlider(int x, int y, int width, String label,
                      double min, double max, double current,
                      boolean isInt, Consumer<Double> setter) {
            super(x, y, width, 20, Component.empty(), (current - min) / (max - min));
            this.label  = label;
            this.min    = min;
            this.max    = max;
            this.isInt  = isInt;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double val     = min + value * (max - min);
            String display = isInt
                    ? String.valueOf((int) Math.round(val))
                    : String.format("%.2f", val);
            setMessage(Component.literal(label + ": " + display));
        }

        @Override
        protected void applyValue() {
            setter.accept(min + value * (max - min));
        }
    }
}
