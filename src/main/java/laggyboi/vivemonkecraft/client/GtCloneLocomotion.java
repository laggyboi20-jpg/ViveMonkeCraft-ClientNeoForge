package laggyboi.vivemonkecraft.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// =====================================================================
// GORILLA TAG CLONE  (locomotion mode 1)
// =====================================================================
//
// A line-by-line port of the official GorillaLocomotion Player.cs
// (github.com/Another-Axiom/GorillaLocomotion), run the way the
// Gorillatag-Minecraft-Passthrough mod runs it: Gorilla Tag's physics OWNS the
// body. Vanilla walking/gravity/drag are switched off for the local player
// (GtTravelMixin cancels Player#travel), and this class integrates the body
// itself every tick: its own gravity (9.81 m/s², 1 block = 1 m), no air drag,
// hand anchors, the velocity-history throw, and unstick.
//
// What makes it feel like Gorilla Tag instead of the old "GT Anchor" servo:
//   * Hands are swept SPHERES against the real block collision shapes, with
//     Player.cs's slide (0.1% one hand / 3% two hands / ice slip), so a hand
//     sticks exactly where it touched.
//   * The body moves by the FULL anchor correction each tick (1:1), not a
//     fraction of it. That's safe here because the poses come from Vivecraft's
//     pre-tick world pose (VivecraftBridge#tickPose), which already includes
//     the last move — no render-interpolation lag, so no oscillation.
//   * The throw is Player.cs's: while a hand touches, if the average body
//     velocity over the last few ticks exceeds velocityLimit, the body is given
//     avg × jumpMultiplier (capped at maxJumpSpeed). Then it flies under
//     gravity until a hand touches again.
//
// Beyond Player.cs:
//   * WATER — vanilla swimming owns the body (Player#travel runs as normal), but
//     the hands still collide: touching the floor or a wall moves you with the
//     hand and the throw launches you, then the water slows you down as usual.
//   * CLIMBABLES — ladders, vines, scaffolding etc. are grabbable surfaces for
//     the hands (their outline shape, padded), and the body falls no faster than
//     vanilla's ladder speed while inside one.
//   * The hand holding a hoverboard never touches anything (it steers).
//
// All tuning is in Gorilla Tag's own units (metres, m/s) — see MovementConfig gt*.
// =====================================================================

public final class GtCloneLocomotion {

    private static final double DT        = 0.05;   // one game tick, seconds
    private static final double PRECISION = 0.995;  // Player.cs defaultPrecision
    private static final double SLIDE_TWO_HANDS = 0.03;   // defaultSlideFactor
    private static final double SLIDE_ONE_HAND  = 0.001;
    // Climbable outline shapes (a vine is 1/16 thick) are padded by this so a
    // hand can catch them, clipped to the block's own cell.
    private static final double CLIMB_PAD       = 0.1;
    // Vanilla's ladder speed limit (LivingEntity#handleOnClimbable), blocks/tick.
    private static final double CLIMB_MAX_FALL  = 0.15;

    // Set each tick we drive the body; consumed by GtTravelMixin on the next
    // player tick so vanilla travel stays off only while this mode is live.
    public static volatile boolean ownsBody = false;

    private boolean initialized = false;

    // Player.cs state — lastXHandPosition are the "hand followers" (where the
    // hand is drawn), wasXHandTouching the per-hand contact flags.
    private Vec3 lastMain, lastOff;
    private boolean wasMain, wasOff;

    // Rigidbody velocity, blocks per tick.
    private Vec3 vel = Vec3.ZERO;
    // What we last wrote to deltaMovement — anything else found there next tick
    // came from outside (knockback, explosions, server) and is added to vel.
    private Vec3 writtenDm = null;

    private Vec3[] history;
    private int    historyIndex;
    private Vec3   historyAvg = Vec3.ZERO;

    // Last tick ran in water: vanilla travel also moved the body by deltaMovement.
    private boolean vanillaMoved = false;

    // Total displacement applied this tick (teleport detection expects this).
    private Vec3 lastDisplacement = Vec3.ZERO;

