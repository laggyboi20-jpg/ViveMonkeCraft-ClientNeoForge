package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.platform.VmcEvents;
import laggyboi.vivemonkecraft.client.platform.VmcKeybinds;
import laggyboi.vivemonkecraft.client.platform.VmcNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// =====================================================================
// CLIENT CORE — loader-neutral
// =====================================================================
//
// This class is SHARED VERBATIM by the Fabric, NeoForge and Forge dev-test
// branches. It talks to the loader only through the four platform classes
// (VmcEvents / VmcNet / VmcKeybinds / VmcPlatform), so a feature added here on
// the base branch merges into the loader branches with no conflict.
//
// init() is called once by each loader's own bootstrap class (VmcBootstrap),
// which is the only file that knows what a mod entry point looks like.
// =====================================================================

public class VivemonkecraftClient {

    // Is gorilla locomotion currently on? Static so the mixins can read it.
    // Starts OFF — auto-enabled once when the player joins a world (see onEndTick).
    private static boolean enabled = false;

    // Whether we have already auto-enabled this world session.
    // Reset to false on disconnect so we auto-start again next time a world loads.
    private boolean autoStarted = false;

    // VR-active state last tick — used to edge-trigger auto enable/disable so the mod
    // turns on when a headset appears and off when it goes away.
    private boolean vrWasActive = false;

    // Ticks to wait after joining before auto-enabling the mod.
    // This gives the server's config packet time to arrive so we know whether the
    // server runs the companion mod (monke-server) BEFORE we decide.
    // 10 ticks = 500 ms — plenty of time even over a WAN connection.
    private static final int PACKET_WAIT_TICKS = 10;

    // Counts ticks since the last JOIN event.  Incremented each tick until
    // autoStarted becomes true, then ignored.  Reset to 0 on JOIN and DISCONNECT.
    private int ticksSinceJoin = 0;

    // Whether the "this server doesn't run the ViveMonke server mod" notice has
    // already been shown this connection (shown at most once per join).
    private boolean warnedNoServerMod = false;

    // Last applied "Real Monke" state — re-applies the collision box / server
    // shrink when the setting (or the mod's enabled state) flips mid-game.
    private boolean lastRealMonke = false;

    // Last announced "monke model" (legless look) state.
    private boolean lastMonkeModel = false;

    // Whether we last told a DEDICATED server we're gripping (for the no-fall-damage
    // slide). Tracks state so we send one "released" packet when gripping ends rather
    // than spamming it. Singleplayer/LAN host doesn't use this (handled in the handler).
    private boolean wallSlideSent = false;

    // Whether a GUI screen was open last tick — used to drop grips exactly once
    // when a screen opens (inventory/chat/settings must not move the player).
    private boolean wasInGui = false;

    // The dimension the player was in last tick. Changing dimension (e.g. to the
    // Nether) rebuilds the player entity, which recomputes its collision box ONCE
    // and then caches it — so the Real Monke shrink silently reverts to full size
    // until something forces a refresh (the in-game "toggle off+on" fix). We watch
    // for the change and re-apply automatically.
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDimension = null;

    // The player ENTITY instance last tick. Respawn (and dimension change) replace it
    // with a new LocalPlayer; when that happens we re-apply Real Monke / monke model,
    // otherwise the rebuilt entity reverts to full 2-block height after dying.
    private net.minecraft.client.player.LocalPlayer lastPlayerRef = null;

    // The physics handler.
    private GorillaLocomotionHandler handler;

    // Toggle keybind — UNBOUND by default (no key assigned out of the box).
    // Constructed and registered by VmcKeybinds (registration is loader-specific).
    // To toggle via Vivecraft radial menu: go to VR Settings -> Radial Menu and assign
    // the "ViveMonkeCraft: Toggle" keybind to a radial slot.
    // To toggle via keyboard: rebind in Options -> Controls -> Miscellaneous.
    private final KeyMapping toggleKey = VmcKeybinds.TOGGLE;

    // Set by init() so the /vmc command (VmcCommands, which has no instance) can
    // reach the live handler through the cmd* statics below.
    private static VivemonkecraftClient instance;

