package laggyboi.vivemonkecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import laggyboi.vivemonkecraft.mixin.client.SpecialModelRenderersAccessor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else
/*import net.minecraft.client.renderer.MultiBufferSource;*/
//? if <26.1
/*import net.minecraft.world.item.ItemDisplayContext;*/
//? if <1.21.9
/*import net.minecraft.client.model.geom.EntityModelSet;*/
//? if >=1.21.11 {
import org.joml.Vector3fc;
import java.util.function.Consumer;
//?}
//? if >=1.21.6 <1.21.11 {
/*import org.joml.Vector3f;
import java.util.Set;
*///?}

// =====================================================================
// HOVERBOARD MODEL — the 3D board you see in your hand
// =====================================================================
//
// Built from cuboids like the cosmetics (see cosmetics/Cosmetics), not from a
// block-style item model, and drawn by a vanilla "special" item renderer — the
// same hook the trident and shield use. items/hoverboard.json points at it, so
// it shows wherever the item is drawn: in your VR hand, other players' hands,
// the hotbar and on the ground. models/item/hoverboard.json only supplies the
// per-context display transforms (how it sits in the hand) and the particle.
//
// Layout, front of the board first (the handle end):
//   deck     4 x 1 x 16
//   handle   1 x 3 x 1, standing up near the front — the hand grips it, the
//            board runs back along the forearm
//   magnets  4 equal 3 x 1 x 2.5 pads under the deck
//   wings    two 3.5 x 0.5 x 3 fins at the back, tilted up 22.5 degrees
//
// Units: the model is built in HALF pixels (so 2.5 and 0.5 sizes stay whole
// numbers on the texture) and scaled by 0.5 when drawn. Texture is 128 x 64,
// box-UV like the cosmetics; regions are listed next to each part.
// =====================================================================
public final class HoverboardModel {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("vivemonkecraft", "hoverboard");
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("vivemonkecraft", "textures/item/hoverboard_model.png");

    private static ModelPart root;

    /** Registers the "vivemonkecraft:hoverboard" special model type. Call once at client init. */
    @SuppressWarnings("unchecked")
    public static void register() {
        SpecialModelRenderersAccessor.vmc$idMapper().put(ID, Unbaked.MAP_CODEC);
    }

    // Boxes are given in item-model pixels (0..16, y up, front at z = 0) and
    // converted to model space (half pixels, centred, y down, front at +z).
    private static CubeListBuilder box(CubeListBuilder b, float x0, float y0, float z0, float x1, float y1, float z1) {
        return b.addBox(2 * (x0 - 8), 2 * (8 - y1), 2 * (8 - z1), 2 * (x1 - x0), 2 * (y1 - y0), 2 * (z1 - z0));
    }

    private static LayerDefinition layer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition p = mesh.getRoot();
        p.addOrReplaceChild("deck", box(CubeListBuilder.create().texOffs(0, 0), 6, 7, 0, 10, 8, 16), PartPose.ZERO);
        p.addOrReplaceChild("handle", box(CubeListBuilder.create().texOffs(80, 0), 7.5F, 8, 1.5F, 8.5F, 11, 2.5F), PartPose.ZERO);
        CubeListBuilder magnets = CubeListBuilder.create().texOffs(80, 10);
        for (float z : new float[] {0.5F, 4.5F, 8.5F, 12.5F}) box(magnets, 6.5F, 6, z, 9.5F, 7, z + 2.5F);
        p.addOrReplaceChild("magnets", magnets, PartPose.ZERO);
        // Wings hinge on the deck's top edge and tilt their tips up. Model Y
        // points down, so lifting the right (+x) tip is a negative z roll.
        float tilt = (float) Math.toRadians(22.5);
        p.addOrReplaceChild("wing_right", CubeListBuilder.create().texOffs(80, 20).addBox(0, 0, -3, 7, 1, 6),
                PartPose.offsetAndRotation(4, 0, -12, 0, 0, -tilt));
        p.addOrReplaceChild("wing_left", CubeListBuilder.create().texOffs(80, 20).mirror().addBox(-7, 0, -3, 7, 1, 6),
                PartPose.offsetAndRotation(-4, 0, -12, 0, 0, tilt));
        return LayerDefinition.create(mesh, 128, 64);
    }

    private static ModelPart model() {
        if (root == null) root = layer().bakeRoot();
        return root;
    }

    // Item space is 0..1 with the centre at 0.5; the model is centred, in half
    // pixels and y-down, hence the translate + flip + half scale.
    private static void toItemSpace(PoseStack pose) {
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.scale(0.5F, -0.5F, -0.5F);
    }

    public static final class Renderer implements NoDataSpecialModelRenderer {

        // 1.21.9 swapped render(..., MultiBufferSource) for submit(..., SubmitNodeCollector);
        // 26.1 dropped the display-context parameter.
        //? if >=26.1 {
        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
        //?}
        //? if >=1.21.9 <26.1 {
        /*@Override
        public void submit(ItemDisplayContext ctx, PoseStack pose, SubmitNodeCollector collector,
                           int light, int overlay, boolean foil, int outline) {
        *///?}
        //? if <1.21.9 {
        /*@Override
        public void render(ItemDisplayContext ctx, PoseStack pose, MultiBufferSource buffers,
                           int light, int overlay, boolean foil) {
        *///?}
            pose.pushPose();
            toItemSpace(pose);
            //? if >=1.21.9 {
            collector.submitModelPart(model(), pose, RenderTypes.entityCutout(TEXTURE), light, overlay, null);
            //?} else {
            /*model().render(pose, buffers.getBuffer(RenderTypes.entityCutout(TEXTURE)), light, overlay);
            *///?}
            pose.popPose();
        }

        // Points the GUI uses to frame the item (added in 1.21.6).
        //? if >=1.21.11 {
        @Override
        public void getExtents(Consumer<Vector3fc> out) {
            PoseStack pose = new PoseStack();
            toItemSpace(pose);
            model().getExtentsForGui(pose, out);
        }
        //?}
        //? if >=1.21.6 <1.21.11 {
        /*@Override
        public void getExtents(Set<Vector3f> out) {
            PoseStack pose = new PoseStack();
            toItemSpace(pose);
            model().getExtentsForGui(pose, out);
        }
        *///?}
    }

    //? if >=26.1 {
    public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<Void> bake(SpecialModelRenderer.BakingContext context) {
            return new Renderer();
        }
    }
    //?}
    //? if >=1.21.9 <26.1 {
    /*public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
            return new Renderer();
        }
    }
    *///?}
    //? if <1.21.9 {
    /*public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(EntityModelSet models) {
            return new Renderer();
        }
    }
    *///?}

    private HoverboardModel() {}
}
