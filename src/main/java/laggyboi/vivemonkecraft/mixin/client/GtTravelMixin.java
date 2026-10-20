package laggyboi.vivemonkecraft.mixin.client;

import laggyboi.vivemonkecraft.client.GtCloneLocomotion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// =====================================================================
// GORILLA TAG CLONE — the clone owns the body (mixin)
// =====================================================================
//
// Same split as the Gorillatag-Minecraft-Passthrough mod: Gorilla Tag's
// physics moves the gorilla, so Minecraft's own walking, gravity and drag stay
// out of it. GtCloneLocomotion raises ownsBody each tick it drives the local
// player; the next Player#travel consumes it and is skipped. Any tick the clone
// didn't run (a menu open, swimming, elytra, mod off, another mode) leaves the
// flag down and vanilla travel runs as normal.
// =====================================================================
@Mixin(Player.class)
public abstract class GtTravelMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void vmc$gtCloneOwnsBody(Vec3 input, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer)) return;
        if (!GtCloneLocomotion.ownsBody) return;
        GtCloneLocomotion.ownsBody = false;
        ci.cancel();
    }
}
