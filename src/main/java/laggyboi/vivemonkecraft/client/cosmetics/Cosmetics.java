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

    // ---- SHOULDER CAT ---------------------------------------------------
    // An orange tabby sitting on the left shoulder, like a parrot. Attached to
    // the body, so it leans with the torso. The body's origin is the neck and
    // the top of the shoulders is y = 0; the left arm spans x 4..8, so the cat
    // is centred at x = 6.5 with its feet on y = 0. Front is -z.
    //   body   4x4x5   sitting on the shoulder
    //   head   5x4x4   on top, poking out past the front of the shoulder
    //   ears   1x1x2   x2
    //   muzzle 3x2x1   front of the head
    //   paws   1x2x1   x2, front of the body
    //   tail   1x6x1   hanging down the back
    // Texture (32x32): body 0,0; head 0,9; ears 18,0; muzzle 18,3; paws 18,6; tail 22,6.
    private static LayerDefinition shoulderCat() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition cat = mesh.getRoot().addOrReplaceChild("cat", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-2.0F, -4.0F, -2.0F, 4.0F, 4.0F, 5.0F), PartPose.offset(6.5F, 0.0F, 0.0F));
        cat.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 9).addBox(-2.5F, -8.0F, -3.5F, 5.0F, 4.0F, 4.0F)
                .texOffs(18, 0).addBox(-2.5F, -9.0F, -1.5F, 1.0F, 1.0F, 2.0F)
                .texOffs(18, 0).addBox(1.5F, -9.0F, -1.5F, 1.0F, 1.0F, 2.0F)
                .texOffs(18, 3).addBox(-1.5F, -6.0F, -4.5F, 3.0F, 2.0F, 1.0F), PartPose.ZERO);
        cat.addOrReplaceChild("paws", CubeListBuilder.create()
                .texOffs(18, 6).addBox(-1.5F, -2.0F, -3.0F, 1.0F, 2.0F, 1.0F)
                .texOffs(18, 6).addBox(0.5F, -2.0F, -3.0F, 1.0F, 2.0F, 1.0F), PartPose.ZERO);
        cat.addOrReplaceChild("tail", CubeListBuilder.create()
                .texOffs(22, 6).addBox(-0.5F, -1.0F, 3.0F, 1.0F, 6.0F, 1.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    private static final Map<String, Cosmetic> ALL = new LinkedHashMap<>();
    static {
        add(new Cosmetic("pink_hat", "Pink Hat", Cosmetic.Slot.HEAD, Cosmetics::pinkHat));
        add(new Cosmetic("shoulder_cat", "Shoulder Cat", Cosmetic.Slot.BODY, Cosmetics::shoulderCat));
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
        if (isLocal) return localWorn();
        return REMOTE.getOrDefault(uuid, List.of());
    }

    // Called every frame, so the parsed list is cached until the setting changes.
    private static String localKey = null;
    private static List<Cosmetic> localList = List.of();

    private static List<Cosmetic> localWorn() {
        String key = MovementConfig.cosmetics;
        if (!key.equals(localKey)) {
            List<Cosmetic> out = new ArrayList<>();
            for (String id : equippedIds()) out.add(ALL.get(id));
            localList = List.copyOf(out);
            localKey  = key;
        }
        return localList;
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
