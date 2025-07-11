package wtf.taksa.usual.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import lombok.NonNull;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import wtf.taksa.Taksa;
import wtf.taksa.mixin.accessor.NativeImageAccessor;
import wtf.taksa.render.shader.storage.BlurShader;
import wtf.taksa.render.shader.storage.RectangleShader;
import wtf.taksa.usual.utils.math.Radius;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.nio.IntBuffer;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
/**
 * @author Kenny1337
 * @since 28.06.2025
 */
/**
 * <p>Utils for rendering in minecraft</p>
 */
@SuppressWarnings("unused")
public class RendererUtils {
    @ApiStatus.Internal
    public static final Matrix4f lastProjMat = new Matrix4f();
    @ApiStatus.Internal
    public static final Matrix4f lastModMat = new Matrix4f();
    @ApiStatus.Internal
    public static final Matrix4f lastWorldSpaceMatrix = new Matrix4f();

    private static final FastMStack empty = new FastMStack();
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final char RND_START = 'a';
    private static final char RND_END = 'z';
    private static final Random RND = new Random();

    /**
     * <p>Sets up rendering and resets everything that should be reset</p>
     */
    public static void setupRender() {
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /**
     * <p>Reverts everything back to normal after rendering</p>
     */
    public static void endRender() {
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    /**
     * <p>Linear interpolation between two integers</p>
     *
     * @param from  Range from
     * @param to    Range to
     * @param delta Range delta
     * @return The interpolated value between from and to
     */
    public static int lerp(int from, int to, double delta) {
        return (int) Math.floor(from + (to - from) * MathHelper.clamp(delta, 0, 1));
    }

    /**
     * <p>Linear interpolation between two doubles</p>
     *
     * @param from  Range from
     * @param to    Range to
     * @param delta Range delta
     * @return The interpolated value between from and to
     */
    public static double lerp(double from, double to, double delta) {
        return from + (to - from) * MathHelper.clamp(delta, 0, 1);
    }

    /**
     * <p>Linear interpolation between two colors</p>
     *
     * @param a Color range from
     * @param b Color range to
     * @param c Range delta
     * @return The interpolated color
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static Color lerp(@NonNull Color a, @NonNull Color b, double c) {
        return new Color(lerp(a.getRed(), b.getRed(), c), lerp(a.getGreen(), b.getGreen(), c),
                lerp(a.getBlue(), b.getBlue(), c), lerp(a.getAlpha(), b.getAlpha(), c));
    }

    /**
     * <p>Modifies a color</p>
     * <p>Any of the components can be set to -1 to keep them from the original color</p>
     *
     * @param original       The original color
     * @param redOverwrite   The new red components
     * @param greenOverwrite The new green components
     * @param blueOverwrite  The new blue components
     * @param alphaOverwrite The new alpha components
     * @return The new color
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    public static Color modify(@NonNull Color original, int redOverwrite, int greenOverwrite, int blueOverwrite, int alphaOverwrite) {
        return new Color(
                redOverwrite == -1 ? original.getRed() : redOverwrite,
                greenOverwrite == -1 ? original.getGreen() : greenOverwrite,
                blueOverwrite == -1 ? original.getBlue() : blueOverwrite,
                alphaOverwrite == -1 ? original.getAlpha() : alphaOverwrite
        );
    }

    /**
     * <p>Translates a Vec3d's position with a MatrixStack</p>
     *
     * @param stack The MatrixStack to translate with
     * @param in    The Vec3d to translate
     * @return The translated Vec3d
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static Vec3d translateVec3dWithMatrixStack(@NonNull MatrixStack stack, @NonNull Vec3d in) {
        Matrix4f matrix = stack.peek().getPositionMatrix();
        Vector4f vec = new Vector4f((float) in.x, (float) in.y, (float) in.z, 1);
        vec.mul(matrix);
        return new Vec3d(vec.x(), vec.y(), vec.z());
    }

    /**
     * <p>Registers a BufferedImage as Identifier, to be used in future render calls</p>
     * <p><strong>WARNING:</strong> This will wait for the main tick thread to register the texture, keep in mind that the texture will not be available instantly</p>
     * <p><strong>WARNING 2:</strong> This will throw an exception when called when the OpenGL context is not yet made</p>
     *
     * @param i  The identifier to register the texture under
     * @param bi The BufferedImage holding the texture
     */
    public static void registerBufferedImageTexture(@NonNull Identifier i, @NonNull BufferedImage bi) {
        try {
            // argb from BufferedImage is little endian, alpha is actually where the `a` is in the label
            // rgba from NativeImage (and by extension opengl) is big endian, alpha is on the other side (abgr)
            // thank you opengl
            int ow = bi.getWidth();
            int oh = bi.getHeight();
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, ow, oh, false);
            @SuppressWarnings("DataFlowIssue") long ptr = ((NativeImageAccessor) (Object) image).getPointer();
            IntBuffer backingBuffer = MemoryUtil.memIntBuffer(ptr, image.getWidth() * image.getHeight());
            int off = 0;
            Object _d;
            WritableRaster _ra = bi.getRaster();
            ColorModel _cm = bi.getColorModel();
            int nbands = _ra.getNumBands();
            int dataType = _ra.getDataBuffer().getDataType();
            _d = switch (dataType) {
                case DataBuffer.TYPE_BYTE -> new byte[nbands];
                case DataBuffer.TYPE_USHORT -> new short[nbands];
                case DataBuffer.TYPE_INT -> new int[nbands];
                case DataBuffer.TYPE_FLOAT -> new float[nbands];
                case DataBuffer.TYPE_DOUBLE -> new double[nbands];
                default -> throw new IllegalArgumentException("Unknown data buffer type: " +
                        dataType);
            };

            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    _ra.getDataElements(x, y, _d);
                    int a = _cm.getAlpha(_d);
                    int r = _cm.getRed(_d);
                    int g = _cm.getGreen(_d);
                    int b = _cm.getBlue(_d);
                    int abgr = a << 24 | b << 16 | g << 8 | r;
                    backingBuffer.put(abgr);
                }
            }
            NativeImageBackedTexture tex = new NativeImageBackedTexture(image);
            tex.upload();
            if (RenderSystem.isOnRenderThread()) {
                MinecraftClient.getInstance().getTextureManager().registerTexture(i, tex);
            } else {
                RenderSystem.recordRenderCall(() -> MinecraftClient.getInstance().getTextureManager().registerTexture(i, tex));
            }
        } catch (Throwable e) { // should never happen, but just in case
            Taksa.LOGGER.error("Failed to register buffered image as identifier {}", i, e);
        }
    }

    public static void setRectanglePoints(BufferBuilder buffer, Matrix4f matrix, float x, float y, float x1, float y1) {
        buffer.vertex(matrix, x, y, 0);
        buffer.vertex(matrix, x, y1, 0);
        buffer.vertex(matrix, x1, y1, 0);
        buffer.vertex(matrix, x1, y, 0);
    }


    public static BufferBuilder preShaderDraw(MatrixStack matrices, float x, float y, float width, float height) {
        setupRender();
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        setRectanglePoints(buffer, matrix, x, y, x + width, y + height);
        return buffer;
    }

    public static void drawRectangle(MatrixStack matrices, float x, float y, float width, float height, Radius radius, Color color, float alpha, float brightness, float smoothness) {
        BufferBuilder bufferBuilder = preShaderDraw(matrices, x, y, width, height);
        RectangleShader shader = RectangleShader.INSTANCE;
        shader.setParameters(width, height, radius, color, color, color, color, brightness, smoothness);
        BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
        endRender();
    }

    public static void drawBlur(MatrixStack matrices, float x, float y, float width, float height, Radius cornerRadius, float blurRadius, Color tintColor, float brightness, float smoothness) {
        if (blurRadius <= 0) return;

        BufferBuilder bufferBuilder = preShaderDraw(matrices, x, y, width, height);
        BlurShader shader = BlurShader.INSTANCE;
        shader.setParameters(width, height, cornerRadius, blurRadius, tintColor, brightness, smoothness);
        BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
        endRender();
    }

    /**
     * Gets an empty matrix stack without having to initialize the object
     *
     * @return An empty matrix stack
     */
    public static MatrixStack getEmptyMatrixStack() {
        if (!empty.isEmpty()) {
            throw new IllegalStateException(
                    "Supposed \"empty\" stack is not actually empty; someone does not clean up after themselves.");
        }
        empty.loadIdentity(); // reset top to identity, in case someone modified it
        return empty;
    }

    /**
     * Gets the position of the crosshair of the player, transformed into world space
     *
     * @return The position of the crosshair of the player, transformed into world space
     */
    @Contract("-> new")
    public static Vec3d getCrosshairVector() {
        Camera camera = client.gameRenderer.getCamera();

        float pi = (float) Math.PI;
        float yawRad = (float) Math.toRadians(-camera.getYaw());
        float pitchRad = (float) Math.toRadians(-camera.getPitch());
        float f1 = MathHelper.cos(yawRad - pi);
        float f2 = MathHelper.sin(yawRad - pi);
        float f3 = -MathHelper.cos(pitchRad);
        float f4 = MathHelper.sin(pitchRad);

        return new Vec3d(f2 * f3, f4, f1 * f3).add(camera.getPos());
    }

    /**
     * Transforms an input position into a (x, y, d) coordinate, transformed to screen space. d specifies the far plane of the position, and can be used to check if the position is on screen. Use {@link #screenSpaceCoordinateIsVisible(Vec3d)}.
     * Example:
     * <pre>
     * {@code
     * // Hud render event
     * Vec3d targetPos = new Vec3d(100, 64, 100); // world space
     * Vec3d screenSpace = RendererUtils.worldSpaceToScreenSpace(targetPos);
     * if (RendererUtils.screenSpaceCoordinateIsVisible(screenSpace)) {
     *     // do something with screenSpace.x and .y
     * }
     * }
     * </pre>
     *
     * @param pos The world space coordinates to translate
     * @return The (x, y, d) coordinates
     * @throws NullPointerException If {@code pos} is null
     */
    @Contract(value = "_ -> new", pure = true)
    public static Vec3d worldSpaceToScreenSpace(@NonNull Vec3d pos) {
        Camera camera = client.getEntityRenderDispatcher().camera;
        int displayHeight = client.getWindow().getHeight();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Vector3f target = new Vector3f();

        double deltaX = pos.x - camera.getPos().x;
        double deltaY = pos.y - camera.getPos().y;
        double deltaZ = pos.z - camera.getPos().z;

        Vector4f transformedCoordinates = new Vector4f((float) deltaX, (float) deltaY, (float) deltaZ, 1.f).mul(
                lastWorldSpaceMatrix);

        Matrix4f matrixProj = new Matrix4f(lastProjMat);
        Matrix4f matrixModel = new Matrix4f(lastModMat);

        matrixProj.mul(matrixModel)
                .project(transformedCoordinates.x(), transformedCoordinates.y(), transformedCoordinates.z(), viewport,
                        target);

        return new Vec3d(target.x / client.getWindow().getScaleFactor(),
                (displayHeight - target.y) / client.getWindow().getScaleFactor(), target.z);
    }

    /**
     * Checks if a screen space coordinate (x, y, d) is on screen
     *
     * @param pos The (x, y, d) coordinates to check
     * @return True if the coordinates are visible
     */
    public static boolean screenSpaceCoordinateIsVisible(Vec3d pos) {
        return pos != null && pos.z > -1 && pos.z < 1;
    }

    /**
     * Converts a (x, y, d) screen space coordinate back into a world space coordinate. Example:
     * <pre>
     * {@code
     * // World render event
     * Vec3d near = RendererUtils.screenSpaceToWorldSpace(100, 100, 0);
     * Vec3d far = RendererUtils.screenSpaceToWorldSpace(100, 100, 1);
     * // Ray-cast from near to far to get block or entity at (100, 100) screen space
     * }
     * </pre>
     *
     * @param x x
     * @param y y
     * @param d d
     * @return The world space coordinate
     */
    @Contract(value = "_,_,_ -> new", pure = true)
    public static Vec3d screenSpaceToWorldSpace(double x, double y, double d) {
        Camera camera = client.getEntityRenderDispatcher().camera;
        int displayHeight = client.getWindow().getScaledHeight();
        int displayWidth = client.getWindow().getScaledWidth();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Vector3f target = new Vector3f();

        Matrix4f matrixProj = new Matrix4f(lastProjMat);
        Matrix4f matrixModel = new Matrix4f(lastModMat);

        matrixProj.mul(matrixModel)
                .mul(lastWorldSpaceMatrix)
                .unproject((float) x / displayWidth * viewport[2],
                        (float) (displayHeight - y) / displayHeight * viewport[3], (float) d, viewport, target);

        return new Vec3d(target.x, target.y, target.z).add(camera.getPos());
    }

    /**
     * Returns the GUI scale of the current window
     *
     * @return The GUI scale of the current window
     */
    public static int getGuiScale() {
        return (int) MinecraftClient.getInstance().getWindow().getScaleFactor();
    }

    private static String randomString(int length) {
        return IntStream.range(0, length)
                .mapToObj(operand -> {
                    char randomChar = (char) (RND_START + RND.nextInt(RND_END - RND_START + 1));
                    return String.valueOf(randomChar);
                })
                .collect(Collectors.joining());
    }


    /**
     * Returns an identifier in the renderer namespace, with a random id
     *
     * @return The identifier
     */
    @Contract(value = "-> new", pure = true)
    public static Identifier randomIdentifier() {
        return Identifier.of("renderer", "temp/" + randomString(32));
    }
}