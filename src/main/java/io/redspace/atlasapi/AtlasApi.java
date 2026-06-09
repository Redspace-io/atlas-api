package io.redspace.atlasapi;

import com.mojang.logging.LogUtils;
import io.redspace.atlasapi.internal.ClientManager;
import io.redspace.atlasapi.internal.DynamicAtlasModel;
import io.redspace.atlasapi.internal.SimpleAtlasModel;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import org.slf4j.Logger;

import static io.redspace.atlasapi.api.AtlasApiRegistry.ASSET_HANDLER_REGISTRY;

@Mod(AtlasApi.MODID)
public class AtlasApi {
    public static final String MODID = "atlas_api";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AtlasApi(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerItemModels);
        modEventBus.addListener(this::registerClientListeners);
        modEventBus.addListener(this::registerRegistries);
        NeoForge.EVENT_BUS.addListener(this::onLogOut);
        NeoForge.EVENT_BUS.addListener(this::onLogIn);
    }

    public void onLogOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientManager.clear();
    }

    public void onLogIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Warm up each atlas on the render thread so the first item render isn't stalled by stitch + GPU upload.
        ASSET_HANDLER_REGISTRY.stream().forEach(handler -> ClientManager.getAtlas(handler).ensureUploaded());
    }

    public void registerRegistries(NewRegistryEvent event) {
        event.register(ASSET_HANDLER_REGISTRY);
    }

    public void registerClientListeners(AddClientReloadListenersEvent event) {
        event.addListener(id("client_manager"), new ClientManager());
    }

    public void registerItemModels(RegisterItemModelsEvent event) {
        event.register(id("dynamic_model"), DynamicAtlasModel.Unbaked.MAP_CODEC);
        event.register(id("simple_model"), SimpleAtlasModel.Unbaked.MAP_CODEC);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(AtlasApi.MODID, path);
    }
}
