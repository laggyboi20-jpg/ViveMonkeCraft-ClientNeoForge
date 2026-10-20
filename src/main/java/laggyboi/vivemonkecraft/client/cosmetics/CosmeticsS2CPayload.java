package laggyboi.vivemonkecraft.client.cosmetics;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Server -> client: "this player wears these cosmetics" (empty = none / left).
 * Broadcast when someone changes theirs, and replayed to each player who joins.
 */
public record CosmeticsS2CPayload(UUID player, List<String> ids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CosmeticsS2CPayload> ID =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("vivemonkecraft", "cosmetics_sync"));

    public static final StreamCodec<FriendlyByteBuf, CosmeticsS2CPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUUID(p.player()); CosmeticsC2SPayload.writeIds(buf, p.ids()); },
                    buf -> new CosmeticsS2CPayload(buf.readUUID(), CosmeticsC2SPayload.readIds(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
