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
// The hover comes out of the board's MAGNETS, not your body: each tick a ray
// is cast out of the underside of each of the four magnet pads (see
// HoverboardModel), and only a surface right under the magnets can hold you.
//
// Surfaces:
//   FLOOR — the board's underside faces ground within reach AND the board is
//           under your body (not held out to the side). A P-controller then holds
//           your feet hoverHeight above it (max 0.5 on servers so they don't
//           see "flying"; up to 4 in singleplayer or with op). Ground slightly AHEAD is probed too, so you rise over
//           1-block steps instead of hitting them (step-up doesn't run airborne).
//   WALL  — the board's underside faces a wall close to the board. It pushes
//           you away from the wall until the board is hoverHeight off it. At
//           hoverWallRideSpeed or faster you RIDE the wall instead: held at that
//           distance with gravity cancelled down to a gentle slide. Below the
//           speed it only pushes, and you fall normally.
//
// Driven by GorillaLocomotionHandler (Speed Swing and Gorilla Tag modes):
//   probe() before the physics, so the hand holding the board never grips and
//            the non-grip friction/gravity code stands aside while riding;
//   apply() after the physics, layering the board on top of any free-hand push.
// In Gorilla Tag mode the clone owns the body (no vanilla drag/gravity): the
// velocity we write is picked up by the clone next tick, which then adds its own
// gravity — apply() is told that gravity so it can pre-compensate the hover.
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
    private static final double FLOOR_SNAP = 1.5;
    // Probe ground this far ahead per unit of velocity (ticks), capped in blocks,
    // and never closer than just past the body's edge (slow approaches).
    private static final double LOOKAHEAD_TICKS = 6.0;
    private static final double LOOKAHEAD_MAX   = 3.0;
    private static final double LOOKAHEAD_MIN   = 0.6;
    private static final int    LOOKAHEAD_STEPS = 4;
    // Look-ahead rays start this high above the CURRENT ground, so steps of up
    // to one block register (and two-block walls don't).
    private static final double STEP_PROBE      = 1.1;

    // Fraction of the hover-height error closed per tick (P-controller), and how
    // fast that may lift you. Kept gentle so touching down never fires you up
    // like a pogo stick; climbing a step ahead uses its own, faster rise.
    private static final double HOVER_GAIN = 0.45;
    private static final double MAX_LIFT   = 0.15;
    private static final double MAX_SINK   = 0.6;
    private static final double MAX_CLIMB  = 0.6;

    // The board must be within this tilt of level (cosine: 60°) to hover over a
    // floor. Tipped further, its field points sideways and only finds walls.
    private static final double FLOOR_MIN_UP = 0.5;
    // Ticks the floor hover holds on after the magnets lose the ground (a
    // wobble of the wrist), as long as ground is still in reach below you.
    private static final int    FLOOR_GRACE  = 4;

    // Where the magnets sit relative to the controller, in blocks: each pad's
    // centre BEHIND the grip along the board (the board runs back along the
    // forearm), and how far below the grip their undersides are. Matches the
    // four pads in HoverboardModel as drawn in the hand.
    private static final double[] MAGNET_BACK = { -0.01, 0.18, 0.36, 0.55 };
    // The field is sampled at each pad AND halfway between neighbouring pads,
    // so a narrow ledge or hole edge between two pads isn't missed.
    private static final int FIELD_SAMPLES = MAGNET_BACK.length * 2 - 1;
    private static final double   MAGNET_DROP = 0.16;

    // How far the magnets' field reaches for ground, on top of the hover height.
    // Long enough for a board held at a hanging hand while hovering (and tilted
    // 45°), short enough that a raised board doesn't hold you up.
    private static final double FLOOR_REACH  = 1.6;
    // Each magnet's ray starts this far back up inside the board, so a magnet
    // pushed into the ground (a low hand, or a short Monke-look body) still
    // finds the surface it is buried in instead of starting inside the block.
    private static final double[] FIELD_BACKUP = { 0.6, 0.3, 0.0 };
    // For the floor, the magnet must be within this far (horizontally) of your
    // body's edge — i.e. under you, not held out to the side.
    private static final double UNDER_MARGIN = 0.35;

    // Walls: how far from the board a wall can be and still push, the gentle
    // slide-down while riding, the strongest push per tick, and the exit
    // hysteresis on the ride speed.
    private static final double WALL_REACH = 1.0;
    private static final double WALL_SINK  = 0.015;
    private static final double WALL_PUSH  = 0.4;
    private static final double WALL_KEEP  = 0.8;

    private Surface surface = Surface.NONE;
    private boolean wallRide;                     // WALL: riding it, not just pushing off
    private final Vec3[] magnets = new Vec3[FIELD_SAMPLES]; // pad undersides (+ midpoints), world space
    private Vec3    boardUp = new Vec3(0, 1, 0); // thrust axis, world space
    private double  gap;                          // feet→floor or board→wall (blocks)
    private Vec3    wallNormal = Vec3.ZERO;       // out of the wall face
    private int     floorGrace;                   // FLOOR_GRACE countdown
    private double  climbVy;                      // vertical speed needed to clear the step ahead
    private double  stepTop = Double.NaN;         // top of the step ahead (FLOOR), or NaN
    private Vec3    lastH = Vec3.ZERO;            // horizontal velocity written last tick (FLOOR)
    private double  lastVy;                       // vertical velocity written last tick (FLOOR), else 0
    private boolean warnedGt = false;
    private boolean gtPhysics = false;           // Gorilla Tag clone owns the body

    /** True while the board is holding you over a floor or wall this tick. */
    public boolean isRiding() {
        return surface == Surface.FLOOR || (surface == Surface.WALL && wallRide);
    }

    /**
     * Finds the hand holding a hoverboard and the surface it rides this tick.
     * Returns that hand (its grip must be skipped), or null when no board is
     * active — including in the Charge Jump mode, where the board does nothing.
     */
    public InteractionHand probe(Minecraft mc, LocalPlayer p, VivecraftBridge vr,
                                 boolean speedMode, boolean gtMode) {
        surface = Surface.NONE;
        boolean wasRide = wallRide;
        wallRide = false;
        gtPhysics = gtMode;

        InteractionHand hand = p.getMainHandItem().is(VmcItems.hoverboard()) ? InteractionHand.MAIN_HAND
                             : p.getOffhandItem().is(VmcItems.hoverboard())  ? InteractionHand.OFF_HAND
                             : null;
        if (hand == null || !MovementConfig.hoverboardEnabled) {
            warnedGt = false;
            return null;
        }
        if (!speedMode && !gtMode) {
            if (!warnedGt) {
                VmcCompat.overlay(mc, Component.literal(
                        "Hoverboard needs the Speed Swing or Gorilla Tag locomotion mode"), false);
                warnedGt = true;
            }
            return null;
        }
        warnedGt = false;

        boolean main = hand == InteractionHand.MAIN_HAND;
        Vec3 up   = boardAxis(vr, main, true);
        Vec3 back = boardAxis(vr, main, false);
        Vec3[] pose = vr.getTickPose();
        if (up == null || back == null || pose == null) return null; // no controller → leave the hand alone
        boardUp = up;
        Vec3 grip = pose[main ? 1 : 2].subtract(up.scale(MAGNET_DROP));
        for (int i = 0; i < magnets.length; i++) {
            double b = (i % 2 == 0) ? MAGNET_BACK[i / 2]
                                    : (MAGNET_BACK[i / 2] + MAGNET_BACK[i / 2 + 1]) * 0.5;
            magnets[i] = grip.add(back.scale(b));
        }

        if (p.isInWater() || p.isInLava() || p.isPassenger()
                || p.isFallFlying() || p.getAbilities().flying) {
            return hand;
        }

        // Only what lies right under the magnets can hold the board up: cast each
        // sample's field out of the underside and use the nearest surface hit.
        // A magnet BURIED in a block (falling into a hole, a low hand) wins outright:
        // the face it went through is found by sweeping from your body to it.
        // Reach is measured straight down: a board tilted for speed casts on a
        // slant, so its ray is lengthened to still touch the same ground.
        double reach = (FLOOR_REACH + SettingCaps.hoverHeight())
                     / Math.max(boardUp.y, FLOOR_MIN_UP);
        Vec3   body  = p.getBoundingBox().getCenter();
        BlockHitResult hit = null;
        Vec3   from = null;
        double best = Double.MAX_VALUE;
        for (Vec3 m : magnets) {
            BlockHitResult h = null;
            double d = 0;
            for (double b : FIELD_BACKUP) {
                Vec3 start = m.add(boardUp.scale(b));
                BlockHitResult r = mc.level.clip(new ClipContext(start, m.subtract(boardUp.scale(reach)),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
                if (r.getType() == HitResult.Type.BLOCK && r.isInside()) continue; // start in a block
                if (r.getType() == HitResult.Type.BLOCK) {
                    h = r;
                    d = r.getLocation().distanceTo(m) * Math.signum(m.subtract(r.getLocation()).dot(boardUp));
                }
                break;
            }
            if (h == null && isBuried(mc, p, m)) {
                BlockHitResult r = mc.level.clip(new ClipContext(body, m,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (r.getType() == HitResult.Type.BLOCK && !r.isInside()) {
                    h = r;
                    d = -r.getLocation().distanceTo(m);
                }
            }
            if (h != null && d < best) { best = d; hit = h; from = m; }
        }
        boolean level = boardUp.y >= FLOOR_MIN_UP;
        if (hit == null || hit.getDirection() != Direction.UP) {
            // Lost the ground for a moment while hovering: hold on briefly.
            if (floorGrace > 0 && level && probeFloor(mc, p)) {
                floorGrace--;
                surface = Surface.FLOOR;
                logBoard(p, "floor grace " + floorGrace, from);
                return hand;
            }
            floorGrace = 0;
        }
        if (hit == null) {
            logBoard(p, "no surface under the magnets", null);
            return hand;
        }

        Direction face = hit.getDirection();
        if (face == Direction.UP) {
            if (level && underBody(p, from) && probeFloor(mc, p)) {
                surface    = Surface.FLOOR;
                floorGrace = FLOOR_GRACE;
            }
        } else if (face.getAxis().isHorizontal()) {
            Vec3 n = new Vec3(face.getStepX(), 0, face.getStepZ());
            double d = from.subtract(hit.getLocation()).dot(n);
            if (d <= WALL_REACH) {
                surface    = Surface.WALL;
                wallNormal = n;
                gap        = d;
                double minSpeed = MovementConfig.hoverWallRideSpeed * (wasRide ? WALL_KEEP : 1.0);
                Vec3 cur = gtPhysics ? p.getDeltaMovement() : undoVanillaDecay(p);
                wallRide = cur.length() >= minSpeed;
            }
        }
        logBoard(p, face + (best < 0 ? " BURIED " : " ") + "hit at " + fmt(hit.getLocation())
                + (face == Direction.UP ? " under=" + underBody(p, from) : ""), from);
        return hand;
    }

    // True if the point sits inside a block's collision shape.
    private static boolean isBuried(Minecraft mc, LocalPlayer p, Vec3 m) {
        BlockHitResult r = mc.level.clip(new ClipContext(m, m.add(0, 1e-3, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.BLOCK && r.isInside();
    }

    // One debug-log line per tick while a board is held: what the magnets see.
    private void logBoard(LocalPlayer p, String what, Vec3 from) {
        if (!VmcDebugLog.on()) return;
        VmcDebugLog.event("BOARD", "surface=" + surface + (wallRide ? "(ride)" : "")
                + " gap=" + String.format("%.2f", gap) + " up=" + fmt(boardUp)
                + " magnet=" + (from != null ? fmt(from) : fmt(magnets[0]))
                + " feet=" + String.format("%.2f", p.getBoundingBox().minY) + " | " + what);
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }

    /**
     * Applies the board to the player's velocity. {@code velocityFresh} = the
     * locomotion code SET the velocity this tick (free-hand grip or throw), so it
     * hasn't been through vanilla's drag yet. Returns true if it rode this tick.
     */
    public boolean apply(LocalPlayer p, boolean velocityFresh) {
        return apply(p, velocityFresh, 0.0);
    }

    /**
     * {@code lift} is added to the vertical velocity written — the gravity the
     * physics will take off again before the move (Gorilla Tag mode).
     */
    public boolean apply(LocalPlayer p, boolean velocityFresh, double lift) {
        if (surface != Surface.FLOOR) lastVy = 0.0;
        if (surface == Surface.NONE) return false;

        if (surface == Surface.WALL && !wallRide) {
            // Too slow to ride: the board only shoves you off the wall. Everything
            // but the push along the wall normal is left to the normal physics.
            Vec3   v    = p.getDeltaMovement();
            double vn   = v.dot(wallNormal);
            double push = clamp((SettingCaps.hoverWallGap() - gap) * HOVER_GAIN, 0.0, WALL_PUSH);
            if (push > vn) p.setDeltaMovement(v.add(wallNormal.scale(push - vn)));
            return false;
        }

        double drag     = clamp(MovementConfig.hoverDrag, 0.0, 1.0);
        double maxSpeed = SettingCaps.hoverMaxSpeed();
        Vec3   v        = velocityFresh ? p.getDeltaMovement() : undoVanillaDecay(p);

        if (surface == Surface.FLOOR) {
            // Bumped into a step last tick: hop onto it and keep the speed the
            // collision just ate, instead of stopping dead and bouncing.
            Vec3 hv = new Vec3(v.x, 0, v.z);
            if (p.horizontalCollision && !Double.isNaN(stepTop) && stepUp(p)) {
                if (lastH.lengthSqr() > hv.lengthSqr()) hv = lastH;
            }
            Vec3 h = hv.scale(drag)
                    .add(thrust(new Vec3(boardUp.x, 0, boardUp.z)));
            h = capLength(h, maxSpeed);
            lastH = h;

            // Hover spring. Above the target height we let a rising arc (a jump
            // off a free hand) play out, and only cap how fast you come back down —
            // a soft landing on the board. The arc keeps its CURRENT speed with
            // no gravity added back, so the physics slows it down; re-adding the
            // gravity here kept every small bounce rising forever.
            double err = SettingCaps.hoverHeight() - gap;
            double vy  = clamp(err * HOVER_GAIN, -MAX_SINK, MAX_LIFT);
            if (climbVy > 0.0) vy = Math.max(vy, climbVy); // only when a step is ahead
            vy += lift;
            // Only an OUTSIDE push (more speed than the board gave last tick) counts
            // as a jump to carry: the board's own climb speed must not overshoot.
            double rise = p.getDeltaMovement().y;
            if (err < 0 && rise > vy && rise > lastVy + 0.02) vy = rise;
            p.setDeltaMovement(h.x, vy, h.z);
            lastVy = vy;
        } else {
            Vec3 n  = wallNormal;
            Vec3 vt = v.subtract(n.scale(v.dot(n))).scale(drag)
                    .add(thrust(boardUp.subtract(n.scale(boardUp.dot(n)))))
                    .add(0, -WALL_SINK, 0);
            vt = capLength(vt, maxSpeed);
            double pull = clamp((SettingCaps.hoverWallGap() - gap) * HOVER_GAIN, -0.3, WALL_PUSH);
            p.setDeltaMovement(vt.add(n.scale(pull)).add(0, lift, 0));
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
        climbVy = 0.0;
        stepTop = Double.NaN;

        // Under the body: centre + four inset corners, so standing over an edge
        // still finds the ground.
        double ground = Double.NaN;
        double[][] offs = {{0, 0}, {ix, iz}, {-ix, iz}, {ix, -iz}, {-ix, -iz}};
        for (double[] o : offs) {
            ground = maxNaN(ground, groundAt(mc, p, cx + o[0], cz + o[1], feet + 0.2, feet - reach));
        }
        if (Double.isNaN(ground)) return false;
        gap = feet - ground;
        if (gap > reach) return false;

        // Ahead of the body, starting a step above the current ground, so the
        // board sees a step coming and starts rising early enough to sail over
        // it at full speed: the nearest higher ground ahead sets how fast to
        // climb (its height over the time left to reach it).
        Vec3   dv    = p.getDeltaMovement();
        Vec3   hdir  = new Vec3(dv.x, 0, dv.z);
        double speed = hdir.length();
        if (speed > 1e-3) {
            hdir = hdir.scale(1.0 / speed);
            double edge = bb.getXsize() * 0.5;
            double far  = clamp(speed * LOOKAHEAD_TICKS, LOOKAHEAD_MIN, LOOKAHEAD_MAX);
            for (int i = 1; i <= LOOKAHEAD_STEPS; i++) {
                double dist = edge + far * i / LOOKAHEAD_STEPS;
                double top  = groundAt(mc, p, cx + hdir.x * dist, cz + hdir.z * dist,
                                       ground + STEP_PROBE, ground - 0.5);
                if (Double.isNaN(top) || top <= ground + 0.05) continue;
                double need  = top + SettingCaps.hoverHeight() - feet;
                double ticks = Math.max(1.0, (dist - edge) / speed);
                climbVy = clamp(need / ticks, 0.0, MAX_CLIMB);
                stepTop = top;
                break;
            }
        }
        return true;
    }

    // Lift the body onto the step ahead (like vanilla step-up, which doesn't
    // run while airborne) if it is at most a block up and there is room.
    private boolean stepUp(LocalPlayer p) {
        AABB   bb   = p.getBoundingBox();
        double rise = stepTop - bb.minY + 0.01;
        if (rise <= 0.0 || rise > STEP_PROBE) return false;
        if (!p.level().noCollision(p, bb.move(0, rise, 0))) return false;
        p.setPos(p.getX(), p.getY() + rise, p.getZ());
        return true;
    }

    // The magnet is under you: horizontally within your body plus a margin, so a
    // board held out to the side doesn't hold you up. (No height test — Monke
    // look shrinks the hitbox to well below where your hands are; a raised board
    // is ruled out by the field's reach instead.)
    private static boolean underBody(LocalPlayer p, Vec3 m) {
        AABB bb = p.getBoundingBox();
        double dx = Math.max(0, Math.abs(m.x - p.getX()) - bb.getXsize() * 0.5);
        double dz = Math.max(0, Math.abs(m.z - p.getZ()) - bb.getZsize() * 0.5);
        return dx * dx + dz * dz <= UNDER_MARGIN * UNDER_MARGIN;
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

    // The board's thrust axis (up=true: the controller's up axis) or its long
    // axis running back toward the tail (up=false: the controller's +Z), both
    // pitched by the configured offset (+ tips the nose, i.e. the pointing
    // direction, down).
    private static Vec3 boardAxis(VivecraftBridge vr, boolean main, boolean up) {
        double a = Math.toRadians(MovementConfig.hoverTiltOffset);
        Vec3 v = up ? vr.getHandAxis(main, 0.0, Math.cos(a), -Math.sin(a))
                    : vr.getHandAxis(main, 0.0, Math.sin(a), Math.cos(a));
        if (v == null || v.lengthSqr() < 1e-6) return null;
        return v.normalize();
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
