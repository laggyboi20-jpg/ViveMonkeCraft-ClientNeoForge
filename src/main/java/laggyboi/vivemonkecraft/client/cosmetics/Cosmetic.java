package laggyboi.vivemonkecraft.client.cosmetics;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;

import java.util.function.Supplier;

// =====================================================================
// COSMETIC — one wearable 3D piece (hat, jacket, ...)
// =====================================================================
//
// A cosmetic is a small block model built from cuboids, with its own texture,
// attached to one body part of the player model. It follows that part's pose
// every frame (head turn, monke torso lean, arm swing), so a hat tilts with
// the head and a jacket leans with the torso.
//
// Model space is the attached part's own space, in skin pixels (16 = 1 block),
// Y pointing DOWN. For the head the origin is the neck pivot and the head cube
// spans x/z -4..4, y -8..0.
// =====================================================================
public final class Cosmetic {

    public enum Slot { HEAD, BODY, LEFT_ARM, RIGHT_ARM }

    public final String id;
    public final String name;
    public final Slot slot;
    public final Identifier texture;
    private final Supplier<LayerDefinition> layer;
    private ModelPart baked;

    public Cosmetic(String id, String name, Slot slot, Supplier<LayerDefinition> layer) {
        this.id = id;
        this.name = name;
        this.slot = slot;
        this.texture = Identifier.fromNamespaceAndPath("vivemonkecraft", "textures/cosmetic/" + id + ".png");
        this.layer = layer;
    }

    /** The baked model, built on first use (render thread only). */
    public ModelPart model() {
        if (baked == null) baked = layer.get().bakeRoot();
        return baked;
    }
}
