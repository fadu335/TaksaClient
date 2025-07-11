package wtf.taksa.render.font;

import com.google.common.base.Preconditions;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2IntArrayMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import lombok.NonNull;
import lombok.SneakyThrows;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.render.VertexFormat.DrawMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import wtf.taksa.Taksa;
import wtf.taksa.render.shader.storage.RectangleShader;
import wtf.taksa.usual.utils.math.Radius;
import wtf.taksa.usual.utils.render.BufferUtils;
import wtf.taksa.usual.utils.render.Colors;
import wtf.taksa.usual.utils.render.RendererUtils;

import java.awt.*;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
/**
 * @author Kenny1337
 * @since 28.06.2025
 */
/**
 * A simple font renderer. Supports multiple fonts, passed in as {@link Font} instances.
 * <h2>Constructor</h2>
 * {@link FontRenderer#FontRenderer(Font[], float)} takes in a {@link Font} array with the fonts to use. All fonts in this array will be asked, in order,
 * if they have a glyph corresponding to the one being drawn. If none of them have it, the missing "square" is drawn.
 * <h2>Performance</h2>
 * Glyph maps are lazily computed when needed, to keep memory usage low until needed.
 * Creating glyph pages for large fonts takes a considerable amount of time.
 * If font size is high, it is recommended to decrease the amount of glyphs on each page, to both decrease memory usage
 * and computation time for each page, at the cost of requiring more pages in total to be created.
 * Additionally, characters for glyph pages which should immediately be available may be passed into {@link FontRenderer#FontRenderer(Font[], float, int, int, String)},
 * to bake the glyph pages for those characters on another thread once the font renderer is initialized.
 */
public class FontRenderer implements Closeable {
    private static final Char2IntArrayMap colorCodes = new Char2IntArrayMap() {{
        put('0', 0x000000);
        put('1', 0x0000AA);
        put('2', 0x00AA00);
        put('3', 0x00AAAA);
        put('4', 0xAA0000);
        put('5', 0xAA00AA);
        put('6', 0xFFAA00);
        put('7', 0xAAAAAA);
        put('8', 0x555555);
        put('9', 0x5555FF);
        put('A', 0x55FF55);
        put('B', 0x55FFFF);
        put('C', 0xFF5555);
        put('D', 0xFF55FF);
        put('E', 0xFFFF55);
        put('F', 0xFFFFFF);
    }};
    private static final ExecutorService ASYNC_WORKER = Executors.newCachedThreadPool();
    private final Object2ObjectMap<Identifier, ObjectList<DrawEntry>> GLYPH_PAGE_CACHE = new Object2ObjectOpenHashMap<>();
    private final float originalSize;
    private final ObjectList<GlyphMap> maps = new ObjectArrayList<>();
    private final Char2ObjectArrayMap<Glyph> allGlyphs = new Char2ObjectArrayMap<>();
    private final int charsPerPage;
    private final int padding;
    private final String prebakeGlyphs;
    private int scaleMul = 0;
    private Font[] fonts;
    private int previousGameScale = -1;
    private Future<Void> prebakeGlyphsFuture;
    private boolean initialized;

    /**
     * Initializes a new FontRenderer with the specified fonts
     *
     * @param fonts                    The fonts to use. The font renderer will go over each font in this array, search for the glyph, and render it if found. If no font has the specified glyph, it will draw the missing font symbol.
     * @param sizePx                   The size of the font in minecraft pixel units. One pixel unit = `guiScale` pixels
     * @param charactersPerPage        How many characters one glyph page should contain. Default 256
     * @param paddingBetweenCharacters Padding between characters on a glyph page. Increase if font characters tend to have a lot of decoration around the "main body" of a character.
     * @param prebakeCharacters        Characters to pre-bake off thread when the font is reinitialized. Glyph pages containing those characters will (most of the time) immediately be available when drawing.
     */
    public FontRenderer(@NonNull Font[] fonts, float sizePx, int charactersPerPage, int paddingBetweenCharacters, @Nullable String prebakeCharacters) {
        Preconditions.checkArgument(sizePx > 0, "sizePx <= 0");
        Preconditions.checkArgument(fonts.length > 0, "fonts.length <= 0");
        Preconditions.checkArgument(charactersPerPage > 4, "Unreasonable charactersPerPage count");
        Preconditions.checkArgument(paddingBetweenCharacters > 0, "paddingBetweenCharacters <= 0");
        this.originalSize = sizePx;
        this.charsPerPage = charactersPerPage;
        this.padding = paddingBetweenCharacters;
        this.prebakeGlyphs = prebakeCharacters;
        init(fonts, sizePx);
    }

