package io.redspace.atlasapi.internal;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.redspace.atlasapi.api.AssetHandler;
import io.redspace.atlasapi.api.AtlasApiRegistry;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class SimpleAtlasModel implements IUnbakedGeometry<SimpleAtlasModel> {
    private final BlockModel unbakedGeometry;
    private final Holder<AssetHandler> handler;

    public SimpleAtlasModel(BlockModel unbakedGeometry, Holder<AssetHandler> handler) {
        this.unbakedGeometry = unbakedGeometry;
        this.handler = handler;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        return new Baked(handler,
                () -> {
                    var map = new HashMap<>(unbakedGeometry.textureMap);
                    map.forEach((string, mat) -> unbakedGeometry.textureMap.put(string, mat.mapBoth(material -> new Material(handler.value().getAtlasLocation(), material.texture()), Function.identity())));
                    return unbakedGeometry.bake(baker, m -> handler.value().getSprite(m.texture()), modelState);
                }, unbakedGeometry
        );
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
        unbakedGeometry.resolveParents(modelGetter);
    }

    /**
     * A Lazy-Loaded and cached model holder that bakes the model when it is first rendered. Also ensures atlas usage is correct
     */
    public static class Baked implements BakedModel {
        BakedModel model;
        private final Holder<AssetHandler> handler;
        /** Simple Models are non-itemstack-sensitive, meaning no args are required */
        Supplier<BakedModel> bakery;
        boolean baked = false;
        /** Item Transforms are applied before the simple model is cached, meaning we rely on what is in the json for the first frame of rendering. After caching, we defer to the cache */
        ItemTransforms defaultTransforms;

        @Override
        public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
            if (!baked) {
                this.model = bakery.get();
                bakery = null;
                defaultTransforms = null;
                baked = true;
            }
            return BakedModel.super.getRenderPasses(itemStack, fabulous);
        }

        public Baked(Holder<AssetHandler> handler, Supplier<BakedModel> bakery, BlockModel context) {
            this.model = EmptyModel.BAKED;
            this.bakery = bakery;
            this.handler = handler;
            this.defaultTransforms = context.getTransforms();
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState pState, @Nullable Direction pDirection, RandomSource pRandom) {
            return model.getQuads(pState, pDirection, pRandom);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return model.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return model.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return model.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return model.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return model.getParticleIcon();
        }

        @Override
        public ItemOverrides getOverrides() {
            return model.getOverrides();
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
            return List.of(RenderType.entityCutout(handler.value().getAtlasLocation()));
        }

        @Override
        public ItemTransforms getTransforms() {
            return baked ? model.getTransforms() : defaultTransforms;
        }
    }

    public static final class Loader implements IGeometryLoader<SimpleAtlasModel> {
        public static final SimpleAtlasModel.Loader INSTANCE = new SimpleAtlasModel.Loader();

        private Loader() {
        }

        @Override
        public SimpleAtlasModel read(JsonObject jsonObject, JsonDeserializationContext deserializationContext) {
            try {
                BlockModel baseModel = new BlockModel.Deserializer().deserialize(jsonObject, BlockModel.class, deserializationContext);
                String typestring = jsonObject.get("handler").getAsString();
                Holder<AssetHandler> type = AtlasApiRegistry.ASSET_HANDLER_REGISTRY.getHolderOrThrow(ResourceKey.create(AtlasApiRegistry.ASSET_HANDLER_REGISTRY_KEY, ResourceLocation.parse(typestring)));
                return new SimpleAtlasModel(baseModel, type);
            } catch (Exception e) {
                throw new JsonParseException(e.getMessage());
            }
        }
    }
}