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

package icyllis.modernui.mc.text.mixin;

import icyllis.modernui.mc.text.ModernWorldPreparedText;
import icyllis.modernui.mc.text.TextLayout;
import icyllis.modernui.mc.text.TextLayoutEngine;
import icyllis.modernui.mc.text.TextRenderType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nullable;

/**
 * World (3D) text rendering. Minecraft 26.2 removed {@code Font.drawInBatch}, so the
 * Modern Text Engine can no longer take over via {@code MixinFontRenderer}; instead we
 * substitute the prepared text that this renderer builds for each submitted string.
 * <p>
 * The glowing outline is drawn as a single SDF stroke pass rather than vanilla's eight
 * offset copies, which is why both calls have to be replaced together: the two passes
 * must come from the same {@link TextLayout} or they would not line up.
 */
@Mixin(TextFeatureRenderer.class)
public class MixinTextFeatureRenderer {

    /**
     * {@code Submit.displayMode()} is read once per string, before either pass is
     * prepared, and decides whether the glyphs must be baked for see-through.
     */
    @Unique
    private Font.DisplayMode modernUI_MC$displayMode = Font.DisplayMode.NORMAL;

    @Unique
    @Nullable
    private ModernWorldPreparedText modernUI_MC$pendingOutline;

    @Unique
    private int modernUI_MC$pendingOutlineColor;

    @Redirect(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;" +
                    "displayMode()Lnet/minecraft/client/gui/Font$DisplayMode;"))
    private Font.DisplayMode onGetDisplayMode(TextFeatureRenderer.Submit submit) {
        Font.DisplayMode displayMode = submit.displayMode();
        modernUI_MC$displayMode = displayMode;
        return displayMode;
    }

    @Redirect(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;prepare8xTextOutline" +
                    "(Lnet/minecraft/util/FormattedCharSequence;FFI)" +
                    "Lnet/minecraft/client/gui/Font$PreparedText;"))
    private Font.PreparedText onPrepare8xTextOutline(Font font, FormattedCharSequence text,
                                                     float x, float y, int outlineColor) {
        TextLayout layout = TextLayoutEngine.getInstance().lookupFormattedLayout(text);
        ModernWorldPreparedText outline = layout.prepareWorldTextOutline(x, y, outlineColor);
        if (!TextLayoutEngine.sUseTextShadersInWorld) {
            // there is no SDF stroke without the text shaders
            outline.discard();
        }
        modernUI_MC$pendingOutline = outline;
        modernUI_MC$pendingOutlineColor = outlineColor;
        return outline;
    }

    @Redirect(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;prepareText" +
                    "(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)" +
                    "Lnet/minecraft/client/gui/Font$PreparedText;"))
    private Font.PreparedText onPrepareText(Font font, FormattedCharSequence text,
                                            float x, float y, int color, boolean dropShadow,
                                            boolean includeEmpty, int backgroundColor) {
        // the outline pass, if any, was prepared for this very string just above
        ModernWorldPreparedText outline = modernUI_MC$pendingOutline;
        modernUI_MC$pendingOutline = null;
        if (outline != null && (color & 0xFFFFFF) == 0) {
            // vanilla draws black glyphs inside the colored glow; the Modern Text Engine
            // draws the glyphs in the glow color and drops the stroke instead
            color = modernUI_MC$pendingOutlineColor;
            outline.discard();
        }

        int mode = modernUI_MC$displayMode == Font.DisplayMode.SEE_THROUGH
                ? TextRenderType.MODE_SEE_THROUGH
                : TextRenderType.MODE_SDF_FILL;
        TextLayout layout = TextLayoutEngine.getInstance().lookupFormattedLayout(text);
        return layout.prepareWorldText(x, y, color, dropShadow, mode, backgroundColor);
    }
}
