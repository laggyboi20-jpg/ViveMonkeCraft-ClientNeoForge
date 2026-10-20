package laggyboi.vivemonkecraft.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

// =====================================================================
// ENTITY COLLIDERS  (GT mode: climb on mobs and players)
// =====================================================================
//
// Lets the Gorilla Tag hands grab, and the body stand on, other entities.
// Every collider is tagged with the entity it belongs to, so whatever touches
// it can be CARRIED when that entity moves: GtCloneLocomotion shifts a hand
// anchored on a mob (or the body standing on one) by the mob's movement since
// the last tick, and the anchored hand then drags the body along exactly like a
// hand pushing off a wall.
//
// For now a collider is the entity's hitbox (one AABB). The plan is to replace
// it with the posed model cuboids (oriented boxes), so everything outside this
// class only sees "a list of Colliders, each with an owner" — swapping the shape
// source only touches shapesOf().
// =====================================================================

public final class EntityColliders {

    private EntityColliders() {}

    /** One solid piece of an entity, in world space. */
    public record Collider(Entity owner, AABB box) {}

    // Raised while the GT clone drives the local player and the setting is on;
    // read by EntityCollideMixin so the body's vanilla collision sees entities.
    public static volatile boolean bodyActive = false;

    public static boolean enabled() {
        return MovementConfig.gtEntityColliders;
    }

    /** Entities you can climb: anything alive with a body (mobs, players, armor stands). */
    public static boolean solid(Entity e, Entity self) {
        if (e == self || !(e instanceof LivingEntity) || !e.isAlive() || e.isSpectator()) return false;
        // Don't collide with what you ride / what rides you.
        if (e == self.getVehicle() || e.hasPassenger(self) || self.hasPassenger(e)) return false;
        return e.getBbWidth() > 0.05 && e.getBbHeight() > 0.05;
    }

    /** All entity colliders touching {@code area}. */
    public static List<Collider> near(Level level, Entity self, AABB area) {
        List<Collider> out = new ArrayList<>();
        if (!enabled()) return out;
        for (Entity e : level.getEntities(self, area, e -> solid(e, self))) shapesOf(e, out);
        return out;
    }

    // The collision shape of one entity. Option 2 (model cuboids) plugs in here.
    private static void shapesOf(Entity e, List<Collider> out) {
        out.add(new Collider(e, e.getBoundingBox()));
    }

    /** Where the entity is right now; the carry delta is the change of this between ticks. */
    public static Vec3 anchorOf(Entity e) {
        return e.position();
    }

    /** The entity's facing in degrees: the BODY yaw for mobs, which is what the model turns with. */
    public static float yawOf(Entity e) {
        return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
    }

    // The mob the body is riding (standing on). Its top is widened by SADDLE on
    // every side so the headset drifting a few cm, or the mob's square hitbox not
    // turning with the model, doesn't drop you off the edge. Set by GtCloneLocomotion.
    public static volatile Entity riding = null;
    private static final double SADDLE = 0.25;

    private static AABB solidBox(Collider c) {
        return c.owner == riding ? c.box.inflate(SADDLE, 0, SADDLE) : c.box;
    }

    /**
     * Extra shapes for the local player's vanilla collision (Entity#collide).
     * Boxes the player already overlaps are skipped so a mob walking into you
     * (or rising under you) never traps you — you just stand up out of it.
     */
    public static List<VoxelShape> bodyShapes(LocalPlayer player, AABB sweep) {
        List<VoxelShape> out = new ArrayList<>();
        if (!bodyActive || !enabled()) return out;
        AABB self = player.getBoundingBox().deflate(1.0e-3);
        for (Collider c : near(player.level(), player, sweep)) {
            // The mob you ride isn't a vanilla collider: you're held on its back by
            // saddleLift() instead. Vanilla collision lets go the moment the feet
            // are a hair inside the box (the mob coming down from a jump), and the
            // same overlap blocks the sideways carry.
            if (c.owner == riding) continue;
            if (c.box.intersects(self)) continue;
            out.add(Shapes.create(c.box));
        }
        return out;
    }

    // How far below the ridden mob's top the feet may sink and still be lifted
    // back onto it (one tick of the mob dropping faster than you fall).
    private static final double SADDLE_DEPTH = 0.4;

    /**
     * How far to raise the player so the feet sit on the top of the mob being
     * ridden, or NaN if the feet left it (stepped past the widened top, or are
     * above it: jumping or climbing off).
     */
    public static double saddleLift(LocalPlayer player) {
        Entity e = riding;
        if (e == null || !enabled() || e.isRemoved() || !e.isAlive()) return Double.NaN;
        AABB feet = player.getBoundingBox();
        AABB b = e.getBoundingBox().inflate(SADDLE, 0, SADDLE);
        if (b.maxX <= feet.minX || b.minX >= feet.maxX || b.maxZ <= feet.minZ || b.minZ >= feet.maxZ) return Double.NaN;
        double lift = b.maxY - feet.minY;
        return lift >= -0.02 && lift <= SADDLE_DEPTH ? Math.max(0.0, lift) : Double.NaN;
    }

    /**
     * The entity the player is standing on, or null. Feet must rest on a
     * collider's top face (within a small tolerance) and overlap it horizontally.
     */
    public static Entity standingOn(LocalPlayer player) {
        if (!enabled()) return null;
        // Still on the mob we ride (a hand move this tick may have cleared onGround).
        if (riding != null && !Double.isNaN(saddleLift(player))) return riding;
        if (!player.onGround()) return null;
        AABB feet = player.getBoundingBox();
        AABB probe = new AABB(feet.minX, feet.minY - 0.05, feet.minZ, feet.maxX, feet.minY + 1.0e-3, feet.maxZ);
        Entity best = null;
        double bestTop = -Double.MAX_VALUE;
        for (Collider c : near(player.level(), player, probe.inflate(SADDLE, 0, SADDLE))) {
            AABB b = solidBox(c);
            double top = b.maxY;
            if (Math.abs(top - feet.minY) > 0.05) continue;
            if (b.maxX <= feet.minX || b.minX >= feet.maxX
                    || b.maxZ <= feet.minZ || b.minZ >= feet.maxZ) continue;
            if (top > bestTop) { bestTop = top; best = c.owner; }
        }
        return best;
    }
}
