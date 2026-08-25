/*
 * Modern UI.
 * Copyright (C) 2019-2026 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.modernui.mc.text;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import icyllis.arc3d.core.Rect2f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix4fc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * World (3D) counterpart of {@link ModernPreparedText}.
 * <p>
 * Minecraft 26.2 removed {@code Font.drawInBatch}; world text now goes through
 * {@code Font.prepareText} and {@link Font.PreparedText#visit}, so the Modern Text
 * Engine emits {@link TextRenderable}s instead of writing into a
 * {@code MultiBufferSource} directly. This is the path taken by
 * {@link net.minecraft.client.renderer.feature.TextFeatureRenderer}, i.e. name tags,
 * text displays and signs.
 *
 * @see icyllis.modernui.mc.text.mixin.MixinTextFeatureRenderer
 */
public class ModernWorldPreparedText implements Font.PreparedText {

    /**
     * Depth bias between the glowing outline pass and the fill pass; the two render
     * types carry the same polygon offset, so they would z-fight without this.
     */
    public static final float OUTLINE_DEPTH = 0.01f;

    private final ArrayList<TextRenderable> mRenderables;
    @Nullable
    private final ScreenRectangle mBounds;

    /**
     * Set when the fill pass has taken over the outline color, see {@link #discard()}.
     */
    private boolean mDiscarded;

    /**
     * The fill pass, this is the successor of the old {@code TextLayout.drawText()}.
     */
    ModernWorldPreparedText(@Nonnull TextLayout layout, @Nonnull BakedGlyph[] baseGlyphs,
                            float density, float x, float top,
                            int color, boolean dropShadow, int preferredMode, int bgColor) {
        final int a = color >>> 24;
        final int r = color >> 16 & 0xff;
        final int g = color >> 8 & 0xff;
        final int b = color & 0xff;

        final boolean hasShadow = dropShadow && ModernTextRenderer.sAllowShadow;
        final float shadowOffset = hasShadow ? ModernTextRenderer.sShadowOffset : 0;

        final float[] positions = layout.getPositions();
        final int[] flags = layout.getGlyphFlags();
        final float baseline = top + TextLayout.sBaselineOffset;
        final float totalAdvance = layout.getTotalAdvance();

        final var renderables = new ArrayList<TextRenderable>();
        final var bounds = Rect2f.makeInfiniteInverted();
        final BakedGlyph[] glyphs = resolveObfuscated(baseGlyphs, flags);

        if ((bgColor & 0xFF000000) != 0) {
            renderables.add(GlyphManager.getInstance().getEffectGlyph().createEffect(
                    x - 1, top - 1, x + totalAdvance + 1, top + 9,
                    -TextRenderEffect.EFFECT_DEPTH, bgColor, 0, 0
            ));
            bounds.joinNoCheck(x - 1, top - 1, x + totalAdvance + 1, top + 9);
        }

        // the shadow goes first, the fill is then lifted towards the viewer
        if (hasShadow) {
            buildFillPass(renderables, bounds, layout, glyphs, positions, flags,
                    x, top, density, preferredMode,
                    r >> 2, g >> 2, b >> 2, a, true, shadowOffset, 0);
        }
        buildFillPass(renderables, bounds, layout, glyphs, positions, flags,
                x, top, density, preferredMode,
                r, g, b, a, false, shadowOffset, hasShadow ? Font.SHADOW_DEPTH : 0);

        if (layout.hasEffect()) {
            buildEffects(renderables, bounds, positions, flags, x, baseline,
                    totalAdvance, color, hasShadow ? shadowOffset : 0, hasShadow);
        }

        mRenderables = renderables;
        mBounds = toScreenRectangle(bounds);
    }

