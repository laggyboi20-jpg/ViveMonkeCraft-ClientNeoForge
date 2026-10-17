package laggyboi.vivemonkecraft.client.cosmetics;

import laggyboi.vivemonkecraft.client.MovementConfig;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// =====================================================================
// COSMETICS — the catalogue + who is wearing what
// =====================================================================
//
// Free cosmetics, all unlocked. To add one: give it a model below (cuboids in
// skin pixels), drop a texture at textures/cosmetic/<id>.png, and register it
// in the static block. It then shows up on the Cosmetics page of the config
// screen.
//
// LOCAL: what you wear lives in MovementConfig.cosmetics (comma-separated ids),
// so it is saved with the rest of the config.
// REMOTE: what other players wear is filled in by the cosmetic sync, keyed by
// player UUID. Players without the mod simply never appear there.
// =====================================================================
public final class Cosmetics {

    // ---- PINK HAT -------------------------------------------------------
    // A wide flat brim with a square crown on top, sitting low on the head.
    //   brim  14x1x14, 1px below the top of the head
    //   crown 10x4x10, covering the top of the head
    // Texture (64x32): brim at UV 0,0; crown at UV 0,15.
    private static LayerDefinition pinkHat() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("brim", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-7.0F, -6.0F, -7.0F, 14.0F, 1.0F, 14.0F), PartPose.ZERO);
        root.addOrReplaceChild("crown", CubeListBuilder.create()
                .texOffs(0, 15).addBox(-5.0F, -10.0F, -5.0F, 10.0F, 4.0F, 10.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 32);
    }

    private static final Map<String, Cosmetic> ALL = new LinkedHashMap<>();
    static {
        add(new Cosmetic("pink_hat", "Pink Hat", Cosmetic.Slot.HEAD, Cosmetics::pinkHat));
    }

    private static void add(Cosmetic c) {
        ALL.put(c.id, c);
    }

    // Other players' cosmetics, filled by the sync.
    private static final Map<UUID, List<Cosmetic>> REMOTE = new ConcurrentHashMap<>();

    private Cosmetics() {}

    public static Collection<Cosmetic> all() {
        return Collections.unmodifiableCollection(ALL.values());
    }

    public static Cosmetic byId(String id) {
        return ALL.get(id);
    }

    // -----------------------------------------------------------------------
    // Local player
    // -----------------------------------------------------------------------

    /** Ids you are wearing, in the order saved. Unknown ids are dropped. */
    public static Set<String> equippedIds() {
        Set<String> out = new LinkedHashSet<>();
        for (String s : MovementConfig.cosmetics.split(",")) {
            String id = s.trim();
            if (ALL.containsKey(id)) out.add(id);
        }
        return out;
    }

    public static boolean isEquipped(String id) {
        return equippedIds().contains(id);
    }

    /** Puts a cosmetic on or takes it off. The caller saves the config. */
    public static void setEquipped(String id, boolean on) {
        Set<String> ids = equippedIds();
        if (on) ids.add(id); else ids.remove(id);
        MovementConfig.cosmetics = String.join(",", ids);
    }

    // -----------------------------------------------------------------------
    // What to draw on a player
    // -----------------------------------------------------------------------

    /** Cosmetics to draw on the given player (you, or someone the sync told us about). */
    public static List<Cosmetic> forPlayer(UUID uuid, boolean isLocal) {
        if (isLocal) {
            List<Cosmetic> out = new ArrayList<>();
            for (String id : equippedIds()) out.add(ALL.get(id));
            return out;
        }
        return REMOTE.getOrDefault(uuid, List.of());
    }

    /** Sets what another player wears (from the sync). Empty or null clears them. */
    public static void setRemote(UUID uuid, Collection<String> ids) {
        List<Cosmetic> list = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Cosmetic c = ALL.get(id);
                if (c != null) list.add(c);
            }
        }
        if (list.isEmpty()) REMOTE.remove(uuid); else REMOTE.put(uuid, List.copyOf(list));
    }

    public static void clearRemote() {
        REMOTE.clear();
    }
}
