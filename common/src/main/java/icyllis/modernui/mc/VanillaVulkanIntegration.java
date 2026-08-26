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

package icyllis.modernui.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import icyllis.arc3d.engine.Swizzle;
import icyllis.arc3d.vulkan.VKUtil;
import icyllis.arc3d.vulkan.VulkanBackendContext;
import icyllis.arc3d.vulkan.VulkanMemoryAllocator;
import icyllis.modernui.core.VulkanManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import javax.annotation.Nonnull;
import java.nio.LongBuffer;

public final class VanillaVulkanIntegration {

    private VanillaVulkanIntegration() {
    }

    public static VulkanBackendContext wrapContext() {
        var device = (VulkanDevice) RenderSystem.getDevice().backend;
        var vkInstance = device.instance().vkInstance();
        var vkDevice = device.vkDevice();
        var physicalDevice = vkDevice.getPhysicalDevice();
        var graphicsQueue = device.graphicsQueue();

        final int apiVersion;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
            apiVersion = properties.apiVersion();
        }

        VulkanManager vulkanManager = VulkanManager.get();
        // vanilla does not keep the feature set it enabled at device creation either, and
        // querying the physical device would report features that were never enabled, so
        // leave the struct zeroed - the VulkanMod path does the same
        vulkanManager.setPhysicalDeviceFeatures2(VkPhysicalDeviceFeatures2.calloc().sType$Default());
        // vanilla has its own VMA instance behind VulkanDevice.vma(), but it is a raw
        // handle and sharing it would tie the two allocation lifetimes together
        vulkanManager.setMemoryAllocator(VulkanMemoryAllocator.make(
                vkInstance, physicalDevice, vkDevice, apiVersion, 0
        ));

        VulkanBackendContext backendContext = new VulkanBackendContext();
        backendContext.mInstance = vkInstance;
        backendContext.mPhysicalDevice = physicalDevice;
        backendContext.mDevice = vkDevice;
        backendContext.mQueue = graphicsQueue.vkQueue();
        backendContext.mGraphicsQueueIndex = graphicsQueue.queueFamilyIndex();
        backendContext.mDeviceFeatures2 = vulkanManager.getPhysicalDeviceFeatures2();
        backendContext.mMemoryAllocator = vulkanManager.getMemoryAllocator();

        return backendContext;
    }

    /**
     * Give an existing texture view a component swizzle, which Vulkan can only express at
     * image view creation. Used for the single channel glyph atlas, where the coverage
     * has to arrive in the alpha channel; the OpenGL backend does the same thing with
     * {@code GL_TEXTURE_SWIZZLE_RGBA}.
     * <p>
     * Vanilla has no entry point for this, so the view is rebuilt the way
     * {@code VulkanGpuTextureView} builds its own and written back over the handle. The
     * view remains owned by vanilla and is destroyed by it as usual.
     */
    public static void replaceImageViewWithSwizzle(@Nonnull GpuTextureView textureView, short swizzle) {
        var view = (VulkanGpuTextureView) textureView;
        var texture = view.texture();
        var format = texture.getFormat();
        var vkDevice = ((VulkanDevice) RenderSystem.getDevice().backend).vkDevice();

        VK10.vkDestroyImageView(vkDevice, view.vkImageView, null);

        final long newImageView;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo pCreateInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(0)
                    .image(texture.vkImage())
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VulkanConst.toVk(format));
            pCreateInfo.components().set(
                    VKUtil.toVkComponentSwizzle(Swizzle.getR(swizzle)),
                    VKUtil.toVkComponentSwizzle(Swizzle.getG(swizzle)),
                    VKUtil.toVkComponentSwizzle(Swizzle.getB(swizzle)),
                    VKUtil.toVkComponentSwizzle(Swizzle.getA(swizzle))
            );
            pCreateInfo.subresourceRange()
                    .aspectMask(format.hasColorAspect()
                            ? VK10.VK_IMAGE_ASPECT_COLOR_BIT
                            : VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(view.baseMipLevel())
                    .levelCount(view.mipLevels())
                    .baseArrayLayer(0)
                    .layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            VKUtil._CHECK_(VK10.vkCreateImageView(vkDevice, pCreateInfo, null, pView));
            newImageView = pView.get(0);
        }

        view.vkImageView = newImageView;
    }
}