    /**
     * The glowing outline pass, this is the successor of the old
     * {@code TextLayout.drawTextOutline()}. A single SDF stroke draw replaces
     * vanilla's eight offset copies.
     */
    ModernWorldPreparedText(@Nonnull TextLayout layout, @Nonnull BakedGlyph[] baseGlyphs,
                            float resLevel, float x, float top, int outlineColor) {
        final float[] positions = layout.getPositions();
        final int[] flags = layout.getGlyphFlags();
        final float baseline = top + TextLayout.sBaselineOffset;

        final var renderables = new ArrayList<TextRenderable>();
        final var bounds = Rect2f.makeInfiniteInverted();
        final BakedGlyph[] glyphs = resolveObfuscated(baseGlyphs, flags);

        // outset glyph bounds
        final float bloat = 1.0f / resLevel;
        boolean any = false;
        for (int i = 0, e = glyphs.length; i < e; i++) {
            if (!(glyphs[i] instanceof ModernBakedGlyph glyph) ||
                    (flags[i] & CharacterStyle.ANY_BITMAP_REPLACEMENT) != 0) {
                // bitmap font and color emoji have no distance field to stroke
                continue;
            }
            any = true;
            float rx = x + positions[i << 1] + glyph.x / resLevel;
            float ry = baseline + positions[i << 1 | 1] + glyph.y / resLevel;
            bounds.joinNoCheck(rx - bloat, ry - bloat,
                    rx + glyph.width / resLevel + bloat,
                    ry + glyph.height / resLevel + bloat);
        }
        if (any) {
            renderables.add(new OutlineRun(glyphs, positions, flags,
                    x, baseline, resLevel, bloat, outlineColor, bounds));
        }

        mRenderables = renderables;
        mBounds = toScreenRectangle(bounds);
    }

