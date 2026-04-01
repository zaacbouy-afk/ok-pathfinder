package name.ezforaging.pathfinding;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public class PathRenderer {
    private static List<BlockPos> currentPath = new ArrayList<>();

    // Custom pipeline that renders through walls
    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("ezforaging", "pipeline/path_renderer"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build()
    );

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static BufferBuilder buffer;

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    public static void setPath(List<BlockPos> path) {
        currentPath = path;
    }

    public static void clearPath() {
        currentPath = new ArrayList<>();
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (currentPath.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Sync with PathfinderAction's progress
        int currentIndex = PathfinderAction.getCurrentNode();
        if (currentIndex >= currentPath.size()) {
            currentPath.clear();
            return;
        }

        PoseStack matrices = context.matrices();
        Vec3 camera = mc.gameRenderer.getMainCamera().position();

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        if (buffer == null) {
            buffer = new BufferBuilder(allocator, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());
        }

        // Render remaining waypoint nodes
        for (int i = currentIndex; i < currentPath.size(); i++) {
            BlockPos pos = currentPath.get(i);
            float p = 0.3f;
            if (i == currentIndex) {
                // Current target: green
                renderFilledBox(matrices.last().pose(), buffer,
                        pos.getX() + p, pos.getY() + p, pos.getZ() + p,
                        pos.getX() + 1 - p, pos.getY() + 1 - p, pos.getZ() + 1 - p,
                        0f, 1f, 0f, 0.4f);
            } else {
                // Future nodes: grey
                renderFilledBox(matrices.last().pose(), buffer,
                        pos.getX() + p, pos.getY() + p, pos.getZ() + p,
                        pos.getX() + 1 - p, pos.getY() + 1 - p, pos.getZ() + 1 - p,
                        0.5f, 0.5f, 0.5f, 0.3f);
            }
        }

        // Render intermediate air blocks for remaining segments
        for (int i = currentIndex; i < currentPath.size() - 1; i++) {
            boolean isCurrent = (i == currentIndex);
            for (BlockPos air : getIntermediateBlocks(currentPath.get(i), currentPath.get(i + 1))) {
                float p = 0.25f;
                if (isCurrent) {
                    renderFilledBox(matrices.last().pose(), buffer,
                            air.getX() + p, air.getY() + p, air.getZ() + p,
                            air.getX() + 1 - p, air.getY() + 1 - p, air.getZ() + 1 - p,
                            0f, 0.8f, 1f, 0.25f); // cyan for current segment
                } else {
                    renderFilledBox(matrices.last().pose(), buffer,
                            air.getX() + p, air.getY() + p, air.getZ() + p,
                            air.getX() + 1 - p, air.getY() + 1 - p, air.getZ() + 1 - p,
                            0.5f, 0.5f, 0.5f, 0.15f); // grey for future segments
                }
            }
        }

        matrices.popPose();

        drawBuffer(mc, FILLED_THROUGH_WALLS);
    }

    // Returns the air blocks traversed between two consecutive path nodes.
    // For a fall: all blocks at the destination X/Z from fromY down to toY+1.
    // For a step up: the block above the origin (headroom slot).
    private static List<BlockPos> getIntermediateBlocks(BlockPos from, BlockPos to) {
        List<BlockPos> blocks = new ArrayList<>();
        int yDiff = to.getY() - from.getY();

        if (yDiff == 0) return blocks; // flat move, nothing in the air

        if (yDiff > 0) {
            // Step up: player needs headroom at from.above() to clear the jump
            blocks.add(from.above());
        } else {
            // Fall: all air blocks at to's X/Z between fromY and toY (exclusive of landing)
            for (int y = from.getY(); y > to.getY(); y--) {
                blocks.add(new BlockPos(to.getX(), y, to.getZ()));
            }
        }

        return blocks;
    }

    private static void renderFilledBox(Matrix4fc pose, BufferBuilder buffer,
                                        float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                        float r, float g, float b, float a) {
        // Front
        buffer.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);
        // Back
        buffer.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        // Left
        buffer.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);
        // Right
        buffer.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        // Top
        buffer.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);
        // Bottom
        buffer.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);
    }

    private static void drawBuffer(Minecraft client, RenderPipeline pipeline) {
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> "ezforaging path renderer", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        GpuBuffer vertices = vertexBuffer.currentBuffer();

        builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
        GpuBuffer indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
        VertexFormat.IndexType indexType = builtBuffer.drawState().indexType();

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "ezforaging path rendering", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(), client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
        vertexBuffer.rotate();
        buffer = null;
    }

    public static void close() {
        allocator.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}