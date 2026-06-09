package io.redspace.atlasapi.internal;

import com.mojang.math.Transformation;
import io.redspace.atlasapi.AtlasApi;
import io.redspace.atlasapi.api.AssetHandler;
import io.redspace.atlasapi.api.AtlasApiRegistry;
import io.redspace.atlasapi.api.data.BakingPreparations;
import io.redspace.atlasapi.api.data.ModelLayer;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.ComposedModelState;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3fc;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared runtime baking helpers that turn {@link AssetHandler} sprite layers into {@link QuadCollection}s bound to the
 * handler's dynamic atlas.
 * <p>
 * All methods here are intended to be called from the render thread (lazily, at first render), so the atlas is
 * guaranteed stitched and uploaded. The result is cached by the caller and reused until a reload/logout.
 */
final class AtlasModelBaking {
    private AtlasModelBaking() {
    }

    static Holder<AssetHandler> requireHandler(Identifier handlerId) {
        return AtlasApiRegistry.ASSET_HANDLER_REGISTRY.get(handlerId)
                .orElseThrow(() -> new IllegalStateException("Unknown asset handler: " + handlerId));
    }

    /**
     * (render thread) Resolves and uploads the handler's atlas
     */
    static DynamicAtlas atlasFor(AssetHandler handler) {
        DynamicAtlas atlas = ClientManager.getAtlas(handler);
        atlas.ensureUploaded();
        return atlas;
    }

    static QuadCollection bakeDynamicModel(AssetHandler handler, BakingPreparations preparations, ModelState modelState, Transformation rootTransform) {
        AtlasApi.LOGGER.debug("DynamicAtlasModel bake: {}", preparations);
        DynamicAtlas atlas = atlasFor(handler);
        List<ModelLayer> layers = preparations.layers().stream().sorted(Comparator.comparingInt(ModelLayer::drawOrder)).toList();
        if (layers.isEmpty()) {
            return QuadCollection.EMPTY;
        }

        ModelBaker baker = new RuntimeModelBaker(handler.getAtlasLocation());
        QuadCollection.Builder builder = new QuadCollection.Builder();
        for (int i = 0; i < layers.size(); i++) {
            ModelLayer layer = layers.get(i);
            Material.Baked material = new Material.Baked(atlas.getSprite(layer.spriteLocation()), false);
            Transformation layerTransform = layer.transformation().orElse(Transformation.IDENTITY);
            ModelState subState = new ComposedModelState(modelState, rootTransform.compose(layerTransform));
            builder.addAll(baker.compute(new ItemModelGenerator.ItemLayerKey(material, subState, i)));
        }
        return builder.build();
    }

    static QuadCollection bakeSimpleModel(Map<String, Identifier> textureLayers, AssetHandler handler) {
        DynamicAtlas atlas = atlasFor(handler);
        ModelBaker baker = new RuntimeModelBaker(handler.getAtlasLocation());
        QuadCollection.Builder builder = new QuadCollection.Builder();
        boolean any = false;
        for (int layerIndex = 0; layerIndex < ItemModelGenerator.LAYERS.size(); layerIndex++) {
            String layerName = ItemModelGenerator.LAYERS.get(layerIndex);
            Identifier spriteLocation = textureLayers.get(layerName);
            if (spriteLocation == null) {
                break;
            }
            Material.Baked material = new Material.Baked(atlas.getSprite(spriteLocation), false);
            builder.addAll(baker.compute(new ItemModelGenerator.ItemLayerKey(material, BlockModelRotation.IDENTITY, layerIndex)));
            any = true;
        }
        return any ? builder.build() : QuadCollection.EMPTY;
    }

    /**
     * Minimal {@link ModelBaker} for runtime item-layer quad generation. It does not resolve models by id; it only
     * computes layer geometry and rebinds {@link BakedQuad.MaterialInfo} render types to the dynamic atlas so the
     * quads sample the correct (custom) texture.
     */
    private static final class RuntimeModelBaker implements ModelBaker {
        private final Map<SharedOperationKey<?>, Object> cache = new HashMap<>();
        private final Interner interner;

        private RuntimeModelBaker(Identifier atlasLocation) {
            this.interner = new SimpleInterner(atlasLocation);
        }

        @Override
        public net.minecraft.client.resources.model.ResolvedModel getModel(Identifier location) {
            throw new UnsupportedOperationException("Runtime atlas baking does not resolve models by id");
        }

        @Override
        public net.minecraft.client.renderer.block.dispatch.BlockStateModelPart missingBlockModelPart() {
            throw new UnsupportedOperationException("Runtime atlas baking does not provide block model parts");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T compute(SharedOperationKey<T> key) {
            return (T) cache.computeIfAbsent(key, k -> k.compute(this));
        }

        @Override
        public MaterialBaker materials() {
            throw new UnsupportedOperationException("Runtime atlas baking resolves materials directly");
        }

        @Override
        public Interner interner() {
            return interner;
        }

        private record SimpleInterner(Identifier atlasLocation) implements ModelBaker.Interner {
            @Override
            public Vector3fc vector(Vector3fc vector) {
                return vector;
            }

            @Override
            public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
                return new BakedQuad.MaterialInfo(
                        material.sprite(),
                        material.layer(),
                        RenderTypes.itemTranslucent(atlasLocation),
                        material.tintIndex(),
                        material.shade(),
                        material.lightEmission(),
                        material.ambientOcclusion()
                );
            }

            @Override
            public BakedNormals normals(BakedNormals normals) {
                return normals;
            }

            @Override
            public BakedColors colors(BakedColors colors) {
                return colors;
            }
        }
    }
}