    /**
     * Initializes a new FontRenderer with the specified fonts. Equivalent to {@link FontRenderer#FontRenderer(Font[], float, int, int, String) FontRenderer}{@code (fonts, sizePx, 256, 5, null)}
     *
     * @param fonts  The fonts to use. The font renderer will go over each font in this array, search for the glyph, and render it if found. If no font has the specified glyph, it will draw the missing font symbol.
     * @param sizePx The size of the font in minecraft pixel units. One pixel unit = `guiScale` pixels
     */
    public FontRenderer(Font[] fonts, float sizePx) {
        this(fonts, sizePx, 256, 5, null);
    }

    private static int floorNearestMulN(int x, int n) {
        return n * (int) Math.floor((double) x / (double) n);
    }

    /**
     * Strips all characters prefixed with a § from the given string
     *
     * @param text The string to strip
     * @return The stripped string
     */
    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '§') {
                i++;
                continue;
            }
            f.append(c);
        }
        return f.toString();
    }

    private void sizeCheck() {
        int gs = RendererUtils.getGuiScale();
        if (gs != this.previousGameScale) {
            close(); // delete glyphs and cache
            init(this.fonts, this.originalSize); // re-init
        }
    }

    private void init(Font[] fonts, float sizePx) {
        if (initialized) throw new IllegalStateException("Double call to init()");
        initialized = true;
        this.previousGameScale = RendererUtils.getGuiScale();
        this.scaleMul = this.previousGameScale;
        this.fonts = new Font[fonts.length];
        for (int i = 0; i < fonts.length; i++) {
            this.fonts[i] = fonts[i].deriveFont(sizePx * this.scaleMul);
        }
        if (prebakeGlyphs != null && !prebakeGlyphs.isEmpty()) {
            prebakeGlyphsFuture = this.prebake();
        }
    }

    private Future<Void> prebake() {
        return ASYNC_WORKER.submit(() -> {
            for (char c : prebakeGlyphs.toCharArray()) {
                if (Thread.interrupted()) break;
                locateGlyph1(c);
            }
            return null;
        });
    }

    private GlyphMap generateMap(char from, char to) {
        Taksa.LOGGER.debug("[Font renderer {}] Generating glyph page '{}' ({}) to '{}' ({}), {} characters", hashCode(), from, (int) from, to, (int) to, (int) to - from);
        GlyphMap gm = new GlyphMap(from, to, this.fonts, RendererUtils.randomIdentifier(), padding);
        maps.add(gm);
        return gm;
    }

    private Glyph locateGlyph0(char glyph) {
        for (GlyphMap map : maps) { // go over existing ones
            if (map.contains(glyph)) { // do they have it? good
                return map.getGlyph(glyph);
            }
        }
        int base = floorNearestMulN(glyph, charsPerPage); // if not, generate a new page and return the generated glyph
        GlyphMap glyphMap = generateMap((char) base, (char) (base + charsPerPage));
        return glyphMap.getGlyph(glyph);
    }

    private Glyph locateGlyph1(char glyph) {
        return allGlyphs.computeIfAbsent(glyph, this::locateGlyph0);
    }

    /**
     * Draws a string
     *
     * @param stack The MatrixStack
     * @param s     The string to draw
     * @param x     X coordinate to draw at
     * @param y     Y coordinate to draw at
     * @param r     Red color components of the text to draw
     * @param g     Green color components of the text to draw
     * @param b     Blue color components of the text to draw
     * @param a     Alpha color components of the text to draw
     */
    public void drawString(MatrixStack stack, String s, float x, float y, float r, float g, float b, float a) {
        if (prebakeGlyphsFuture != null && !prebakeGlyphsFuture.isDone()) {
            try {
                prebakeGlyphsFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                // if failed, we just continue
                // the glyphs aren't created, we'll create them later
            }
        }
        sizeCheck();
        float r2 = r, g2 = g, b2 = b;
        stack.push();
        stack.translate(x, y, 0);
        stack.scale(1f / this.scaleMul, 1f / this.scaleMul, 1f);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
//		RenderSystem.disableCull();
//		int prevMin = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
//		int prevMag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
//		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
//		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        Matrix4f mat = stack.peek().getPositionMatrix();
        char[] chars = s.toCharArray();
        float xOffset = 0;
        float yOffset = 0;
        boolean inSel = false;
        int lineStart = 0;
        synchronized (GLYPH_PAGE_CACHE) {
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (inSel) {
                    inSel = false;
                    char c1 = Character.toUpperCase(c);
                    if (colorCodes.containsKey(c1)) {
                        int ii = colorCodes.get(c1);
                        int[] col = Colors.RGBIntToRGB(ii);
                        r2 = col[0] / 255f;
                        g2 = col[1] / 255f;
                        b2 = col[2] / 255f;
                    } else if (c1 == 'R') {
                        r2 = r;
                        g2 = g;
                        b2 = b;
                    }
                    continue;
                }
                if (c == '§') {
                    inSel = true;
                    continue;
                } else if (c == '\n') {
                    yOffset += getStringHeight(s.substring(lineStart, i)) * scaleMul;
                    xOffset = 0;
                    lineStart = i + 1;
                    continue;
                }
                Glyph glyph = locateGlyph1(c);
                if (glyph.value() != ' ') { // we only need to really draw the glyph if it's not blank, otherwise we can just skip its width and that'll be it
                    Identifier i1 = glyph.owner().bindToTexture;
                    DrawEntry entry = new DrawEntry(xOffset, yOffset, r2, g2, b2, glyph);
                    GLYPH_PAGE_CACHE.computeIfAbsent(i1, integer -> new ObjectArrayList<>()).add(entry);
                }
                xOffset += glyph.width();
            }
            for (Identifier identifier : GLYPH_PAGE_CACHE.keySet()) {
                RenderSystem.setShaderTexture(0, identifier);
                List<DrawEntry> objects = GLYPH_PAGE_CACHE.get(identifier);

                BufferBuilder bb = Tessellator.getInstance().begin(DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

                for (DrawEntry object : objects) {
                    float xo = object.atX;
                    float yo = object.atY;
                    float cr = object.r;
                    float cg = object.g;
                    float cb = object.b;
                    Glyph glyph = object.toDraw;
                    GlyphMap owner = glyph.owner();
                    float w = glyph.width();
                    float h = glyph.height();
                    float u1 = (float) glyph.u() / owner.width;
                    float v1 = (float) glyph.v() / owner.height;
                    float u2 = (float) (glyph.u() + glyph.width()) / owner.width;
                    float v2 = (float) (glyph.v() + glyph.height()) / owner.height;

                    bb.vertex(mat, xo + 0, yo + h, 0).texture(u1, v2).color(cr, cg, cb, a);
                    bb.vertex(mat, xo + w, yo + h, 0).texture(u2, v2).color(cr, cg, cb, a);
                    bb.vertex(mat, xo + w, yo + 0, 0).texture(u2, v1).color(cr, cg, cb, a);
                    bb.vertex(mat, xo + 0, yo + 0, 0).texture(u1, v1).color(cr, cg, cb, a);
                }
                BufferUtils.draw(bb);
            }

            GLYPH_PAGE_CACHE.clear();
        }
        stack.pop();
//		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, prevMin);
//		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, prevMag);
    }

    public void drawString(MatrixStack stack, String s, float x, float y, Color color) {
        drawString(stack, s, x - getStringWidth(s) / 2f, y, color.getRed()/255f, color.getGreen()/255f, color.getBlue()/255f, color.getAlpha()/255f);
    }

    public static void drawClippedStringWithFade(DrawContext context, FontRenderer font, String text, float x, float y, float maxWidth, Color color, Color backgroundColor) {
        float textWidth = font.getStringWidth(text);

        //RenderSystem.enableScissor((int)x, 0, (int)maxWidth, context.getScaledWindowHeight());

        font.drawString(context.getMatrices(), text, x, y, color.getRed()/255f, color.getGreen()/255f, color.getBlue()/255f, color.getAlpha()/255f);

        //RenderSystem.disableScissor();

//        if (textWidth > maxWidth) {
//            float gradientWidth = 10;
//            float gradientX = x + maxWidth - gradientWidth;
//
//            Color transparent = new Color(backgroundColor.getRed(), backgroundColor.getGreen(), backgroundColor.getBlue(), 0);
//
//            BufferBuilder bufferBuilder = RendererUtils.preShaderDraw(context.getMatrices(), gradientX, y - 2, gradientWidth, font.getStringHeight(text) + 4);
//            RectangleShader shader = RectangleShader.INSTANCE;
//            shader.setParameters(gradientWidth, font.getStringHeight(text) + 4, new Radius(0), transparent, backgroundColor, backgroundColor, transparent, 1f, 1f, 0f);
//            BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
//            RendererUtils.endRender();
//        }
    }

    /**
     * Draws a string centered on the X coordinate
     *
     * @param stack The MatrixStack
     * @param s     The string to draw
     * @param x     X center coordinate of the text to draw
     * @param y     Y coordinate of the text to draw
     * @param r     Red color components
     * @param g     Green color components
     * @param b     Blue color components
     * @param a     Alpha color components
     */
    public void drawCenteredString(MatrixStack stack, String s, float x, float y, float r, float g, float b, float a) {
        drawString(stack, s, x - getStringWidth(s) / 2f, y, r, g, b, a);
    }

    public void drawCenteredString(MatrixStack stack, String s, float x, float y, Color color) {
        drawString(stack, s, x - getStringWidth(s) / 2f, y, color.getRed()/255f, color.getGreen()/255f, color.getBlue()/255f, color.getAlpha()/255f);
    }

    /**
     * Calculates the width of the string, if it were drawn on the screen
     *
     * @param text The text to simulate
     * @return The width of the string if it'd be drawn on the screen
     */
    public float getStringWidth(String text) {
        char[] c = stripControlCodes(text).toCharArray();
        float currentLine = 0;
        float maxPreviousLines = 0;
        for (char c1 : c) {
            if (c1 == '\n') {
                maxPreviousLines = Math.max(currentLine, maxPreviousLines);
                currentLine = 0;
                continue;
            }
            Glyph glyph = locateGlyph1(c1);
            currentLine += glyph.width() / (float) this.scaleMul;
        }
        return Math.max(currentLine, maxPreviousLines);
    }

    /**
     * Calculates the height of the string, if it were drawn on the screen. This is necessary, because the fonts in this FontRenderer might have a different height for each char.
     *
     * @param text The text to simulate
     * @return The height of the string if it'd be drawn on the screen
     */
    public float getStringHeight(String text) {
        char[] c = stripControlCodes(text).toCharArray();
        if (c.length == 0) {
            c = new char[]{' '};
        }
        float currentLine = 0;
        float previous = 0;
        for (char c1 : c) {
            if (c1 == '\n') {
                if (currentLine == 0) {
                    // empty line, assume space
                    currentLine = locateGlyph1(' ').height() / (float) this.scaleMul;
                }
                previous += currentLine;
                currentLine = 0;
                continue;
            }
            Glyph glyph = locateGlyph1(c1);
            currentLine = Math.max(glyph.height() / (float) this.scaleMul, currentLine);
        }
        return currentLine + previous;
    }

    /**
     * Clears all glyph maps, and unlinks them. The font can continue to be used, but it will have to regenerate the maps.
     */
    @SneakyThrows
    @Override
    public void close() {
        if (prebakeGlyphsFuture != null && !prebakeGlyphsFuture.isDone() && !prebakeGlyphsFuture.isCancelled()) {
            // if we have a prebake job running, cancel it to avoid it creating more pages while the font renderer clears
            prebakeGlyphsFuture.cancel(true);
            prebakeGlyphsFuture.get(); // should only ever throw interrupted, which is the parent's job to handle anyway
            prebakeGlyphsFuture = null;
        }
        for (GlyphMap map : maps) {
            map.destroy();
        }
        maps.clear();
        allGlyphs.clear();
        initialized = false;
    }

    record DrawEntry(float atX, float atY, float r, float g, float b, Glyph toDraw) {
    }
}