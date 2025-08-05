package io.redspace.atlasapi.internal;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.math.Transformation;
import io.redspace.atlasapi.AtlasApi;
import io.redspace.atlasapi.api.AssetHandler;
import io.redspace.atlasapi.api.AtlasApiRegistry;
import io.redspace.atlasapi.api.data.BakingPreparations;
import io.redspace.atlasapi.api.data.ModelLayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.NeoForgeRenderTypes;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.model.CompositeModel;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.SimpleModelState;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public class DynamicAtlasModel implements IUnbakedGeometry<DynamicAtlasModel> {
    final Holder<AssetHandler> handler;

    public DynamicAtlasModel(Holder<AssetHandler> handler) {
        this.handler = handler;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        return new Baked((BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel, @Nullable LivingEntity pEntity, int pSeed) -> {
            var _handler = handler.value();
            int id = _handler.modelId(pStack, pLevel, pEntity, pSeed);
            return ClientManager.getModelOrCompute(handler.getKey().location(), id, (i) -> bake(_handler, _handler.makeBakedModelPreparations(pStack, pLevel, pEntity, pSeed), context, modelState, overrides));
        }, baker);
    }

    public static BakedModel bake(AssetHandler handler, BakingPreparations preparations, IGeometryBakingContext context, ModelState modelState, ItemOverrides overrides) {
        AtlasApi.LOGGER.debug("DynamicAtlasModel bake: {}", preparations);
        var atlas = ClientManager.getAtlas(handler);
        var layers = preparations.layers().stream().sorted(Comparator.comparingInt(ModelLayer::drawOrder)).toList();
        if (!layers.isEmpty()) {
            TextureAtlasSprite particle = atlas.getSprite(layers.getFirst().spriteLocation());
            CompositeModel.Baked.Builder builder = CompositeModel.Baked.builder(context, particle, overrides, context.getTransforms());
            Transformation rootTransform = context.getRootTransform();
            for (int i = 0; i < layers.size(); i++) {
                var layer = layers.get(i);

                TextureAtlasSprite sprite = atlas.getSprite(layer.spriteLocation());
                Transformation transformation = layer.transformation().orElse(new Transformation(
                        new Vector3f(0, 0, 0),
                        new Quaternionf(), new Vector3f(1, 1, 1),
                        new Quaternionf())
                );

                ModelState subState = new SimpleModelState(modelState.getRotation().compose(rootTransform.compose(transformation)), modelState.isUvLocked());

                List<BlockElement> unbaked = UnbakedGeometryHelper.createUnbakedItemElements(i, sprite);
                List<BakedQuad> quads = UnbakedGeometryHelper.bakeElements(unbaked, (material2) -> sprite, subState);
                RenderTypeGroup renderTypes = new RenderTypeGroup(RenderType.solid(), NeoForgeRenderTypes.getUnsortedTranslucent(handler.getAtlasLocation()));

                builder.addQuads(renderTypes, quads);
            }
            return builder.build();
        }
        return EmptyModel.BAKED;

    }

    /**
     * A no-op model that delegates model baking/lookups to the itemstack sensitive {@link BakedModel#getOverrides()}, which defers to AtlasAPI's model cache
     */
    public static class Baked implements BakedModel {
        public interface BakedModelSupplier {
            BakedModel get(BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel, @Nullable LivingEntity pEntity, int pSeed);
        }

        BakedModel noopModel;
        ItemOverrides overrides;

        public Baked(BakedModelSupplier modelSupplier, ModelBaker baker) {
            this.noopModel = EmptyModel.BAKED;
            var blockmodel = (BlockModel) baker.getModel(ModelBakery.MISSING_MODEL_LOCATION);
            this.overrides = new ItemOverrides(baker, blockmodel,
                    List.of()
                    , baker.getModelTextureGetter()) {
                @Nullable
                @Override
                public BakedModel resolve(BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel, @Nullable LivingEntity pEntity, int pSeed) {
                    return modelSupplier.get(pModel, pStack, pLevel, pEntity, pSeed);
                }
            };
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState pState, @Nullable Direction pDirection, RandomSource pRandom) {
            return noopModel.getQuads(pState, pDirection, pRandom);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return noopModel.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return noopModel.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return noopModel.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return noopModel.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return noopModel.getParticleIcon();
        }

        @Override
        public ItemOverrides getOverrides() {
            return overrides;
        }

        @Override
        public ItemTransforms getTransforms() {
            return noopModel.getTransforms();
        }
    }

    public static final class Loader implements IGeometryLoader<DynamicAtlasModel> {
        public static final Loader INSTANCE = new Loader();

        private Loader() {
        }

        @Override
        public DynamicAtlasModel read(JsonObject jsonObject, JsonDeserializationContext deserializationContext) {
            try {
                String typestring = jsonObject.get("handler").getAsString();
                Holder<AssetHandler> type = AtlasApiRegistry.ASSET_HANDLER_REGISTRY.getHolderOrThrow(ResourceKey.create(AtlasApiRegistry.ASSET_HANDLER_REGISTRY_KEY, ResourceLocation.parse(typestring)));
                return new DynamicAtlasModel(type);
            } catch (Exception e) {
                throw new JsonParseException(e.getMessage());
            }
        }
    }
}
