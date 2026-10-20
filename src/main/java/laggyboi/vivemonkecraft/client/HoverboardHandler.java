package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.compat.VmcCompat;
import laggyboi.vivemonkecraft.client.platform.VmcItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// =====================================================================
// HOVERBOARD
// =====================================================================
//
// Hold the Hoverboard item in either hand and it rides like a hand-held
// joystick. The board thrusts out of its UNDERSIDE, so:
//   * level board          → just hover (no push)
//   * tilted 45° forward   → full push forward
//   * tilted to a side     → push that way
// Only the part of the tilt that runs ALONG the surface pushes; the part
// pointing into the surface is replaced by the hover spring.
//
// Surfaces:
//   FLOOR — any ground within reach below your feet. A P-controller holds your
//           feet hoverHeight above it (max 0.5 so servers don't see "flying").
//           Ground slightly AHEAD is probed too, so you rise over 1-block steps
//           instead of hitting them (step-up doesn't run while airborne).
//   WALL  — at hoverWallRideSpeed or faster, turn the board's underside toward a
//           wall and you ride it, hitbox kept ~0.03 off the face. Drop below the
//           speed and you fall off.
//
// Driven by GorillaLocomotionHandler (Speed Swing locomotion mode only):
//   probe() before the physics, so the hand holding the board never grips and
//            the non-grip friction/gravity code stands aside while riding;
//   apply() after the physics, layering the board on top of any free-hand push.
// =====================================================================
public final class HoverboardHandler {

    private enum Surface { NONE, FLOOR, WALL }

    // Vanilla's per-tick airborne horizontal drag and gravity, undone when we
    // carry last tick's board velocity forward (we apply our own hoverDrag).
    private static final double VANILLA_AIR_DRAG = 0.91;
    private static final double VANILLA_GRAVITY  = 0.08;
    private static final double VANILLA_Y_DRAG   = 0.98;

    // Tilt (sine of angle from level) that gives full thrust: 45°.
    private static final double FULL_TILT = Math.sin(Math.toRadians(45.0));

    // How far below hoverHeight the board still catches you (blocks).
    private static final double FLOOR_SNAP = 0.8;
    // Probe ground this far ahead per unit of velocity (ticks), capped in blocks.
    private static final double LOOKAHEAD_TICKS = 3.0;
    private static final double LOOKAHEAD_MAX   = 1.5;
    // Look-ahead rays start this high above the feet, so 1-block steps register.
    private static final double STEP_PROBE      = 1.1;

    // Fraction of the hover-height error closed per tick (P-controller).
    private static final double HOVER_GAIN = 0.45;
    private static final double MAX_LIFT   = 0.6;
    private static final double MAX_SINK   = 0.6;

    // Wall riding: gap kept to the wall (inside vanilla's 0.0625 "touching a
    // block" margin so the anti-fly check stays happy), how far the board reaches
    // for a wall, the gentle slide-down, and the exit hysteresis.
    private static final double WALL_GAP   = 0.03;
    private static final double WALL_SNAP  = 0.6;
    private static final double WALL_SINK  = 0.015;
    private static final double WALL_KEEP  = 0.8;
    // The board must be tilted at least this far (sine) to ride a wall.
    private static final double WALL_TILT  = 0.5;

    private Surface surface = Surface.NONE;
    private Vec3    boardUp = new Vec3(0, 1, 0); // thrust axis, world space
    private double  gap;                          // feet→floor or side→wall (blocks)
    private Vec3    wallNormal = Vec3.ZERO;       // out of the wall face
    private boolean warnedGt = false;

    /** True while the board is holding you over a floor or wall this tick. */
    public boolean isRiding() {
        return surface != Surface.NONE;
    }