    /**
     * Drop this pass. Vanilla draws black glyphs inside a colored glow; the Modern Text
     * Engine draws the glyphs in the glow color instead and skips the stroke, which is
     * what {@code ModernTextRenderer.drawText8xOutline()} used to do.
     */
    public void discard() {
        mDiscarded = true;
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    @Override
    public void visit(@Nonnull Font.GlyphVisitor glyphVisitor) {
        if (mDiscarded) {
            return;
        }
        // For-index is 2x faster than enhanced-for
        for (int i = 0; i < mRenderables.size(); i++) {
            var renderable = mRenderables.get(i);
            if (renderable instanceof TextRenderable.Styled styled) {
                glyphVisitor.acceptGlyph(styled);
            } else {
                glyphVisitor.acceptEffect(renderable);
            }
        }
    }

    @Nullable
    @Override
    public ScreenRectangle bounds() {
        return mBounds;
    }

    /**
     * Obfuscated chars pick a random glyph per frame; resolve them once so that the run
     * splitting and {@link GlyphRun#render} agree on which glyph images are drawn.
     */
    @Nonnull
    private static BakedGlyph[] resolveObfuscated(@Nonnull BakedGlyph[] glyphs, @Nonnull int[] flags) {
        BakedGlyph[] result = glyphs;
        for (int i = 0, e = glyphs.length; i < e; i++) {
            if ((flags[i] & CharacterStyle.OBFUSCATED_MASK) == 0 ||
                    !(glyphs[i] instanceof GlyphManager.FastCharSet chars)) {
                continue;
            }
            if (result == glyphs) {
                result = glyphs.clone();
            }
            result[i] = chars.glyphs.get(TextLayout.RANDOM.nextInt(chars.glyphs.size()));
        }
        return result;
    }

    private static void buildFillPass(@Nonnull ArrayList<TextRenderable> renderables,
                                      @Nonnull Rect2f bounds,
                                      @Nonnull TextLayout layout,
                                      @Nonnull BakedGlyph[] glyphs,
                                      @Nonnull float[] positions, @Nonnull int[] flags,
                                      float x, float top, float density, int preferredMode,
                                      int r, int g, int b, int a,
                                      boolean isShadow, float shadowOffset, float z) {
        final float invDensity = 1.0f / density;
        float baseline = top + TextLayout.sBaselineOffset;
        if (isShadow) {
            x += shadowOffset;
            top += shadowOffset;
            baseline += shadowOffset;
        }

        Identifier prevTextureName = null;
        int prevMode = -1;
        GlyphRun run = null;

        for (int i = 0, e = glyphs.length; i < e; i++) {
            var vglyph = glyphs[i];
            if (vglyph == null) {
                continue;
            }
            final int bits = flags[i];
            if (!(vglyph instanceof ModernBakedGlyph glyph)) {
                // atlas sprite or player skin, they don't use style and have no shadow
                if (!isShadow) {
                    int glyphColor = (bits & CharacterStyle.IMPLICIT_COLOR_MASK) == 0
                            ? (a << 24) | (bits & 0xffffff)
                            : (a << 24) | (r << 16) | (g << 8) | b;
                    var renderable = vglyph.createGlyph(
                            x + positions[i << 1],
                            top + positions[i << 1 | 1],
                            glyphColor, 0, Style.EMPTY, 0, 0
                    );
                    if (renderable != null) {
                        bounds.joinNoCheck(renderable.left(), renderable.top(),
                                renderable.right(), renderable.bottom());
                        renderables.add(renderable);
                    }
                }
                // a foreign renderable interrupts the current run, otherwise it would
                // be drawn out of order
                if (run != null) {
                    run.glyphEnd = i;
                    run = null;
                }
                prevTextureName = null;
                prevMode = -1;
                continue;
            }
            if ((bits & CharacterStyle.NO_SHADOW_MASK) != 0 && isShadow) {
                continue;
            }

            final Identifier textureName;
            final AbstractTexture texture;
            final int mode;
            final boolean isBitmapFont;
            final boolean isColorEmoji;
            final float scaleFactor;
            if ((bits & CharacterStyle.ANY_BITMAP_REPLACEMENT) != 0) {
                if (layout.getFont(i) instanceof BitmapFont bitmapFont) {
                    textureName = bitmapFont.getCurrentTextureName();
                    texture = GlyphManager.getInstance().getCurrentTexture(bitmapFont);
                    scaleFactor = 1f / TextLayoutEngine.BITMAP_SCALE;
                    isBitmapFont = true;
                    isColorEmoji = false;
                } else {
                    if (isShadow) {
                        // color emoji has no shadow
                        continue;
                    }
                    textureName = GlyphManager.EMOJI_SHEET;
                    texture = GlyphManager.getInstance().getEmojiTexture();
                    scaleFactor = TextLayoutProcessor.sBaseFontSize / GlyphManager.EMOJI_BASE;
                    isBitmapFont = false;
                    isColorEmoji = true;
                }
                // both are direct mask, they are never SDF
                mode = preferredMode == TextRenderType.MODE_SEE_THROUGH
                        ? preferredMode
                        : TextRenderType.MODE_NORMAL;
            } else {
                textureName = GlyphManager.FONT_SHEET;
                texture = GlyphManager.getInstance().getFontTexture();
                scaleFactor = invDensity;
                isBitmapFont = false;
                isColorEmoji = false;
                mode = preferredMode;
            }
            if (texture == null) {
                continue;
            }

            if (run == null || prevTextureName != textureName || prevMode != mode) {
                prevTextureName = textureName;
                prevMode = mode;
                if (run != null) {
                    run.glyphEnd = i;
                }
                run = new GlyphRun(textureName, texture, mode, isBitmapFont, isColorEmoji,
                        glyphs, positions, flags, i,
                        x, baseline, z, invDensity, isShadow, shadowOffset, r, g, b, a);
                renderables.add(run);
            }

            final float rx = x + positions[i << 1] + glyph.x * scaleFactor;
            final float ry = baseline + positions[i << 1 | 1] + glyph.y * scaleFactor;
            final float w = glyph.width * scaleFactor;
            final float h = glyph.height * scaleFactor;
            bounds.joinNoCheck(rx, ry, rx + w, ry + h);
            run.join(rx, ry, rx + w, ry + h);
        }
        if (run != null) {
            run.glyphEnd = glyphs.length;
        }
    }

    private static void buildEffects(@Nonnull ArrayList<TextRenderable> renderables,
                                     @Nonnull Rect2f bounds,
                                     @Nonnull float[] positions, @Nonnull int[] flags,
                                     float x, float baseline, float totalAdvance,
                                     int color, float shadowOffset, boolean dropShadow) {
        final int a = color >>> 24;
        final var effectGlyph = GlyphManager.getInstance().getEffectGlyph();
        for (int i = 0, e = flags.length; i < e; i++) {
            final int bits = flags[i];
            if ((bits & CharacterStyle.EFFECT_MASK) == 0) {
                continue;
            }
            final int effectColor = (bits & CharacterStyle.IMPLICIT_COLOR_MASK) != 0
                    ? color
                    : (a << 24) | (bits & 0xffffff);
            final int shadowColor = dropShadow ? ARGB.scaleRGB(effectColor, 0.25f) : 0;
            final float rx1 = x + positions[i << 1];
            final float rx2 = x + ((i + 1 == e) ? totalAdvance : positions[(i + 1) << 1]);
            if ((bits & CharacterStyle.STRIKETHROUGH_MASK) != 0) {
                float top = baseline + TextRenderEffect.STRIKETHROUGH_OFFSET;
                float bottom = top + TextRenderEffect.STRIKETHROUGH_THICKNESS;
                renderables.add(effectGlyph.createEffect(rx1, top, rx2, bottom,
                        TextRenderEffect.EFFECT_DEPTH, effectColor, shadowColor, shadowOffset));
                bounds.joinNoCheck(rx1, top, rx2, bottom);
            }
            if ((bits & CharacterStyle.UNDERLINE_MASK) != 0) {
                float top = baseline + TextRenderEffect.UNDERLINE_OFFSET;
                float bottom = top + TextRenderEffect.UNDERLINE_THICKNESS;
                renderables.add(effectGlyph.createEffect(rx1, top, rx2, bottom,
                        TextRenderEffect.EFFECT_DEPTH, effectColor, shadowColor, shadowOffset));
                bounds.joinNoCheck(rx1, top, rx2, bottom);
            }
        }
    }

    @Nullable
    private static ScreenRectangle toScreenRectangle(@Nonnull Rect2f bounds) {
        if (bounds.isEmpty()) {
            return null;
        }
        int L = (int) Math.floor(bounds.left());
        int T = (int) Math.floor(bounds.top());
        int R = (int) Math.ceil(bounds.right());
        int B = (int) Math.ceil(bounds.bottom());
        return new ScreenRectangle(L, T, R - L, B - T);
    }

    /**
     * One sub run of glyphs sharing the same atlas and render mode. The geometry is
     * replayed from the layout, see {@link TextRunRenderState} for the GUI equivalent.
     */
    static final class GlyphRun implements TextRenderable.Styled {

        private final Identifier textureName;
        private final AbstractTexture texture;
        private final int mode;
        private final boolean isBitmapFont;
        private final boolean isColorEmoji;

        private final BakedGlyph[] glyphs;
        private final float[] positions;
        private final int[] flags;
        private final int glyphStart;
        int glyphEnd;

        private final float x;
        private final float baseline;
        private final float z;
        private final float invDensity;
        private final boolean isShadow;
        private final float shadowOffset;
        private final int startR, startG, startB, alpha;

        private float mLeft = Float.MAX_VALUE, mTop = Float.MAX_VALUE;
        private float mRight = -Float.MAX_VALUE, mBottom = -Float.MAX_VALUE;

        GlyphRun(Identifier textureName, AbstractTexture texture, int mode,
                 boolean isBitmapFont, boolean isColorEmoji,
                 BakedGlyph[] glyphs, float[] positions, int[] flags, int glyphStart,
                 float x, float baseline, float z, float invDensity,
                 boolean isShadow, float shadowOffset,
                 int startR, int startG, int startB, int alpha) {
            this.textureName = textureName;
            this.texture = texture;
            this.mode = mode;
            this.isBitmapFont = isBitmapFont;
            this.isColorEmoji = isColorEmoji;
            this.glyphs = glyphs;
            this.positions = positions;
            this.flags = flags;
            this.glyphStart = glyphStart;
            this.glyphEnd = glyphStart;
            this.x = x;
            this.baseline = baseline;
            this.z = z;
            this.invDensity = invDensity;
            this.isShadow = isShadow;
            this.shadowOffset = shadowOffset;
            this.startR = startR;
            this.startG = startG;
            this.startB = startB;
            this.alpha = alpha;
        }

        void join(float left, float top, float right, float bottom) {
            mLeft = Math.min(mLeft, left);
            mTop = Math.min(mTop, top);
            mRight = Math.max(mRight, right);
            mBottom = Math.max(mBottom, bottom);
        }

        @Override
        public void render(@Nonnull Matrix4fc pose, @Nonnull VertexConsumer builder,
                           int packedLight, boolean unused) {
            int r;
            int g;
            int b;
            for (int i = glyphStart; i < glyphEnd; i++) {
                if (!(glyphs[i] instanceof ModernBakedGlyph glyph)) {
                    continue;
                }
                final int bits = flags[i];
                if ((bits & CharacterStyle.NO_SHADOW_MASK) != 0 && isShadow) {
                    continue;
                }
                float rx;
                float ry;
                final float w;
                final float h;
                boolean fakeItalic = false;
                int ascent = 0;
                if ((bits & CharacterStyle.ANY_BITMAP_REPLACEMENT) != 0) {
                    final float scaleFactor;
                    if (!isColorEmoji) {
                        ascent = -glyph.y / TextLayoutEngine.BITMAP_SCALE;
                        scaleFactor = 1f / TextLayoutEngine.BITMAP_SCALE;
                    } else {
                        ascent = TextLayout.STANDARD_BASELINE_OFFSET;
                        scaleFactor = TextLayoutProcessor.sBaseFontSize / GlyphManager.EMOJI_BASE;
                    }
                    fakeItalic = (bits & CharacterStyle.ITALIC_MASK) != 0;
                    rx = x + positions[i << 1] + glyph.x * scaleFactor;
                    ry = baseline + positions[i << 1 | 1] + glyph.y * scaleFactor;
                    if (isShadow) {
                        // bitmap font shadow offset is always 1 pixel
                        rx += 1.0f - shadowOffset;
                        ry += 1.0f - shadowOffset;
                    }
                    w = glyph.width * scaleFactor;
                    h = glyph.height * scaleFactor;
                } else {
                    rx = x + positions[i << 1] + glyph.x * invDensity;
                    ry = baseline + positions[i << 1 | 1] + glyph.y * invDensity;
                    w = glyph.width * invDensity;
                    h = glyph.height * invDensity;
                }
                if (isColorEmoji) {
                    r = 0xff;
                    g = 0xff;
                    b = 0xff;
                } else if ((bits & CharacterStyle.IMPLICIT_COLOR_MASK) != 0) {
                    r = startR;
                    g = startG;
                    b = startB;
                } else {
                    r = bits >> 16 & 0xff;
                    g = bits >> 8 & 0xff;
                    b = bits & 0xff;
                    if (isShadow) {
                        r >>= 2;
                        g >>= 2;
                        b >>= 2;
                    }
                }
                float upSkew = 0;
                float downSkew = 0;
                if (fakeItalic) {
                    upSkew = 0.25f * ascent;
                    downSkew = 0.25f * (ascent - h);
                }
                builder.addVertex(pose, rx + upSkew, ry, z)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u1, glyph.v1)
                        .setLight(packedLight);
                builder.addVertex(pose, rx + downSkew, ry + h, z)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u1, glyph.v2)
                        .setLight(packedLight);
                builder.addVertex(pose, rx + w + downSkew, ry + h, z)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u2, glyph.v2)
                        .setLight(packedLight);
                builder.addVertex(pose, rx + w + upSkew, ry, z)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u2, glyph.v1)
                        .setLight(packedLight);
            }
        }

        @Nonnull
        @Override
        public RenderType renderType(@Nonnull Font.DisplayMode displayMode) {
            if (isBitmapFont || isColorEmoji || mode == TextRenderType.MODE_NORMAL) {
                // direct mask, honour the layering requested by vanilla
                return TextRenderType.getOrCreate(textureName, displayMode, isBitmapFont);
            }
            return displayMode == Font.DisplayMode.SEE_THROUGH
                    ? TextRenderType.getOrCreate(textureName, TextRenderType.MODE_SEE_THROUGH)
                    : TextRenderType.getOrCreate(textureName, mode);
        }

        @Nonnull
        @Override
        public GpuTextureView textureView() {
            return texture.getTextureView();
        }

        @Nonnull
        @Override
        public RenderPipeline guiPipeline() {
            return TextRenderType.getPipelineForGui(mode, isBitmapFont);
        }

        @Nonnull
        @Override
        public Style style() {
            return Style.EMPTY;
        }

        @Override
        public float left() {
            return mLeft;
        }

        @Override
        public float top() {
            return mTop;
        }

        @Override
        public float right() {
            return mRight;
        }

        @Override
        public float bottom() {
            return mBottom;
        }
    }

    /**
     * The SDF stroke pass, one draw for the whole text.
     */
    static final class OutlineRun implements TextRenderable.Styled {

        private final BakedGlyph[] glyphs;
        private final float[] positions;
        private final int[] flags;
        private final float x;
        private final float baseline;
        private final float resLevel;
        private final float bloat;
        private final int r, g, b, alpha;
        private final float mLeft, mTop, mRight, mBottom;

        OutlineRun(BakedGlyph[] glyphs, float[] positions, int[] flags,
                   float x, float baseline, float resLevel, float bloat,
                   int outlineColor, @Nonnull Rect2f bounds) {
            this.glyphs = glyphs;
            this.positions = positions;
            this.flags = flags;
            this.x = x;
            this.baseline = baseline;
            this.resLevel = resLevel;
            this.bloat = bloat;
            this.alpha = outlineColor >>> 24;
            this.r = outlineColor >> 16 & 0xff;
            this.g = outlineColor >> 8 & 0xff;
            this.b = outlineColor & 0xff;
            this.mLeft = bounds.left();
            this.mTop = bounds.top();
            this.mRight = bounds.right();
            this.mBottom = bounds.bottom();
        }

        @Override
        public void render(@Nonnull Matrix4fc pose, @Nonnull VertexConsumer builder,
                           int packedLight, boolean unused) {
            for (int i = 0, e = glyphs.length; i < e; i++) {
                if (!(glyphs[i] instanceof ModernBakedGlyph glyph) ||
                        (flags[i] & CharacterStyle.ANY_BITMAP_REPLACEMENT) != 0) {
                    continue;
                }
                final float rx = x + positions[i << 1] + glyph.x / resLevel;
                final float ry = baseline + positions[i << 1 | 1] + glyph.y / resLevel;
                final float w = glyph.width / resLevel;
                final float h = glyph.height / resLevel;
                final float uBloat = (glyph.u2 - glyph.u1) / glyph.width;
                final float vBloat = (glyph.v2 - glyph.v1) / glyph.height;
                builder.addVertex(pose, rx - bloat, ry - bloat, OUTLINE_DEPTH)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u1 - uBloat, glyph.v1 - vBloat)
                        .setLight(packedLight);
                builder.addVertex(pose, rx - bloat, ry + h + bloat, OUTLINE_DEPTH)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u1 - uBloat, glyph.v2 + vBloat)
                        .setLight(packedLight);
                builder.addVertex(pose, rx + w + bloat, ry + h + bloat, OUTLINE_DEPTH)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u2 + uBloat, glyph.v2 + vBloat)
                        .setLight(packedLight);
                builder.addVertex(pose, rx + w + bloat, ry - bloat, OUTLINE_DEPTH)
                        .setColor(r, g, b, alpha)
                        .setUv(glyph.u2 + uBloat, glyph.v1 - vBloat)
                        .setLight(packedLight);
            }
        }

        @Nonnull
        @Override
        public RenderType renderType(@Nonnull Font.DisplayMode displayMode) {
            return TextRenderType.getOrCreate(GlyphManager.FONT_SHEET,
                    TextRenderType.MODE_SDF_STROKE);
        }

        @Nonnull
        @Override
        public GpuTextureView textureView() {
            return GlyphManager.getInstance().getFontTexture().getTextureView();
        }

        @Nonnull
        @Override
        public RenderPipeline guiPipeline() {
            return TextRenderType.PIPELINE_SDF_STROKE;
        }

        @Nonnull
        @Override
        public Style style() {
            return Style.EMPTY;
        }

        @Override
        public float left() {
            return mLeft;
        }

        @Override
        public float top() {
            return mTop;
        }

        @Override
        public float right() {
            return mRight;
        }

        @Override
        public float bottom() {
            return mBottom;
        }
    }
}