    /** Called once by the loader's bootstrap. */
    public void init() {

        instance = this;
        handler  = new GorillaLocomotionHandler();

        // Register the per-frame hand marker renderer (replaces old particles).
        HandMarkerRenderer.register();

        // Register the camera stabilization vignette (motion-sickness reduction).
        CameraStabilizationRenderer.register();

        // Load the editable config at startup (creates it the first time).
        MovementConfig.load();

        // ---- PAYLOADS ----
        // All of these are OPTIONAL channels: the client must still connect to a
        // server that has none of them, because "no ServerConfigPayload arrived" is
        // exactly how we detect a server that hasn't opted in. See VmcNet's contract.
        //
        // Registration must happen during init, before any world joins. The embedded
        // server handlers (serverbound) are registered here too — when THIS client
        // hosts (singleplayer / Open-to-LAN / Essential) its integrated server plays
        // the role of monke-server, so two players who both have this mod can play
        // over LAN with NO separate server jar. Those handlers simply never fire when
        // connected to a remote server.
        VmcNet.register(reg -> {

            // When the server companion mod (monke-server) sends its config, store the
            // limits. Receiving this packet IS the multiplayer authorization: without it
            // the mod refuses to enable on a dedicated server (see serverAuthorized).
            reg.clientbound(ServerConfigPayload.ID, ServerConfigPayload.STREAM_CODEC,
                payload -> {
                    Minecraft client = Minecraft.getInstance();
                    ServerLimits.apply(payload);
                    VmcDebugLog.event("NET", "← ServerConfig modEnabled=" + payload.modEnabled()
                            + " (server companion present → authorized)");

                    if (!payload.modEnabled()) {
                        // Server banned the mod — force-disable immediately, even
                        // if auto-start already fired before the packet arrived.
                        if (enabled) {
                            enabled = false;
                            handler.onDisable(client);
                        }
                        if (client.player != null) {
                            client.gui.hud.setOverlayMessage(
                                Component.literal("§e[ViveMonkeCraft] §cDisabled by server"),
                                false
                            );
                        }
                    } else if (autoStarted && !enabled) {
                        // Authorization arrived AFTER the auto-start window closed
                        // (slow connection) — turn the mod on now.
                        applyEnabled(true);
                    }
                });

            // Monke model sync: we announce our legless look (C2S) and receive
            // everyone else's (S2C broadcast from monke-server or our own LAN host).
            reg.clientbound(MonkeModelS2CPayload.ID, MonkeModelS2CPayload.STREAM_CODEC,
                payload -> MonkeModelClientSet.set(payload.player(), payload.enabled()));

            // Real Monke: C2S request asking monke-server to shrink our hitbox height.
            reg.serverbound(RealMonkeC2SPayload.ID, RealMonkeC2SPayload.STREAM_CODEC,
                    EmbeddedServerLogic::onRealMonke);

            // Monke Model: C2S announce; the host tracks + rebroadcasts it.
            reg.serverbound(MonkeModelC2SPayload.ID, MonkeModelC2SPayload.STREAM_CODEC,
                    EmbeddedServerLogic::onMonkeModel);

            // Wall slide: C2S keepalive telling monke-server we're gripping, so it zeroes
            // our (server-authoritative) fall distance — the dedicated-server half of the
            // no-fall-damage slide. Singleplayer/LAN host handles this in the handler by
            // resetting the integrated server player directly, so it's dedicated-only and
            // needs no handler on this side.
            reg.serverboundNoHandler(WallSlideC2SPayload.ID, WallSlideC2SPayload.STREAM_CODEC);

            // Magma touch: C2S signal telling monke-server to apply hot-floor damage while
            // a hand grips a magma block (server-authoritative, so dedicated-only —
            // singleplayer hurts the integrated server player directly in the handler).
            reg.serverboundNoHandler(MagmaTouchC2SPayload.ID, MagmaTouchC2SPayload.STREAM_CODEC);
        });

        // Join/disconnect handlers for our own integrated server (LAN host role).
        EmbeddedServerLogic.register();

        // Reset state when the player joins a world so the tick handler will
        // fire applyEnabled(true) once the player entity is ready (after the
        // PACKET_WAIT_TICKS grace window).
        VmcEvents.onClientJoin(() -> {
            autoStarted       = false;
            ticksSinceJoin    = 0;
            warnedNoServerMod = false;
            lastRealMonke     = false;   // re-apply the shrink after the join settles
            lastMonkeModel    = false;   // re-announce the legless look too
        });

        // On disconnect: turn off gorilla locomotion, clear server limits, and reset
        // counters so the next world join auto-starts again after the grace window.
        VmcEvents.onClientDisconnect(() -> {
            Minecraft client = Minecraft.getInstance();
            autoStarted       = false;
            ticksSinceJoin    = 0;
            warnedNoServerMod = false;
            lastRealMonke     = false;
            lastMonkeModel    = false;
            wasInGui          = false;
            lastDimension     = null;
            wallSlideSent     = false;   // next world starts with no pending grip state
            vrWasActive       = false;   // re-evaluate VR presence fresh next world
            MonkeModelClientSet.clear(); // stale legless flags don't carry over
            ServerLimits.reset();   // clear caps + authorization for the next world
            if (enabled) {
                enabled = false;
                handler.onDisable(client);
            }
        });

        // Run our logic at the end of every client tick.
        VmcEvents.onClientTick(this::onEndTick);

        // The /vmc chat command tree lives in VmcCommands (shared, generic over the
        // brigadier source type). Each loader bootstrap registers it, because only
        // the bootstrap knows which command event and source type its loader uses.
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // -----------------------------------------------------------------------
    // Hooks for VmcCommands (which is static and has no instance)
    // -----------------------------------------------------------------------

    static void cmdToggle()                 { if (instance != null) instance.toggle(); }
    static void cmdSetEnabled(boolean on)   { if (instance != null) instance.applyEnabled(on); }
    static void cmdReload()                 { if (instance != null) instance.reloadConfig(); }

    // -----------------------------------------------------------------------
    // Per-tick: keybind handling + physics
    // -----------------------------------------------------------------------

    private void onEndTick(Minecraft client) {
        // Auto-start: wait PACKET_WAIT_TICKS ticks after joining so the server's
        // config packet has time to arrive before we decide to enable.
        // Singleplayer enables unconditionally. On a dedicated server, no packet =
        // no companion mod = the mod STAYS OFF (server-side opt-in, Modrinth policy);
        // the player gets a one-time chat notice explaining why.
        // Done here rather than in JOIN because on QuestCraft the player entity
        // may not be fully constructed yet at the moment JOIN fires.
        if (!autoStarted && client.player != null) {
            ticksSinceJoin++;
            if (ticksSinceJoin >= PACKET_WAIT_TICKS) {
                autoStarted = true;
                // Actually enabling is handled by the VR watcher below (it requires a
                // headset). Here we only warn once if the server hasn't opted in.
                if (!serverAuthorized(client) && !warnedNoServerMod) {
                    warnedNoServerMod = true;
                    // 26.1 removed Player.displayClientMessage(Component, boolean);
                    // sendSystemMessage(Component) is the chat-message equivalent (the old
                    // boolean=false meant "chat, not action bar").
                    client.player.sendSystemMessage(Component.literal(
                        "§e[ViveMonkeCraft] §cThis server doesn't run the monke-server "
                        + "companion mod, so gorilla locomotion is disabled here. "
                        + "§7(Server admins: install the ViveMonke server mod to allow it. Download: github.com/laggyboi20-jpg/ViveMonkeCraft-Server)"));
                }
            }
        }

        // VR PRESENCE DRIVES THE MOD: auto-ON when a headset becomes active (and the
        // server allows it), auto-OFF when VR goes away — so it's inert in plain desktop
        // Minecraft and springs to life in VR. Edge-triggered (acts only on the VR
        // on/off transition), so you can still manually toggle it off within a VR
        // session without it snapping back on every tick.
        boolean vrActive = VivecraftBridge.isVrActive();
        if (autoStarted && client.player != null) {
            if (vrActive && !vrWasActive) {
                VmcDebugLog.event("VR", "headset ACTIVE");
                if (!enabled && serverAuthorized(client) && ServerLimits.modEnabled) applyEnabled(true);
            } else if (!vrActive && vrWasActive) {
                VmcDebugLog.event("VR", "headset INACTIVE");
                if (enabled) applyEnabled(false);
            }
            vrWasActive = vrActive;
        }

        // (Teleport is blocked directly in TeleportTrackerMixin by cancelling its
        // doProcess while the mod is on and allowTeleport is off — the setTeleportOverride
        // route proved unreliable, isTeleportEnabled ignored it on QuestCraft.)

        // Toggle on each press of the keybind (keyboard or Vivecraft radial menu).
        while (toggleKey.consumeClick()) {
            toggle();
        }

        // Dimension change (Nether/End portal, /execute in, etc.) AND respawn after
        // death both REPLACE the player entity, which recomputes its collision box
        // once and caches it — so our Real Monke shrink silently reverts (the model
        // springs back to full 2-block height). Force the watchers below to re-apply
        // by clearing their "last" state — same effect as toggling the option off+on.
        // A dimension change is caught by the dimension key; a same-dimension respawn
        // is caught by the player INSTANCE changing (a brand-new LocalPlayer object).
        if (client.player != null) {
            var dim = client.player.level().dimension();
            if ((lastDimension != null && !lastDimension.equals(dim))
                    || client.player != lastPlayerRef) {
                lastRealMonke  = false;
                lastMonkeModel = false;
            }
            lastDimension = dim;
            lastPlayerRef = client.player;
        }

        // Apply / remove Real Monke when the setting or the mod's enabled state
        // flips mid-game (config screen save, /vmc set, preset, toggle).
        boolean monke = enabled && MovementConfig.realMonke;
        if (monke != lastRealMonke && client.player != null) {
            lastRealMonke = monke;
            client.player.refreshDimensions();
            applyRealMonkeScale(client, monke);
        }

        // Announce / retract the monke model (legless look): render it locally for
        // ourselves immediately, and tell the server (dedicated monke-server OR our
        // own integrated/LAN server via the embedded logic) so it broadcasts to all
        // other mod users. canSend() is true whenever a receiver exists on the other
        // end — including our own integrated server when hosting LAN.
        boolean model = enabled && MovementConfig.monkeModel;
        if (model != lastMonkeModel && client.player != null) {
            lastMonkeModel = model;
            MonkeModelClientSet.set(client.player.getUUID(), model);
            if (VmcNet.canSendToServer(MonkeModelC2SPayload.ID)) {
                VmcNet.sendToServer(new MonkeModelC2SPayload(model));
            }
        }

        boolean grippingNow = false;
        boolean magmaNow    = false;
        if (enabled && client.player != null && !client.isPaused()
                && ServerLimits.modEnabled && serverAuthorized(client)) {
            // GUI GUARD: while ANY screen is open (inventory, chat, settings, ...)
            // gorilla locomotion must not move the player. Drop all grips the
            // moment a screen opens, and stay fully inert until it closes — the
            // next tick after closing resumes normally.
            if (client.gui.screen() != null) {
                if (!wasInGui) {
                    wasInGui = true;
                    handler.onGuiPause(client);
                }
            } else {
                wasInGui = false;
                handler.tick(client);
                grippingNow = handler.isGripping();
                magmaNow    = handler.isTouchingMagma();
            }
        }

        // No-fall-damage slide, dedicated-server half: keep the companion mod's fall
        // suppression alive while we grip. (Singleplayer/LAN host resets the integrated
        // server player inside the handler, so this only fires on a remote server.)
        syncWallSlide(client, grippingNow);

        // Magma touch, dedicated-server half: ask the companion mod to hurt us while a
        // hand grips a magma block. (Singleplayer/LAN hurts the integrated player in the
        // handler.) Sent each touching tick; hurt invulnerability frames throttle it.
        if (magmaNow && !client.hasSingleplayerServer()
                && VmcNet.canSendToServer(MagmaTouchC2SPayload.ID)) {
            VmcNet.sendToServer(MagmaTouchC2SPayload.INSTANCE);
        }
    }

    // Sends one keepalive per gripping tick and a single "released" packet when
    // gripping ends. No-op in singleplayer/LAN host (handled in the handler) and when
    // no receiver exists on the other end (server without the companion mod).
    private void syncWallSlide(Minecraft client, boolean gripping) {
        if (client.hasSingleplayerServer()) return;
        if (!VmcNet.canSendToServer(WallSlideC2SPayload.ID)) return;
        if (gripping) {
            if (!wallSlideSent) VmcDebugLog.event("NET", "→ WallSlide(true) [no-fall-damage]");
            VmcNet.sendToServer(new WallSlideC2SPayload(true));
            wallSlideSent = true;
        } else if (wallSlideSent) {
            VmcDebugLog.event("NET", "→ WallSlide(false)");
            VmcNet.sendToServer(new WallSlideC2SPayload(false));
            wallSlideSent = false;
        }
    }

    // -----------------------------------------------------------------------
    // Real Monke — server-synced HEIGHT-ONLY hitbox shrink
    // -----------------------------------------------------------------------

    // The SERVER validates movement with its own copy of the player's box, so the
    // shrink must land on both logical sides or tunnels get rubber-banded:
    //   singleplayer → refresh the integrated server's ServerPlayer; the shared
    //                  PlayerHitboxMixin applies the same height cap to it
    //   dedicated    → ask the monke-server companion via RealMonkeC2SPayload
    private static void applyRealMonkeScale(Minecraft client, boolean on) {
        if (client.hasSingleplayerServer()) {
            var server = client.getSingleplayerServer();
            if (server == null || client.player == null) return;
            java.util.UUID id = client.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(id);
                if (sp != null) sp.refreshDimensions();   // PlayerHitboxMixin caps the height
            });
        } else if (ServerLimits.packetReceived
                && VmcNet.canSendToServer(RealMonkeC2SPayload.ID)) {
            VmcNet.sendToServer(new RealMonkeC2SPayload(on));
        }
    }

    // -----------------------------------------------------------------------
    // Multiplayer authorization (server-side opt-in)
    // -----------------------------------------------------------------------

    // Singleplayer / hosting a LAN world: always allowed — there is no one to gain
    // an unfair advantage over, and the integrated server can't run server mods.
    // Dedicated server: allowed ONLY after the monke-server companion mod has sent
    // its config packet this connection. No packet = the server didn't opt in =
    // the mod refuses to run (required by Modrinth's movement-mod policy, and it
    // shuts out bad actors who would use this on unsuspecting vanilla servers).
    private static boolean serverAuthorized(Minecraft client) {
        return client.hasSingleplayerServer() || ServerLimits.packetReceived;
    }

    // -----------------------------------------------------------------------
    // Shared toggle logic (used by BOTH the keybind and the /vmc command)
    // -----------------------------------------------------------------------

    private void toggle() {
        applyEnabled(!enabled);
    }

    private void applyEnabled(boolean on) {
        Minecraft client = Minecraft.getInstance();

        // Multiplayer opt-in gate: on a dedicated server without the monke-server
        // companion mod, refuse every enable attempt — keybind, /vmc on, auto-start.
        if (on && !serverAuthorized(client)) {
            if (client.player != null) {
                client.gui.hud.setOverlayMessage(
                    Component.literal("§e[ViveMonkeCraft] §cThis server doesn't run the monke-server mod"),
                    false
                );
            }
            return;
        }

        // Server config always wins: if the server has banned the mod, refuse any
        // attempt to enable it — keybind, /vmc on, or auto-start all end up here.
        if (on && !ServerLimits.modEnabled) {
            if (client.player != null) {
                client.gui.hud.setOverlayMessage(
                    Component.literal("§e[ViveMonkeCraft] §cDisabled by server"),
                    false
                );
            }
            return;
        }

        enabled = on;
        VmcDebugLog.event("STATE", "gorilla locomotion " + (on ? "ENABLED" : "DISABLED"));

        if (on) {
            // Turning ON: re-read the config so edits apply, no restart needed.
            MovementConfig.load();
        } else {
            // Turning OFF: drop grips and restore step height / scale / gravity.
            handler.onDisable(client);
        }

        if (client.player != null) {
            // Refresh the collision box so the shorter "gorilla" hitbox applies/reverts now.
            client.player.refreshDimensions();

            String state = on ? "§aON" : "§cOFF";
            // Show in the action bar (overlay message).
            client.gui.hud.setOverlayMessage(
                Component.literal("§e[ViveMonkeCraft] §fGorilla Locomotion: " + state),
                false
            );
        }
    }

    private void reloadConfig() {
        MovementConfig.load();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.hud.setOverlayMessage(
                Component.literal("§e[ViveMonkeCraft] §fConfig reloaded"),
                false
            );
        }
    }
}
