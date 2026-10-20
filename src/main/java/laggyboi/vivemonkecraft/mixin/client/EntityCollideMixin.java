package laggyboi.vivemonkecraft.mixin.client;

import laggyboi.vivemonkecraft.client.EntityColliders;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// =====================================================================
// ENTITY COLLIDERS — the body stands on mobs (mixin)
// =====================================================================
//
// Vanilla movement only collides with entities that opt in (boats, shulkers).
// While the GT clone drives the local player, add every climbable entity's
// collider to the shapes Entity#collide tests against, so the body can land on
// and walk across mobs and players. Other entities and other modes are untouched.
//
// Those mobs are solid now, so their vanilla "push apart" is switched off for
// the local player too — otherwise a mob you stand on shoves you off its back
// every time it moves into your box between ticks (a jump, a turn).
// =====================================================================
@Mixin(Entity.class)
public abstract class EntityCollideMixin {

    @Redirect(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
              require = 0)
    private List<VoxelShape> vmc$entityColliders(Level level, Entity self, AABB sweep) {
        List<VoxelShape> vanilla = level.getEntityCollisions(self, sweep);
        if (!(self instanceof LocalPlayer player) || !EntityColliders.bodyActive) return vanilla;
        List<VoxelShape> extra = EntityColliders.bodyShapes(player, sweep);
        if (extra.isEmpty()) return vanilla;
        List<VoxelShape> all = new ArrayList<>(vanilla);
        all.addAll(extra);
        return all;
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void vmc$noPushFromSolidMobs(Entity other, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player && EntityColliders.bodyActive
                && EntityColliders.enabled() && EntityColliders.solid(other, player)) {
            ci.cancel();
        }
    }
}