    public boolean touchingMain() { return initialized && wasMain; }
    public boolean touchingOff()  { return initialized && wasOff; }
    public Vec3    followerMain() { return lastMain; }
    public Vec3    followerOff()  { return lastOff; }
    public Vec3    lastDisplacement() { return lastDisplacement; }
    /** True if vanilla swimming also moves the body by deltaMovement this tick. */
    public boolean vanillaMoves()     { return initialized && vanillaMoved; }

    public void reset() {
        initialized = false;
        wasMain = wasOff = false;
        lastMain = lastOff = null;
        vel = Vec3.ZERO;
        writtenDm = null;
        history = null;
        historyAvg = Vec3.ZERO;
        lastDisplacement = Vec3.ZERO;
        vanillaMoved = false;
    }

    /** Gravity the clone adds to the body per tick right now (blocks/tick²). */
    public static double gravityPerTick(LocalPlayer player) {
        return player.isInWater() ? 0.0 : SettingCaps.gtGravity() * DT * DT;
    }

    /**
     * One Player.cs Update. Positions are Vivecraft pre-tick world poses.
     * {@code useMain}/{@code useOff} = false leaves that hand out of the physics
     * (it is holding the hoverboard).
     */
    public void tick(LocalPlayer player, Vec3 head, Vec3 rawMain, Vec3 rawOff,
                     boolean useMain, boolean useOff) {
        Level level = player.level();
        double r       = Math.max(0.01, SettingCaps.gtHandRadius());
        double armLen  = Math.max(0.2,  SettingCaps.gtArmLength());
        boolean inWater = player.isInWater();
        double gTick   = gravityPerTick(player);                     // blocks/tick²

        Vec3 curMain = clampArm(rawMain, head, armLen);
        Vec3 curOff  = clampArm(rawOff,  head, armLen);

        if (!initialized || history == null
                || history.length != historyLength()) {
            lastMain = curMain;
            lastOff  = curOff;
            wasMain = wasOff = false;
            vel = player.getDeltaMovement();
            history = new Vec3[historyLength()];
            java.util.Arrays.fill(history, Vec3.ZERO);
            historyIndex = 0;
            historyAvg = Vec3.ZERO;
            initialized = true;
        }

        Vec3 dm = player.getDeltaMovement();
        Vec3 physMove = Vec3.ZERO;
        if (inWater) {
            // ---- WATER: vanilla swimming owns velocity, gravity and drag ----
            vel = dm;
        } else {
            // Outside impulses (knockback, explosions, riptide, server velocity).
            // Coming out of the water, vanilla's swim velocity is simply taken over.
            if (vanillaMoved) vel = dm;
            else if (writtenDm != null && dm.distanceToSqr(writtenDm) > 1.0e-10) {
                vel = vel.add(dm.subtract(writtenDm));
            }

            // ---- RIGIDBODY STEP (Unity runs physics before Update) ----
            vel = vel.add(0, -gTick, 0);
            // Inside a ladder / vine: never fall faster than vanilla's ladder speed.
            if (player.onClimbable() && vel.y < -CLIMB_MAX_FALL) vel = new Vec3(vel.x, -CLIMB_MAX_FALL, vel.z);
            physMove = moveBody(player, vel);
            vel = zeroBlockedAxes(vel, vel, physMove);
            if (player.onGround() && !wasMain && !wasOff) {
                double keep = groundKeep(player);
                vel = new Vec3(vel.x * keep, vel.y, vel.z * keep);
            }
        }
        // Hands and head ride along with the body.
        curMain = curMain.add(physMove);
        curOff  = curOff.add(physMove);
        head    = head.add(physMove);

        // ---- HANDS, FIRST ITERATION ----
        Vec3 gravSweep = new Vec3(0, -2.0 * gTick, 0);
        boolean mainColliding = false, offColliding = false;
        Vec3 firstMain = Vec3.ZERO, firstOff = Vec3.ZERO;

        if (!useMain) { lastMain = curMain; wasMain = false; }
        if (!useOff)  { lastOff  = curOff;  wasOff  = false; }

        Vec3 hit = useMain ? iterativeCast(level, lastMain, r, curMain.subtract(lastMain).add(gravSweep), true) : null;
        if (hit != null) {
            firstMain = wasMain ? lastMain.subtract(curMain) : hit.subtract(curMain);
            vel = Vec3.ZERO;
            mainColliding = true;
        }
        hit = useOff ? iterativeCast(level, lastOff, r, curOff.subtract(lastOff).add(gravSweep), true) : null;
        if (hit != null) {
            firstOff = wasOff ? lastOff.subtract(curOff) : hit.subtract(curOff);
            vel = Vec3.ZERO;
            offColliding = true;
        }

        boolean twoHands = (mainColliding || wasMain) && (offColliding || wasOff);
        Vec3 bodyMove = twoHands ? firstMain.add(firstOff).scale(0.5) : firstMain.add(firstOff);

        // Tracking-glitch guard: no single tick may teleport you.
        double maxStep = armLen;
        if (bodyMove.length() > maxStep) bodyMove = bodyMove.normalize().scale(maxStep);

        Vec3 handMove = moveBody(player, bodyMove);
        curMain = curMain.add(handMove);
        curOff  = curOff.add(handMove);
        head    = head.add(handMove);

        // ---- HANDS, FINAL POSITION (the followers) ----
        hit = useMain ? iterativeCast(level, lastMain, r, curMain.subtract(lastMain), !twoHands) : null;
        if (hit != null) { lastMain = hit; mainColliding = true; }
        else             { lastMain = curMain; }
        hit = useOff ? iterativeCast(level, lastOff, r, curOff.subtract(lastOff), !twoHands) : null;
        if (hit != null) { lastOff = hit; offColliding = true; }
        else             { lastOff = curOff; }

        // ---- STORE VELOCITIES ----
        lastDisplacement = physMove.add(handMove);
        storeVelocity(lastDisplacement);

        // ---- THE JUMP ----
        if (mainColliding || offColliding) {
            double limit  = MovementConfig.gtVelocityLimit * DT;
            double maxJmp = SettingCaps.gtMaxJumpSpeedPerTick();
            double mult   = SettingCaps.gtJumpMultiplier();
            double avgLen = historyAvg.length();
            if (avgLen > limit) {
                vel = (avgLen * mult > maxJmp)
                        ? historyAvg.normalize().scale(maxJmp)
                        : historyAvg.scale(mult);
            }
        }

        // ---- UNSTICK ----
        double unstick = MovementConfig.gtUnstickDistance;
        if (mainColliding && curMain.distanceTo(lastMain) > unstick && clearLine(level, player, head, curMain)) {
            lastMain = curMain;
            mainColliding = false;
        }
        if (offColliding && curOff.distanceTo(lastOff) > unstick && clearLine(level, player, head, curOff)) {
            lastOff = curOff;
            offColliding = false;
        }

        wasMain = mainColliding;
        wasOff  = offColliding;

        player.setDeltaMovement(vel);
        writtenDm = vel;
        if (mainColliding || offColliding) player.resetFallDistance();
        // In water vanilla travel still runs (swimming) on the velocity we wrote.
        vanillaMoved = inWater;
        ownsBody = !inWater;
    }

