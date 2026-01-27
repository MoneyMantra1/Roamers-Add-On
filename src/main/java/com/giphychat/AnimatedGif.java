package com.giphychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds decoded frames of an animated GIF as registered GPU textures,
 * with per-frame delay for timed playback.
 */
public class AnimatedGif implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<ResourceLocation> frames;
    private final List<Integer> delaysMs;
    private final int totalDurationMs;
    private final long startTime;

    private AnimatedGif(List<ResourceLocation> frames, List<Integer> delaysMs) {
        this.frames = frames;
        this.delaysMs = delaysMs;
        int total = 0;
        for (int d : delaysMs) total += d;
        this.totalDurationMs = total;
        this.startTime = System.currentTimeMillis();
    }

    /** Returns the ResourceLocation for the current frame based on elapsed time. */
    public ResourceLocation currentFrame() {
        if (frames.size() == 1) {
            return frames.get(0);
        }
        long elapsed = (System.currentTimeMillis() - startTime) % totalDurationMs;
        int accumulated = 0;
        for (int i = 0; i < frames.size(); i++) {
            accumulated += delaysMs.get(i);
            if (elapsed < accumulated) {
                return frames.get(i);
            }
        }
        return frames.get(frames.size() - 1);
    }

    public int frameCount() {
        return frames.size();
    }

    @Override
    public void close() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : frames) {
            mc.getTextureManager().release(loc);
        }
        frames.clear();
        delaysMs.clear();
    }

    /**
     * Decodes all frames from GIF bytes, registers each as a DynamicTexture,
     * and returns an AnimatedGif. Must be called on the render thread for
     * texture registration.
     *
     * @param gifBytes raw GIF file bytes
     * @param texturePrefix prefix for ResourceLocation naming
     * @return AnimatedGif, or null if decoding fails
     */
    public static AnimatedGif fromGifBytes(byte[] gifBytes, String texturePrefix) {
        List<ResourceLocation> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        try {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes));
            reader.setInput(stream);

            int numFrames = reader.getNumImages(true);
            if (numFrames <= 0) {
                LOGGER.warn("GIF has no frames");
                return null;
            }

            // Canvas for compositing frames (GIFs can use dispose methods)
            BufferedImage canvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frameImage = reader.read(i);
                if (canvas == null) {
                    canvas = new BufferedImage(frameImage.getWidth(), frameImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
                }

                // Composite this frame onto the canvas
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frameImage, 0, 0, null);
                g.dispose();

                // Extract frame delay from metadata
                int delayMs = extractDelay(reader.getImageMetadata(i));
                delays.add(delayMs);

                // Convert composited frame to PNG -> NativeImage -> DynamicTexture
                BufferedImage snapshot = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
                snapshot.getGraphics().drawImage(canvas, 0, 0, null);
                snapshot.getGraphics().dispose();

                byte[] pngBytes = toPng(snapshot);
                if (pngBytes == null) {
                    cleanup(frames);
                    return null;
                }
                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(pngBytes));
                DynamicTexture texture = new DynamicTexture(nativeImage);
                ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                        .register(texturePrefix + "/frame_" + i, texture);
                frames.add(loc);
            }

            reader.dispose();
            stream.close();
        } catch (IOException e) {
            LOGGER.warn("Failed to decode animated GIF: {}", e.getMessage());
            cleanup(frames);
            return null;
        }

        if (frames.isEmpty()) {
            return null;
        }
        return new AnimatedGif(frames, delays);
    }

    private static int extractDelay(IIOMetadata metadata) {
        try {
            String formatName = "javax_imageio_gif_image_1.0";
            org.w3c.dom.Node root = metadata.getAsTree(formatName);
            NodeList children = root.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node node = children.item(j);
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    String delayStr = node.getAttributes().getNamedItem("delayTime").getNodeValue();
                    int delay = Integer.parseInt(delayStr) * 10; // GIF delay is in 1/100s
                    return delay > 0 ? delay : 100; // default 100ms if 0
                }
            }
        } catch (Exception ignored) {
        }
        return 100; // default 100ms
    }

    private static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void cleanup(List<ResourceLocation> frames) {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : frames) {
            mc.getTextureManager().release(loc);
        }
        frames.clear();
    }
}
