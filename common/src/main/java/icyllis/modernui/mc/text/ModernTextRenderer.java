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

/**
 * Runtime knobs for the Modern Text Engine.
 *
 * <p>Minecraft 26.2 removed the old MultiBufferSource based Font.drawInBatch
 * entry points. GUI text now flows through Font.prepareText and GuiRenderState;
 * the renderer implementation for that path lives in {@link ModernPreparedText}.
 */
public final class ModernTextRenderer {

    public static volatile boolean sAllowShadow = true;
    public static volatile float sShadowOffset = 1.0f;
    public static volatile float sOutlineOffset = 0.5f;
    public static volatile boolean sComputeDeviceFontSize = true;
    public static volatile boolean sAllowSDFTextIn2D = true;
    public static volatile boolean sTweakExperienceText = true;

    public ModernTextRenderer(TextLayoutEngine engine) {
    }
}