    // -----------------------------------------------------------------------
    // Body movement
    // -----------------------------------------------------------------------

    // Moves the player with vanilla block collision and returns the distance
    // actually travelled. Vivecraft's LocalPlayer#move override only carries a
    // HORIZONTAL move through (and shifts its room origin with it) when
    // deltaMovement shows > 0.01 of horizontal speed, so slow hand pulls would be
    // dropped — briefly present a marker velocity for the call, then restore.
    private static Vec3 moveBody(LocalPlayer player, Vec3 d) {
        if (d.lengthSqr() < 1.0e-12) return Vec3.ZERO;
        Vec3 before = player.position();
        Vec3 saved  = player.getDeltaMovement();
        player.setDeltaMovement(new Vec3(0.02, d.y, 0.02));
        player.move(MoverType.SELF, d);
        player.setDeltaMovement(saved);
        return player.position().subtract(before);
    }

    // Rigidbody contact: any axis the move got clipped on loses its velocity.
    private static Vec3 zeroBlockedAxes(Vec3 v, Vec3 wanted, Vec3 got) {
        double x = Math.abs(got.x - wanted.x) > 1.0e-6 ? 0 : v.x;
        double y = Math.abs(got.y - wanted.y) > 1.0e-6 ? 0 : v.y;
        double z = Math.abs(got.z - wanted.z) > 1.0e-6 ? 0 : v.z;
        return new Vec3(x, y, z);
    }

