package laggyboi.vivemonkecraft.client.platform;

import laggyboi.vivemonkecraft.client.VivemonkecraftClient;
import laggyboi.vivemonkecraft.client.VmcCommands;
import laggyboi.vivemonkecraft.client.VmcClothConfig;
import laggyboi.vivemonkecraft.client.VmcConfigScreen;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

// =====================================================================
// LOADER ENTRY POINT                                     [NEOFORGE BODY]
// =====================================================================
//
// The ONLY class that knows what a mod entry point looks like on this loader.
// Everything it does is: register the keybind, register the /vmc command with
// this loader's command event, flush the buffered payload registrations, wire the
// config screen, and hand off to the shared VivemonkecraftClient.
//
// The Fabric branch replaces this file with a ClientModInitializer that does the
// same things through Fabric's events. Nothing else changes between branches.
//
// dist = Dist.CLIENT: this is a client-only mod (the Fabric branch says the same
// with "environment": "client"). It must still be able to CONNECT to servers that
// don't have it — see the optional-channel contract in VmcNet.
// =====================================================================
@Mod(value = "vivemonkecraft", dist = Dist.CLIENT)
public final class VmcBootstrap {

    public VmcBootstrap(IEventBus modEventBus, ModContainer modContainer) {

        // Mod-bus events: payload + keybind registration.
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerKeyMappings);

        // Config screen: NeoForge's equivalent of Mod Menu's config button. Only
        // offered when Cloth Config is present, exactly as on Fabric.
        //
        // The check MUST go through VmcClothConfig, never VmcConfigScreen. Touching
        // any static member of VmcConfigScreen makes the JVM verify it, which
        // resolves the Cloth types in its signatures and throws
        // NoClassDefFoundError: me/shedaniel/clothconfig2/api/AbstractConfigListEntry
        // right here in the constructor, killing mod construction on every instance
        // that doesn't have Cloth installed. (That is exactly what happened the
        // first time this branch was run.) The lambda below is only ever created
        // once we already know Cloth is present.
        if (VmcClothConfig.present()) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (container, parent) -> VmcConfigScreen.create(parent));
        }

        // Game-bus event: the /vmc command. MUST be RegisterClientCommandsEvent, not
        // RegisterCommandsEvent — this is a CLIENT-ONLY mod, so a server-side command
        // registration would only exist on the integrated server and /vmc would
        // silently stop working on dedicated servers. NeoForge dispatches client
        // commands with CommandSourceStack; the shared tree is generic over the
        // source type, so it slots straight in.
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event ->
                event.getDispatcher().register(VmcCommands.<CommandSourceStack>build()));

        // Hand off to the shared client core. This calls VmcNet.register(...), which
        // BUFFERS the payload block for registerPayloads() below to replay.
        new VivemonkecraftClient().init();
    }

    @SubscribeEvent
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Version "1" is this mod's own protocol version, unrelated to the mod
        // version. Bump it only if the wire format of a payload changes.
        VmcNet.flush(event.registrar("1"));
    }

    @SubscribeEvent
    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(VmcKeybinds.TOGGLE);
        event.register(VmcKeybinds.CYCLE_MODE);
    }
}
