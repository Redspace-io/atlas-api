package io.redspace.atlasapi.internal;

import com.mojang.blaze3d.systems.RenderSystem;
import io.redspace.atlasapi.AtlasApi;
import io.redspace.atlasapi.api.AssetHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link TextureAtlas} stitched at runtime from an {@link AssetHandler}'s sprite sources.
 * <p>
 * The 26.x rendering pipeline bakes models on background worker threads, so atlas work is split into two phases:
 * <ul>
 *     <li>{@link #ensureStitched()} - CPU-only stitching that yields {@link TextureAtlasSprite} UV data. Safe on any
 *     thread; used by model baking to resolve sprite coordinates.</li>
 *     <li>{@link #ensureUploaded()} - GPU upload, which must run on the render thread.</li>
 * </ul>
 * Sprites are served from the stitched region map directly, so lookups never require the GPU texture to exist yet.
 */
public class DynamicAtlas extends TextureAtlas {
    private final AssetHandler handler;

    private volatile boolean stitched = false;
    private volatile boolean uploaded = false;
    private SpriteLoader.Preparations preparations;
    private Map<Identifier, TextureAtlasSprite> regions = Map.of();
    private TextureAtlasSprite missing;

    public DynamicAtlas(AssetHandler handler, TextureManager textureManager) {
        super(handler.getAtlasLocation());
        this.handler = handler;
        textureManager.register(this.location(), this);
    }

    /**
     * True once GPU contents have been uploaded for the current stitch.
     */
    public boolean isUploaded() {
        return uploaded;
    }

    /**
     * CPU-only stitch. Safe to call from any thread; produces sprite UV data usable for baking.
     */
    public synchronized void ensureStitched() {
        if (stitched) {
            return;
        }
        AtlasApi.LOGGER.info("Atlas {}: stitching", this.location());
        long millis = System.currentTimeMillis();
        SpriteLoader loader = new SpriteLoader(this.location(), this.maxSupportedTextureSize());
        SpriteResourceLoader spriteResourceLoader = SpriteResourceLoader.create(Set.of());
        SpriteSourceList sources = new SpriteSourceList(handler.buildSpriteSources());
        var factories = sources.list(Minecraft.getInstance().getResourceManager(), Set.of());
        List<SpriteContents> contents = factories.stream()
                .map(factory -> factory.get(spriteResourceLoader))
                .filter(Objects::nonNull)
                .toList();
        SpriteLoader.Preparations prep = loader.stitch(contents, 0, Runnable::run);
        prep.readyForUpload().join();
        this.preparations = prep;
        this.regions = prep.regions();
        this.missing = prep.missing();
        this.stitched = true;
        AtlasApi.LOGGER.info("Atlas {}: stitched ({}x{}, {} sprites, {} ms)", this.location(), prep.width(), prep.height(), prep.regions().size(), System.currentTimeMillis() - millis);
    }

    /**
     * Uploads stitched contents to the GPU. Must be invoked on the render thread; no-ops otherwise.
     */
    public void ensureUploaded() {
        if (uploaded) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        ensureStitched();
        this.upload(this.preparations);
        this.uploaded = true;
        AtlasApi.LOGGER.info("Atlas {}: uploaded to GPU ({}x{})", this.location(), this.getWidth(), this.getHeight());
    }

    /**
     * Drops both stitch and GPU state. The GPU release requires the render thread.
     */
    public synchronized void reset() {
        if (uploaded) {
            clearTextureData();
            uploaded = false;
        }
        stitched = false;
        preparations = null;
        regions = Map.of();
        missing = null;
    }

    @Override
    public @NonNull TextureAtlasSprite getSprite(@NonNull Identifier location) {
        ensureStitched();
        TextureAtlasSprite sprite = regions.get(location);
        return sprite != null ? sprite : missing;
    }
}