    // Horizontal velocity kept per tick while the body rests on the ground
    // (the body capsule's friction). Slippery blocks (ice) keep almost all of it.
    private static double groundKeep(LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.1, player.getZ());
        float friction = player.level().getBlockState(below).getBlock().getFriction();
        if (friction > 0.9f) return 0.98;
        return Math.max(0.0, Math.min(1.0, MovementConfig.gtGroundFriction));
    }

    private void storeVelocity(Vec3 displacement) {
        historyIndex = (historyIndex + 1) % history.length;
        Vec3 oldest = history[historyIndex];
        historyAvg = historyAvg.add(displacement.subtract(oldest).scale(1.0 / history.length));
        history[historyIndex] = displacement;
    }

    private static int historyLength() {
        return (int) Math.max(1, Math.min(20, Math.round(MovementConfig.gtVelocityHistory)));
    }

    private static Vec3 clampArm(Vec3 hand, Vec3 head, double armLen) {
        Vec3 off = hand.subtract(head);
        return off.length() < armLen ? hand : head.add(off.normalize().scale(armLen));
    }

    private static boolean clearLine(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    // -----------------------------------------------------------------------
    // Sphere casts against block collision shapes
    // -----------------------------------------------------------------------
    //
    // The hand sphere is approximated by a cube of half-size r, which against
    // Minecraft's axis-aligned blocks is exact on faces and only rounds corners
    // differently. A cast is a segment test against every nearby block box
    // expanded by r (Minkowski sum).

    private record Hit(Vec3 pos, Vec3 normal, double slip) {}

    // Player.cs IterativeCollisionSphereCast: returns where the hand ends up if it
    // touched something along the way, or null if the path was clear.
    private static Vec3 iterativeCast(Level level, Vec3 start, double r, Vec3 move, boolean singleHand) {
        Hit h = cast(level, start, r, move);
        if (h == null) return null;

        Vec3 first = h.pos;
        double slip = h.slip >= 0 ? h.slip : (singleHand ? SLIDE_ONE_HAND : SLIDE_TWO_HANDS);
        Vec3 target = start.add(move);
        Vec3 rest   = target.subtract(first);
        Vec3 slide  = rest.subtract(h.normal.scale(rest.dot(h.normal))).scale(slip);

        Hit h2 = cast(level, first, r, slide);
        if (h2 != null) return h2.pos;
        Vec3 slid = first.add(slide);
        Hit h3 = cast(level, slid, r, target.subtract(slid));
        if (h3 != null) return h3.pos;
        return first;
    }

    private static Hit cast(Level level, Vec3 start, double r, Vec3 move) {
        Vec3 end = start.add(move);
        AABB sweep = new AABB(start, end).inflate(r + 0.01);
        double rCast = r * PRECISION;

        double bestT = Double.MAX_VALUE;
        AABB   bestBox = null;
        int    bestAxis = -1;
        double bestSign = 0;
        boolean bestInside = false;

        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (VoxelShape shape : level.getBlockCollisions(null, sweep)) boxes.addAll(shape.toAabbs());
        addClimbables(level, sweep, boxes);

        for (AABB box : boxes) {
            AABB e = box.inflate(rCast);
            double[] res = segmentEntry(start, move, e);
            if (res == null) continue;
            if (res[0] < bestT) {
                bestT = res[0];
                bestBox = box;
                bestAxis = (int) res[1];
                bestSign = res[2];
                bestInside = res[3] != 0;
            }
        }
        if (bestBox == null) return null;

        Vec3 p = start.add(move.scale(Math.max(0, bestT)));
        Vec3 n;
        if (bestInside) {
            // Already overlapping a block: stay exactly where we are (Player.cs's
            // smaller sanity spherecast → endPosition = startPosition).
            n = bestAxis == 0 ? new Vec3(bestSign, 0, 0)
              : bestAxis == 1 ? new Vec3(0, bestSign, 0) : new Vec3(0, 0, bestSign);
            return new Hit(start, n, surfaceSlip(level, start, Vec3.ZERO, 0, n));
        }
        // Rest exactly a full radius off the face we hit (Player.cs:
        // hitInfo.point + hitInfo.normal * sphereRadius).
        switch (bestAxis) {
            case 0 -> { double x = bestSign > 0 ? bestBox.maxX + r : bestBox.minX - r; p = new Vec3(x, p.y, p.z); n = new Vec3(bestSign, 0, 0); }
            case 1 -> { double y = bestSign > 0 ? bestBox.maxY + r : bestBox.minY - r; p = new Vec3(p.x, y, p.z); n = new Vec3(0, bestSign, 0); }
            default -> { double z = bestSign > 0 ? bestBox.maxZ + r : bestBox.minZ - r; p = new Vec3(p.x, p.y, z); n = new Vec3(0, 0, bestSign); }
        }
        return new Hit(p, n, surfaceSlip(level, start, move, bestT, n));
    }

    // Ladders, vines, scaffolding, twisting vines... (#minecraft:climbable) have
    // little or no collision, so the hands grab their OUTLINE instead, padded so
    // a thin vine is catchable and clipped to the block's own cell.
    private static void addClimbables(Level level, AABB sweep, java.util.List<AABB> out) {
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(sweep.minX), (int) Math.floor(sweep.minY), (int) Math.floor(sweep.minZ),
                (int) Math.floor(sweep.maxX), (int) Math.floor(sweep.maxY), (int) Math.floor(sweep.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.CLIMBABLE)) continue;
            AABB cell = new AABB(pos);
            for (AABB local : state.getShape(level, pos).toAabbs()) {
                AABB b = local.move(pos).inflate(CLIMB_PAD).intersect(cell);
                if (b.getXsize() > 0 && b.getYsize() > 0 && b.getZsize() > 0) out.add(b);
            }
        }
    }

    // Slab test of the segment start→start+move against box. Returns
    // {tEntry, axis, normalSign, startedInside} or null. A start already inside the box counts
    // as a hit at t=0 on the face it is closest to (Player.cs's "smaller sanity
    // spherecast" case: a hand resting in a surface stays put).
    private static double[] segmentEntry(Vec3 s, Vec3 d, AABB b) {
        double[] sMin = { b.minX, b.minY, b.minZ };
        double[] sMax = { b.maxX, b.maxY, b.maxZ };
        double[] o    = { s.x, s.y, s.z };
        double[] v    = { d.x, d.y, d.z };

        boolean inside = o[0] > sMin[0] && o[0] < sMax[0]
                && o[1] > sMin[1] && o[1] < sMax[1]
                && o[2] > sMin[2] && o[2] < sMax[2];
        if (inside) {
            int axis = 0; double sign = 0; double best = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                double toMin = o[i] - sMin[i], toMax = sMax[i] - o[i];
                if (toMin < best) { best = toMin; axis = i; sign = -1; }
                if (toMax < best) { best = toMax; axis = i; sign = 1; }
            }
            return new double[] { 0, axis, sign, 1 };
        }

        double tEnter = 0, tExit = 1;
        int axis = -1; double sign = 0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(v[i]) < 1.0e-12) {
                if (o[i] <= sMin[i] || o[i] >= sMax[i]) return null;
                continue;
            }
            double t1 = (sMin[i] - o[i]) / v[i];
            double t2 = (sMax[i] - o[i]) / v[i];
            double near = Math.min(t1, t2), far = Math.max(t1, t2);
            if (near > tEnter) { tEnter = near; axis = i; sign = v[i] > 0 ? -1 : 1; }
            if (far < tExit) tExit = far;
            if (tEnter > tExit) return null;
        }
        if (axis < 0) return null;
        return new double[] { tEnter, axis, sign, 0 };
    }

    // Surface.cs slipPercentage: slippery blocks (ice) let the hand slide; every
    // other block returns -1 = use Player.cs's default slide factor.
    private static double surfaceSlip(Level level, Vec3 start, Vec3 move, double t, Vec3 normal) {
        Vec3 p = start.add(move.scale(Math.max(0, t))).subtract(normal.scale(0.05));
        float friction = level.getBlockState(BlockPos.containing(p)).getBlock().getFriction();
        return friction > 0.9f ? Math.max(0.0, Math.min(1.0, MovementConfig.gtIceSlip)) : -1;
    }
}
