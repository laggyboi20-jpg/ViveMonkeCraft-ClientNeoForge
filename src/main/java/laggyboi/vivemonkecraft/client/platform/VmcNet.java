package laggyboi.vivemonkecraft.client.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

// =====================================================================
// NETWORKING                                             [NEOFORGE BODY]
// =====================================================================
//
// The PAYLOAD CLASSES themselves are pure vanilla (CustomPacketPayload +
// StreamCodec) and are shared verbatim across all three loader branches — only
// registration and send differ, which is all this class hides.
//
// ---------------------------------------------------------------------
// CONTRACT (identical on every loader — the port depends on all of it):
//
//   * All payloads are OPTIONAL. The client MUST be able to connect to a server
//     that has none of these channels: "no ServerConfigPayload arrived" is
//     exactly how the mod detects an un-opted-in server and stays disabled.
//     >>> On NeoForge this is what registrar.optional() below buys us. WITHOUT
//     >>> IT NeoForge treats every channel as required and the handshake
//     >>> REJECTS the connection to any server lacking the companion mod —
//     >>> which would silently destroy the whole server opt-in policy and make
//     >>> the mod unusable on vanilla servers. Do not remove it.
//   * Handlers run on the MAIN thread. NeoForge's default HandlerThread is MAIN,
//     and we additionally enqueueWork() so handler bodies may touch game state.
//   * canSendToServer() must return false — not throw — when no receiver exists
//     on the other end.
//
// ORDERING NOTE: NeoForge only accepts payload registration during
// RegisterPayloadHandlersEvent, but the shared VivemonkecraftClient.init() calls
// register() earlier (from the mod constructor). So register() BUFFERS the block
// and VmcBootstrap replays it via flush() when the event fires.
// ---------------------------------------------------------------------
// =====================================================================
public final class VmcNet {

    private VmcNet() {}

    /**
     * Registration surface handed to {@link #register}. Implemented per loader.
     * Both directions register the payload TYPE and its handler in one call so no
     * branch can register a codec and forget the handler.
     */
    public interface Registrar {

        /** Server -> client payload plus the client-side handler. */
        <T extends CustomPacketPayload> void clientbound(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec,
                Consumer<T> handler);

        /** Client -> server payload plus the server-side handler (sender, payload). */
        <T extends CustomPacketPayload> void serverbound(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec,
                BiConsumer<ServerPlayer, T> handler);

        /**
         * Client -> server payload with NO handler on this side. Used by the client
         * mod for packets only the separate server companion answers; the type still
         * has to be registered so the codec exists for sending.
         */
        <T extends CustomPacketPayload> void serverboundNoHandler(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec);
    }

    // Buffered until RegisterPayloadHandlersEvent — see the ordering note above.
    private static Consumer<Registrar> pending;

    /** Called once from the shared client init with every payload the mod uses. */
    public static void register(Consumer<Registrar> block) {
        pending = block;
    }

    /** Called by VmcBootstrap from RegisterPayloadHandlersEvent. */
    public static void flush(PayloadRegistrar registrar) {
        if (pending == null) return;
        pending.accept(new NeoRegistrar(registrar.optional()));
        pending = null;
    }

    private record NeoRegistrar(PayloadRegistrar registrar) implements Registrar {

        @Override
        public <T extends CustomPacketPayload> void clientbound(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec,
                Consumer<T> handler) {
            registrar.playToClient(id, cast(codec),
                    (payload, context) -> context.enqueueWork(() -> handler.accept(payload)));
        }

        @Override
        public <T extends CustomPacketPayload> void serverbound(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec,
                BiConsumer<ServerPlayer, T> handler) {
            registrar.playToServer(id, cast(codec), (payload, context) ->
                    context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer sp) {
                            handler.accept(sp, payload);
                        }
                    }));
        }

        @Override
        public <T extends CustomPacketPayload> void serverboundNoHandler(
                CustomPacketPayload.Type<T> id,
                StreamCodec<? super FriendlyByteBuf, T> codec) {
            // NeoForge has no "type only" registration — register a no-op handler.
            // These payloads are answered only by the separate server companion mod,
            // so nothing on this side should ever receive them anyway.
            registrar.playToServer(id, cast(codec), (payload, context) -> { });
        }

        // The shared API takes StreamCodec<? super FriendlyByteBuf, T> (what the
        // payload classes declare); NeoForge wants StreamCodec<? super RegistryFriendlyByteBuf, T>.
        // FriendlyByteBuf is a supertype of RegistryFriendlyByteBuf, so any codec
        // accepting the former also accepts the latter — the cast is sound.
        @SuppressWarnings("unchecked")
        private static <T> StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> cast(
                StreamCodec<? super FriendlyByteBuf, T> codec) {
            return (StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T>) codec;
        }
    }

    // -----------------------------------------------------------------------
    // Sending
    // -----------------------------------------------------------------------

    /** Client -> server. Caller should gate on {@link #canSendToServer}. */
    public static void sendToServer(CustomPacketPayload payload) {
        // 1.21.7 moved client-side sending out of PacketDistributor.
        //? if >=1.21.7 {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
        //?} else
        /*PacketDistributor.sendToServer(payload);*/
    }

    /** False when the other end has no receiver (e.g. a server without the companion). */
    public static boolean canSendToServer(CustomPacketPayload.Type<?> id) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(id);
    }

    /** Server -> one client. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Server -> every connected client. */
    public static void sendToAll(MinecraftServer server, CustomPacketPayload payload) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
