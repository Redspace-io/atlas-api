package io.redspace.atlasapi.internal;

import com.mojang.math.Transformation;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.redspace.atlasapi.api.AssetHandler;
import io.redspace.atlasapi.api.data.BakingPreparations;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * The item-stack-sensitive dynamic item model.
 * <p>
 * All atlas/baking work happens in {@link #update} on the render thread. On each render it asks the
 * {@link AssetHandler} for a {@code modelId} plus {@link BakingPreparations}, bakes/caches a
 * {@link QuadCollection} keyed by {@code (handler, modelId)}, and submits it as a single layer of the
 * {@link ItemStackRenderState}. The handler's atlas is stitched and uploaded lazily as part of baking.
 * <p>
 * An optional {@code model} reference supplies vanilla display conventions (parent chain, handheld/gui transforms,
 * gui light) exactly as {@link SimpleAtlasModel} does for static models.
 */
public final class DynamicAtlasModel implements ItemModel {
    private static final Identifier DEFAULT_MODEL = Identifier.withDefaultNamespace("item/generated");

    private final Holder<AssetHandler> handler;
    private final ModelRenderProperties properties;
    private final Matrix4fc transformation;

    public DynamicAtlasModel(Holder<AssetHandler> handler, ModelRenderProperties properties, Matrix4fc transformation) {
        this.handler = handler;
        this.properties = properties;
        this.transformation = transformation;
    }

    @Override
    public void update(
            ItemStackRenderState output,
            ItemStack item,
            ItemModelResolver resolver,
            ItemDisplayContext displayContext,
            @Nullable ClientLevel level,
            @Nullable ItemOwner owner,
            int seed
    ) {
        output.appendModelIdentityElement(this);
        AssetHandler assetHandler = this.handler.value();
        LivingEntity livingEntity = owner == null ? null : owner.asLivingEntity();
        int modelId = assetHandler.modelId(item, level, livingEntity, seed);
        output.appendModelIdentityElement(modelId);

        Identifier handlerId = Objects.requireNonNull(this.handler.unwrapKey().orElseThrow().identifier());
        QuadCollection quads = ClientManager.getQuadsOrCompute(
                handlerId,
                modelId,
                ignored -> AtlasModelBaking.bakeDynamicModel(
                        assetHandler,
                        assetHandler.makeBakedModelPreparations(item, level, livingEntity, seed),
                        BlockModelRotation.IDENTITY,
                        Transformation.IDENTITY
                )
        );

        if (quads.getAll().isEmpty()) {
            return;
        }

        ItemStackRenderState.LayerRenderState layer = output.newLayer();
        layer.setExtents(() -> CuboidItemModelWrapper.computeExtents(quads.getAll()));
        layer.setLocalTransform(this.transformation);
        this.properties.applyToLayer(layer, displayContext);
        layer.prepareQuadList().addAll(quads.getAll());
        if (quads.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
            output.setAnimated();
        }
        Material.Baked particle = new Material.Baked(quads.getAll().getFirst().materialInfo().sprite(), false);
        layer.setParticleMaterial(particle);
    }

    public record Unbaked(Identifier handler, Optional<Identifier> model) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(
                instance -> instance.group(
                        Identifier.CODEC.fieldOf("handler").forGetter(Unbaked::handler),
                        Identifier.CODEC.optionalFieldOf("model").forGetter(Unbaked::model)
                ).apply(instance, Unbaked::new)
        );

        public Identifier resolvedModel() {
            return this.model.orElse(DEFAULT_MODEL);
        }

        @Override
        public MapCodec<? extends ItemModel.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(this.resolvedModel());
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            Holder<AssetHandler> handlerHolder = AtlasModelBaking.requireHandler(this.handler);
            ModelBaker baker = context.blockModelBaker();
            AtlasModelBaking.ModelRenderSetup setup = AtlasModelBaking.resolveModelSetup(baker, this.resolvedModel(), transformation);
            return new DynamicAtlasModel(handlerHolder, setup.properties(), setup.transformation());
        }
    }
}