    /**
     * Finds the hand holding a hoverboard and the surface it rides this tick.
     * Returns that hand (its grip must be skipped), or null when no board is
     * active — including outside Speed Swing mode, where the board does nothing.
     */
    public InteractionHand probe(Minecraft mc, LocalPlayer p, VivecraftBridge vr, boolean speedMode) {
        surface = Surface.NONE;

        InteractionHand hand = p.getMainHandItem().is(VmcItems.hoverboard()) ? InteractionHand.MAIN_HAND
                             : p.getOffhandItem().is(VmcItems.hoverboard())  ? InteractionHand.OFF_HAND
                             : null;
        if (hand == null || !MovementConfig.hoverboardEnabled) {
            warnedGt = false;
            return null;
        }
        if (!speedMode) {
            if (!warnedGt) {
                VmcCompat.overlay(mc, Component.literal(
                        "Hoverboard needs the Speed Swing locomotion mode"), false);
                warnedGt = true;
            }
            return null;
        }
        warnedGt = false;

        Vec3 up = boardAxis(vr, hand == InteractionHand.MAIN_HAND);
        if (up == null) return null; // no controller orientation → leave the hand alone
        boardUp = up;

        if (p.isInWater() || p.isInLava() || p.isPassenger()
                || p.isFallFlying() || p.getAbilities().flying) {
            return hand;
        }

        if (probeWall(mc, p)) {
            surface = Surface.WALL;
        } else if (probeFloor(mc, p)) {
            surface = Surface.FLOOR;
        }
        return hand;
    }

    /**
     * Applies the board to the player's velocity. {@code velocityFresh} = the
     * locomotion code SET the velocity this tick (free-hand grip or throw), so it
     * hasn't been through vanilla's drag yet. Returns true if it rode this tick.
     */
    public boolean apply(LocalPlayer p, boolean velocityFresh) {
        if (surface == Surface.NONE) return false;

        double drag     = clamp(MovementConfig.hoverDrag, 0.0, 1.0);
        double maxSpeed = SettingCaps.hoverMaxSpeed();
        Vec3   v        = velocityFresh ? p.getDeltaMovement() : undoVanillaDecay(p);

        if (surface == Surface.FLOOR) {
            Vec3 h = new Vec3(v.x, 0, v.z).scale(drag)
                    .add(thrust(new Vec3(boardUp.x, 0, boardUp.z)));
            h = capLength(h, maxSpeed);

            // Hover spring. Above the target height we let a rising arc (a jump
            // off a free hand) play out under gravity, and only cap how fast you
            // come back down — a soft landing on the board.
            double err = SettingCaps.hoverHeight() - gap;
            double vy  = clamp(err * HOVER_GAIN, -MAX_SINK, MAX_LIFT);
            if (err < 0 && v.y > vy) vy = v.y;
            p.setDeltaMovement(h.x, vy, h.z);
        } else {
            Vec3 n  = wallNormal;
            Vec3 vt = v.subtract(n.scale(v.dot(n))).scale(drag)
                    .add(thrust(boardUp.subtract(n.scale(boardUp.dot(n)))))
                    .add(0, -WALL_SINK, 0);
            vt = capLength(vt, maxSpeed);
            double pull = clamp((WALL_GAP - gap) * HOVER_GAIN, -0.3, 0.3);
            p.setDeltaMovement(vt.add(n.scale(pull)));
        }
        p.resetFallDistance();
        return true;
    }

    // -----------------------------------------------------------------------
    // Surface probes
    // -----------------------------------------------------------------------

    private boolean probeFloor(Minecraft mc, LocalPlayer p) {
        AABB   bb    = p.getBoundingBox();
        double feet  = bb.minY;
        double reach = SettingCaps.hoverHeight() + FLOOR_SNAP;
        double ix    = bb.getXsize() * 0.5 - 0.05;
        double iz    = bb.getZsize() * 0.5 - 0.05;
        double cx    = p.getX(), cz = p.getZ();

        // Under the body: centre + four inset corners, so standing over an edge
        // still finds the ground.
        double ground = Double.NaN;
        double[][] offs = {{0, 0}, {ix, iz}, {-ix, iz}, {ix, -iz}, {-ix, -iz}};
        for (double[] o : offs) {
            ground = maxNaN(ground, groundAt(mc, p, cx + o[0], cz + o[1], feet + 0.2, feet - reach));
        }

        // Ahead of the body, starting a step high, so the board climbs steps.
        Vec3 dv = p.getDeltaMovement();
        Vec3 ahead = capLength(new Vec3(dv.x, 0, dv.z).scale(LOOKAHEAD_TICKS), LOOKAHEAD_MAX);
        if (ahead.lengthSqr() > 1e-4) {
            ground = maxNaN(ground, groundAt(mc, p, cx + ahead.x, cz + ahead.z,
                                             feet + STEP_PROBE, feet - reach));
        }

        if (Double.isNaN(ground)) return false;
        gap = feet - ground;
        return gap <= reach;
    }

