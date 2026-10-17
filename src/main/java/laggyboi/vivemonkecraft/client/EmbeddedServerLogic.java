package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.platform.VmcEvents;
import laggyboi.vivemonkecraft.client.platform.VmcNet;
import laggyboi.vivemonkecraft.client.cosmetics.CosmeticsC2SPayload;
import laggyboi.vivemonkecraft.client.cosmetics.CosmeticsS2CPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// =====================================================================
// EMBEDDED SERVER LOGIC — the monke-server companion, baked into the client
// =====================================================================
//
// A LAN host ("Open to LAN" / Essential invite) runs an INTEGRATED server in
// the same JVM as the client. ServerPlayConnectionEvents / ServerPlayNetworking
// work there, so registering them from the client mod gives a LAN host all the
// server-side behaviour WITHOUT anyone installing the separate ServerBuild jar:
//
//   * sends ServerConfigPayload on join → guests get AUTHORIZED (the packet is
//     the multiplayer opt-in; without it a guest's mod stays off). Defaults to
//     modEnabled=true, no caps/allowances — a friends LAN game is unrestricted.
//   * receives Real Monke requests → tracks who's shrunk so PlayerHitboxMixin
//     can shrink guests' collision boxes on the host (so tunnels validate).
//   * receives Monke Model toggles → broadcasts them to everyone, and replays
//     the current set to joiners, so all mod users see each other legless.
//   * receives what each player wears (cosmetics) → same broadcast + replay,
//     so all mod users see each other's hats.
//
// IMPORTANT: this only runs where an integrated server exists (singleplayer /
// LAN host). On a DEDICATED server the client mod isn't loaded at all, so these
// events never fire there — dedicated servers still need the ServerBuild, which
// preserves the server-side opt-in (Modrinth policy). The payload TYPES are
// registered once by VivemonkecraftClient; we only add the server handlers here.
// =====================================================================
public final class EmbeddedServerLogic {

    /** Guests (and host) who have Real Monke on — read by PlayerHitboxMixin. */
    public static final Set<UUID> realMonkePlayers = ConcurrentHashMap.newKeySet();

    /** Players who have the legless Monke Model on — for join replay. */
    private static final Set<UUID> monkeModelPlayers = ConcurrentHashMap.newKeySet();

    /** What each player wears (cosmetic ids) — for join replay. */
    private static final Map<UUID, List<String>> cosmeticPlayers = new ConcurrentHashMap<>();

    private EmbeddedServerLogic() {}

    // Join/disconnect only — the two PAYLOAD handlers below are wired up by
    // VivemonkecraftClient's VmcNet.register block (the loader needs the payload
    // type and its handler registered together).
    public static void register() {

        // Authorize every joiner + replay the current monke-model set to them.
        VmcEvents.onServerJoin((player, server) -> {
            // Unrestricted config — a LAN game among friends needs no caps. (Wire
            // format must match ServerConfigPayload: modEnabled, then 10 doubles.)
            VmcNet.sendToPlayer(player, new ServerConfigPayload(
                    true,        // modEnabled
                    0.0,         // maxJumpSpeed (no hard cap)
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,   // allowances unset
                    -1.0, -1.0)); // gravity / air-friction minimums unset

            for (UUID u : monkeModelPlayers) {
                VmcNet.sendToPlayer(player, new MonkeModelS2CPayload(u, true));
            }
            cosmeticPlayers.forEach((u, ids) ->
                    VmcNet.sendToPlayer(player, new CosmeticsS2CPayload(u, ids)));
        });

        VmcEvents.onServerDisconnect((player, server) -> {
            UUID id = player.getUUID();
            realMonkePlayers.remove(id);
            if (monkeModelPlayers.remove(id)) {
                VmcNet.sendToAll(server, new MonkeModelS2CPayload(id, false));
            }
            if (cosmeticPlayers.remove(id) != null) {
                VmcNet.sendToAll(server, new CosmeticsS2CPayload(id, List.of()));
            }
        });
    }

    // Real Monke: track the requester so the hitbox mixin shrinks their box,
    // then refresh dimensions so it applies immediately.
    static void onRealMonke(ServerPlayer sender, RealMonkeC2SPayload payload) {
        UUID id = sender.getUUID();
        if (payload.enabled()) realMonkePlayers.add(id);
        else                   realMonkePlayers.remove(id);
        sender.refreshDimensions();
    }

    // Monke Model: track + broadcast to everyone so all mod users see it.
    static void onMonkeModel(ServerPlayer sender, MonkeModelC2SPayload payload) {
        UUID id = sender.getUUID();
        if (payload.enabled()) monkeModelPlayers.add(id);
        else                   monkeModelPlayers.remove(id);
        VmcNet.sendToAll(sender.level().getServer(),
                new MonkeModelS2CPayload(id, payload.enabled()));
    }

    // Cosmetics: track + broadcast to everyone so all mod users see them.
    static void onCosmetics(ServerPlayer sender, CosmeticsC2SPayload payload) {
        UUID id = sender.getUUID();
        List<String> ids = List.copyOf(payload.ids());
        if (ids.isEmpty()) cosmeticPlayers.remove(id);
        else               cosmeticPlayers.put(id, ids);
        VmcNet.sendToAll(sender.level().getServer(), new CosmeticsS2CPayload(id, ids));
    }
}
