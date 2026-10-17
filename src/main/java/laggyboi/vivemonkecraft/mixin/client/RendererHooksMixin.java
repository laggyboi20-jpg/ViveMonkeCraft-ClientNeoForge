package laggyboi.vivemonkecraft.mixin.client;

import laggyboi.vivemonkecraft.client.MonkeModelClientSet;
import laggyboi.vivemonkecraft.client.MovementConfig;
import laggyboi.vivemonkecraft.client.VivemonkecraftClient;
import laggyboi.vivemonkecraft.client.VmcMonkeRenderState;
import laggyboi.vivemonkecraft.client.cosmetics.Cosmetic;
import laggyboi.vivemonkecraft.client.cosmetics.CosmeticLayer;
import laggyboi.vivemonkecraft.client.cosmetics.Cosmetics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//? if >=1.21.9 {
import net.minecraft.world.entity.Avatar;
//?} else
/*import net.minecraft.client.player.AbstractClientPlayer;*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Marks each player's render state with "draw as monke?" while the renderer
// still knows WHICH player is being drawn (the model only sees the state).
// The set is fed by monke-server broadcasts + the local player's own toggle.
@Mixin(AvatarRenderer.class)
public class RendererHooksMixin {

    // COSMETICS: add the cosmetic layer to every player renderer (normal + slim).
    @SuppressWarnings("unchecked")
    @Inject(method = "<init>", at = @At("TAIL"))
    private void vmc$addCosmeticLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        ((LivingEntityRendererInvoker) (Object) this).vmc$addLayer(
                new CosmeticLayer((RenderLayerParent<AvatarRenderState, net.minecraft.client.model.player.PlayerModel>) (Object) this));
    }

    // 1.21.9 renamed the player renderer and its render state to Avatar*, and
    // extractRenderState's first param became the new Avatar superclass
    // (Player extends Avatar). Before that it took an AbstractClientPlayer.
    //? if >=1.21.9 {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void vmc$markMonke(Avatar player, AvatarRenderState state,
                                float partialTick, CallbackInfo ci) {
    //?} else {
    /*@Inject(
        method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void vmc$markMonke(AbstractClientPlayer player, AvatarRenderState state,
                                float partialTick, CallbackInfo ci) {
    *///?}
        VmcMonkeRenderState s = (VmcMonkeRenderState) state;
        s.vmc$setMonke(MonkeModelClientSet.isMonke(player.getUUID()));

        // Hide ONLY the local player's own body while the VR view is dropped — that
        // shift drags this body model to the floor. Other players are never flagged.
        boolean viewDropped = VivemonkecraftClient.isEnabled()
                && (MovementConfig.realMonke || MovementConfig.cameraHeightOffset > 0.0);
        boolean isLocal = player == Minecraft.getInstance().player;
        s.vmc$setHideOwnBody(viewDropped && isLocal);

        // COSMETICS: what this player wears. Your own head cosmetics are left off in
        // first person, where the body Vivecraft draws would put the hat in your view.
        List<Cosmetic> worn = Cosmetics.forPlayer(player.getUUID(), isLocal);
        if (isLocal && !worn.isEmpty()
                && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            List<Cosmetic> kept = new ArrayList<>();
            for (Cosmetic c : worn) if (c.slot != Cosmetic.Slot.HEAD) kept.add(c);
            worn = kept;
        }
        s.vmc$setCosmetics(worn);
    }
}
