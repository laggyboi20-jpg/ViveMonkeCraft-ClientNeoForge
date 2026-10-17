package laggyboi.vivemonkecraft.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import laggyboi.vivemonkecraft.client.VmcMonkeRenderState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else
/*import net.minecraft.client.renderer.MultiBufferSource;*/

import java.util.List;

// =====================================================================
// COSMETIC LAYER — draws the cosmetics on every player model
// =====================================================================
//
// Added to the player renderer by RendererHooksMixin, so it runs after the
// model is posed (vanilla + Vivecraft + the monke tweaks). Each cosmetic is
// moved into its body part's space and drawn there, so it follows that part.
// A cosmetic whose part is hidden is skipped along with it.
// =====================================================================
public class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    // 1.21.9 replaced render(..., MultiBufferSource, ...) with submit(..., SubmitNodeCollector, ...).
    //? if >=1.21.9 {
    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState state, float yRot, float xRot) {
    //?} else {
    /*@Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       AvatarRenderState state, float yRot, float xRot) {
    *///?}
        if (state.isInvisible) return;
        List<Cosmetic> worn = ((VmcMonkeRenderState) state).vmc$cosmetics();
        if (worn.isEmpty()) return;

        PlayerModel model = getParentModel();
        for (Cosmetic c : worn) {
            ModelPart anchor = switch (c.slot) {
                case HEAD      -> model.head;
                case BODY      -> model.body;
                case LEFT_ARM  -> model.leftArm;
                case RIGHT_ARM -> model.rightArm;
            };
            if (!anchor.visible) continue;

            poseStack.pushPose();
            anchor.translateAndRotate(poseStack);
            //? if >=1.21.9 {
            collector.submitModelPart(c.model(), poseStack, RenderTypes.entityCutout(c.texture),
                    packedLight, OverlayTexture.NO_OVERLAY, null);
            //?} else {
            /*c.model().render(poseStack, buffers.getBuffer(RenderTypes.entityCutout(c.texture)),
                    packedLight, OverlayTexture.NO_OVERLAY);
            *///?}
            poseStack.popPose();
        }
    }
}
