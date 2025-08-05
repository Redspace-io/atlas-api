package io.redspace.atlasapi.internal;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.EmptyModel;

import javax.annotation.Nullable;
import java.util.List;

public class PassthroughBakedModel implements BakedModel {
    public interface BakedModelSupplier {
        record Context(BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel,
                       @Nullable LivingEntity pEntity, int pSeed) {
        }

        BakedModel get(BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel, @Nullable LivingEntity pEntity, int pSeed);
    }

    BakedModel model;
    ItemOverrides overrides;

    private void resolveChild(ItemOverrides.BakedOverride override, BakedModelSupplier.Context ctx) {
        if (override.model != null) {
            override.model.getOverrides().resolve(ctx.pModel, ctx.pStack, ctx.pLevel, ctx.pEntity, ctx.pSeed);
        }
    }

    public PassthroughBakedModel(BakedModelSupplier modelSupplier, ModelBaker baker) {
        this.model = EmptyModel.BAKED;
        var blockmodel = (BlockModel) baker.getModel(ModelBakery.MISSING_MODEL_LOCATION);
        this.overrides = new ItemOverrides(baker, blockmodel,
                List.of()
                , baker.getModelTextureGetter()) {
            @Nullable
            @Override
            public BakedModel resolve(BakedModel pModel, ItemStack pStack, @Nullable ClientLevel pLevel, @Nullable LivingEntity pEntity, int pSeed) {
                BakedModel model = modelSupplier.get(pModel, pStack, pLevel, pEntity, pSeed);
//                model.getOverrides().getOverrides().forEach(ovr -> resolveChild(ovr, new BakedModelSupplier.Context(pModel, pStack, pLevel, pEntity, pSeed)));
                return model/*.getOverrides().resolve(model, pStack, pLevel, pEntity, pSeed)*/;
            }
        };
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
        return overrides;
    }

    @Override
    public ItemTransforms getTransforms() {
        return model.getTransforms();
    }
}
