package laggyboi.vivemonkecraft.mixin.client;

import laggyboi.vivemonkecraft.client.MovementConfig;
import laggyboi.vivemonkecraft.client.VivemonkecraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// =====================================================================
// FALL DAMAGE (mixin) — disable / survive-fatal
// =====================================================================
//
// Fall damage is applied SERVER-SIDE: LivingEntity.checkFallDamage (gated on
// ServerLevel) calls causeFallDamage. So in singleplayer / LAN host this hook
// fires on the integrated server's ServerPlayer — the authoritative side — and
// the change syncs to the client. (On a DEDICATED server the client can't reach
// it; the monke-server companion would carry this half, exactly like the
// existing fall-distance suppression.)
//
// Two independent behaviours, both only for OUR OWN player and only while the
// mod is on:
//   * disableFallDamage — cancel fall damage outright (wins over survive).
//   * surviveFatalFall  — a "last stand": if the hit would KILL you AND you were
//     at FULL health, cancel the fatal damage and instead leave you at half your
//     hearts, minus 3 food. Landing at half health (not full) means it can't
//     chain — it only saves you again once you've healed all the way back.
//
// require = 0 so a future Mojang rename of causeFallDamage silently degrades
// instead of crashing.
// =====================================================================
@Mixin(LivingEntity.class)
public abstract class FallDamageMixin {

    // LivingEntity.calculateFallDamage(double, float) — protected; shadow it so we
    // can predict the incoming damage and decide whether the fall is fatal.
    // 1.21.5 widened the fall distance from float to double in both methods.
    //? if >=1.21.5 {
    @Shadow protected abstract int calculateFallDamage(double fallDistance, float damageModifier);

    @Inject(
        method = "causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void vmc$fallDamage(double fallDistance, float damageModifier, DamageSource source,
                                CallbackInfoReturnable<Boolean> cir) {
    //?} else {
    /*@Shadow protected abstract int calculateFallDamage(float fallDistance, float damageModifier);

    @Inject(
        method = "causeFallDamage(FFLnet/minecraft/world/damagesource/DamageSource;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void vmc$fallDamage(float fallDistance, float damageModifier, DamageSource source,
                                CallbackInfoReturnable<Boolean> cir) {
    *///?}
        Object self = this;
        if (!(self instanceof Player)) return;

        // Our own player only (the client entity, or the integrated server's copy of us).
        boolean own;
        if (self instanceof LocalPlayer) {
            own = true;
        } else if (self instanceof ServerPlayer sp) {
            LocalPlayer lp = Minecraft.getInstance().player;
            own = lp != null && sp.getUUID().equals(lp.getUUID());
        } else {
            own = false;
        }
        if (!own || !VivemonkecraftClient.isEnabled()) return;

        // Full disable takes precedence — no damage at all, nothing to "survive".
        if (MovementConfig.disableFallDamage) {
            cir.setReturnValue(false);
            return;
        }

        if (MovementConfig.surviveFatalFall) {
            LivingEntity le = (LivingEntity) self;
            int dmg = calculateFallDamage(fallDistance, damageModifier);
            if (dmg <= 0) return; // harmless fall — let vanilla run normally

            float health = le.getHealth();
            boolean full = health >= le.getMaxHealth() - 0.001f;
            if (full && dmg >= health) {
                // LAST STAND: cancel the fatal damage, leave you at 1 HP (half a heart,
                // near-death) and drain 3 food.
                le.setHealth(1.0f);
                FoodData food = ((Player) self).getFoodData();
                food.setFoodLevel(Math.max(0, food.getFoodLevel() - 6)); // 3 food icons
                cir.setReturnValue(false);
            }
        }
    }
}
