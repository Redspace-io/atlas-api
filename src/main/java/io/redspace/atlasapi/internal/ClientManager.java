package io.redspace.atlasapi.internal;

import io.redspace.atlasapi.api.AssetHandler;
import io.redspace.atlasapi.api.AtlasApiRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class ClientManager implements ResourceManagerReloadListener {
    private static final Map<Identifier, DynamicAtlas> ATLASES = new HashMap<>();
    private static final Map<Identifier, Map<Integer, QuadCollection>> MODEL_CACHE = new HashMap<>();

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // Render thread (apply phase). Drop stale GPU + cache state; everything rebuilds lazily on next render.
        ATLASES.values().forEach(DynamicAtlas::reset);
        MODEL_CACHE.clear();
    }

    public static QuadCollection getQuadsOrCompute(Identifier handlerId, int modelId, Function<Integer, QuadCollection> bakery) {
        if (!AtlasApiRegistry.ASSET_HANDLER_REGISTRY.containsKey(handlerId)) {
            throw new IllegalStateException("Invalid Asset Handler key: " + handlerId);
        }
        return MODEL_CACHE.computeIfAbsent(handlerId, ignored -> new HashMap<>()).computeIfAbsent(modelId, bakery);
    }

    public static DynamicAtlas getAtlas(AssetHandler assetHandler) {
        return ATLASES.computeIfAbsent(assetHandler.getAtlasLocation(), ignored -> new DynamicAtlas(assetHandler, Minecraft.getInstance().getTextureManager()));
    }

    public static void clear() {
        ATLASES.values().forEach(DynamicAtlas::reset);
        MODEL_CACHE.clear();
    }
}