    private boolean probeWall(Minecraft mc, LocalPlayer p) {
        double minSpeed = MovementConfig.hoverWallRideSpeed;
        if (surface == Surface.WALL) minSpeed *= WALL_KEEP;
        if (undoVanillaDecay(p).length() < minSpeed) return false;

        // The board's underside must face sideways, toward the wall.
        Vec3 down = new Vec3(-boardUp.x, 0, -boardUp.z);
        if (down.length() < WALL_TILT) return false;
        down = down.normalize();

        AABB   bb    = p.getBoundingBox();
        double halfW = bb.getXsize() * 0.5;
        Vec3   c     = bb.getCenter();
        BlockHitResult hit = mc.level.clip(new ClipContext(c, c.add(down.scale(halfW + WALL_SNAP)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()
                || !hit.getDirection().getAxis().isHorizontal()) {
            return false;
        }
        Direction face = hit.getDirection();
        wallNormal = new Vec3(face.getStepX(), 0, face.getStepZ());
        gap = Math.abs(hit.getLocation().subtract(c).dot(wallNormal)) - halfW;
        return true;
    }

    // Top of whatever is below (x, z) between fromY and toY (blocks or fluid
    // surfaces — the board skims water), or NaN.
    private static double groundAt(Minecraft mc, LocalPlayer p, double x, double z,
                                   double fromY, double toY) {
        BlockHitResult hit = mc.level.clip(new ClipContext(new Vec3(x, fromY, z), new Vec3(x, toY, z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()
                || hit.getDirection() != Direction.UP) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    // The board's thrust axis: the controller's up axis, pitched by the
    // configured offset (+ tips the nose, i.e. the pointing direction, down).
    private static Vec3 boardAxis(VivecraftBridge vr, boolean main) {
        double a = Math.toRadians(MovementConfig.hoverTiltOffset);
        Vec3 up = vr.getHandAxis(main, 0.0, Math.cos(a), -Math.sin(a));
        if (up == null || up.lengthSqr() < 1e-6) return null;
        return up.normalize();
    }

    // Push along the surface-parallel part of the board axis: none inside the
    // deadzone, ramping to full hoverAccel at 45°.
    private static Vec3 thrust(Vec3 tangent) {
        double len  = tangent.length();
        double dead = clamp(MovementConfig.hoverDeadzone, 0.0, FULL_TILT - 0.01);
        if (len <= dead) return Vec3.ZERO;
        double k = clamp((len - dead) / (FULL_TILT - dead), 0.0, 1.0);
        return tangent.scale(SettingCaps.hoverAccel() * k / len);
    }

    // Last tick's velocity as WE set it: vanilla has since applied its drag (and
    // gravity), which would otherwise stack on top of hoverDrag.
    private static Vec3 undoVanillaDecay(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        double f = VANILLA_AIR_DRAG;
        if (p.onGround()) {
            BlockPos below = BlockPos.containing(p.getX(), p.getBoundingBox().minY - 0.5, p.getZ());
            f *= p.level().getBlockState(below).getBlock().getFriction();
        }
        double y = p.isNoGravity() ? v.y : v.y / VANILLA_Y_DRAG + VANILLA_GRAVITY;
        return new Vec3(v.x / f, y, v.z / f);
    }

    private static Vec3 capLength(Vec3 v, double max) {
        double len = v.length();
        return (len > max && len > 0) ? v.scale(max / len) : v;
    }

    private static double maxNaN(double a, double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return Math.max(a, b);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
