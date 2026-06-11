package io.redspace.atlasapi.internal;

import com.mojang.math.Transformation;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.redspace.atlasapi.api.AssetHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The static, non-stack-sensitive item model. It resolves a normal item model JSON that allows vanilla conventions such
 * as {@code parent}, display transforms, etc
 * <p>
 * Geometry is baked once, lazily, on the render thread
 */
public final class SimpleAtlasModel implements ItemModel {
    private final Holder<AssetHandler> handler;
    private final Map<String, Identifier> textureLayers;
    private final ModelRenderProperties properties;
    private final Matrix4fc transformation;

    private @Nullable QuadCollection baked;

    public SimpleAtlasModel(Holder<AssetHandler> handler, Map<String, Identifier> textureLayers, ModelRenderProperties properties, Matrix4fc transformation) {
        this.handler = handler;
        this.textureLayers = textureLayers;
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
        if (this.baked == null) {
            this.baked = AtlasModelBaking.bakeSimpleModel(this.textureLayers, this.handler.value());
        }
        QuadCollection quads = this.baked;

        ItemStackRenderState.LayerRenderState layer = output.newLayer();
        layer.setExtents(() -> CuboidItemModelWrapper.computeExtents(quads.getAll()));
        layer.setLocalTransform(this.transformation);
        this.properties.applyToLayer(layer, displayContext);
        layer.prepareQuadList().addAll(quads.getAll());
        if (quads.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
            output.setAnimated();
        }
    }

    /**
     * Client item model definition for {@code atlas_api:simple_model}.
     */
    public record Unbaked(Identifier handler, Identifier model) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(
                instance -> instance.group(
                        Identifier.CODEC.fieldOf("handler").forGetter(Unbaked::handler),
                        Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model)
                ).apply(instance, Unbaked::new)
        );

        @Override
        public MapCodec<? extends ItemModel.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(this.model);
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            Holder<AssetHandler> handlerHolder = AtlasModelBaking.requireHandler(this.handler);
            ModelBaker baker = context.blockModelBaker();
            ResolvedModel resolvedModel = baker.getModel(this.model);
            TextureSlots slots = resolvedModel.getTopTextureSlots();

            Map<String, Identifier> layers = new LinkedHashMap<>();
            for (String layerName : ItemModelGenerator.LAYERS) {
                Material material = slots.getMaterial(layerName);
                if (material == null) {
                    break;
                }
                layers.put(layerName, material.sprite());
            }

            AtlasModelBaking.ModelRenderSetup setup = AtlasModelBaking.resolveModelSetup(baker, this.model, transformation);
            return new SimpleAtlasModel(handlerHolder, layers, setup.properties(), setup.transformation());
        }
    }
}
