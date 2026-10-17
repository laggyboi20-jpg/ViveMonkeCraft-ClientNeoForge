package laggyboi.vivemonkecraft.mixin.client;

import laggyboi.vivemonkecraft.client.VmcMonkeRenderState;
import laggyboi.vivemonkecraft.client.cosmetics.Cosmetic;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

// Stitches the monke-model flag onto vanilla's player render state (renamed in
// 1.21.9) so the renderer (which knows the
// player) can hand it to the model (which doesn't).
@Mixin(AvatarRenderState.class)
public class RenderStateHooksMixin implements VmcMonkeRenderState {

    @Unique
    private boolean vmc$monke = false;

    @Unique
    private boolean vmc$hideOwnBody = false;

    @Override
    public boolean vmc$isMonke() {
        return vmc$monke;
    }

    @Override
    public void vmc$setMonke(boolean monke) {
        vmc$monke = monke;
    }

    @Override
    public boolean vmc$hideOwnBody() {
        return vmc$hideOwnBody;
    }

    @Override
    public void vmc$setHideOwnBody(boolean hide) {
        vmc$hideOwnBody = hide;
    }

    @Unique
    private List<Cosmetic> vmc$cosmetics = List.of();

    @Override
    public List<Cosmetic> vmc$cosmetics() {
        return vmc$cosmetics;
    }

    @Override
    public void vmc$setCosmetics(List<Cosmetic> cosmetics) {
        vmc$cosmetics = cosmetics;
    }
}
