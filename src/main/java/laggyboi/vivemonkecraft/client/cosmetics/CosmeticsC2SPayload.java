package laggyboi.vivemonkecraft.client.cosmetics;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server: "these are the cosmetics I'm wearing" (ids, possibly empty).
 * Sent on join and whenever you change them. The server (monke-server, or our
 * own integrated/LAN server via EmbeddedServerLogic) rebroadcasts it to everyone
 * as {@link CosmeticsS2CPayload}.
 */
public record CosmeticsC2SPayload(List<String> ids) implements CustomPacketPayload {

    /** Most cosmetics one player can announce, and the longest id accepted. */
    public static final int MAX_IDS = 16;
    public static final int MAX_ID_LENGTH = 64;

    public static final CustomPacketPayload.Type<CosmeticsC2SPayload> ID =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("vivemonkecraft", "cosmetics"));

    public static final StreamCodec<FriendlyByteBuf, CosmeticsC2SPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> writeIds(buf, p.ids()),
                    buf -> new CosmeticsC2SPayload(readIds(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    static void writeIds(FriendlyByteBuf buf, List<String> ids) {
        int n = Math.min(ids.size(), MAX_IDS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(ids.get(i), MAX_ID_LENGTH);
    }

    // Bounded on read too, so a bad packet can't make us allocate a huge list.
    static List<String> readIds(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_IDS) throw new IllegalArgumentException("Too many cosmetics: " + n);
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUtf(MAX_ID_LENGTH));
        return ids;
    }
}
