package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.compat.VmcCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

// =====================================================================
// GORILLA TAG LOCOMOTION HANDLER  (v5 — speed-based)
// =====================================================================
//
// Same spirit as the official Gorilla Tag (push/pull a surface, body moves the
// opposite way), but tuned for how VR + Vivecraft actually behave:
//
//   * Movement is driven by how FAST your hand is moving, not how far it is
//     pushed into a block. So a STILL hand = zero movement (you stay put, even
//     if the controller is resting in the ground), and only a real SWING moves
//     you. Gentle placement = nothing; a fast push = a launch. This is what fixes
//     the "it jumps when I set my controller down" problem.
//
//   * Per tick, for each hand touching a block:
//       swing  = how far your hand moved RELATIVE TO YOUR HEAD this tick
//       body velocity += -swing * pullStrength      (you go opposite your hand)
//     Measuring relative to the head means moving your body never feeds back into
//     the math — only your real arm motion counts.
//
//   * We SET velocity (not position) because Vivecraft drives the player position
//     from your headset and ignores direct position writes. Setting velocity to
//     the swing means: still hand -> ~0 velocity -> you hang/stay; moving hand ->
//     you move.
//
//   * The swing is measured on your RAW hand (true 1:1), while the longer "reach"
//     is only used to DETECT the grab — so reach doesn't make motion twitchy.
//
//   * We average recent swing speed; when you LET GO above velocityLimit we fling
//     you (velocity = average * jumpMultiplier, capped). That's the jump.
// =====================================================================

public class GorillaLocomotionHandler {

    // ---- LOCOMOTION MODE REGISTRY ----
    public static final int MODE_SPEED_SWING = 0;
    public static final int MODE_GT_ANCHOR   = 1;
    public static final int MODE_CHARGE_JUMP = 2;
    public static final int MODE_COUNT       = 3;

    private static final String[] MODE_NAMES = { "Speed Swing", "GT Anchor", "Charge Jump" };

    public static String modeName(int i) {
        return (i >= 0 && i < MODE_NAMES.length) ? MODE_NAMES[i] : ("Mode " + i);
    }

    // ---- CHARGE-JUMP tuning ----
    private static final int CHARGE_FULL_TICKS = 30;
    private static final int CHARGE_MIN_FIRE_TICKS = 2;

    // A grab counts as a FLOOR grab when the grabbed point is at least this many
    // blocks BELOW your head (i.e. you reached down to the ground). Above that it's
    // treated as a WALL grab. Your head is ~1.5 above the floor, so ~1.0 cleanly
    // separates "hand on the ground" from "hand on a wall at body height".
    private static final double FLOOR_GRIP_DEPTH = 1.0;

    // Downward slide rate (blocks/tick) of a NON-ice wall at wallStickiness 0. The
    // actual rate scales with stickiness: (1 - stick) * WALL_SLIDE_CAP, applied as an
    // offset on every grip tick so hand movement can never cancel it (anti-hover).
    // Ice walls instead slide freely under gravity (uncapped), so ice always feels
    // more slippery than any normal wall.
    private static final double WALL_SLIDE_CAP = 0.40;

    // Ticks of continuous wall grip over which the slide ramps from 0 to full rate.
    // 10 ticks = 0.5 s: quick pushes/mantles finish before the slide bites, while
    // hanging still reaches full slide fast enough that hovering stays impossible.
    private static final int WALL_SLIDE_RAMP_TICKS = 10;

    // Consecutive ticks the current low-stick wall grip has lasted (drives the ramp).
    // Reset whenever the wall grip ends (floor grip, release, teleport, desync).
    private int wallSlideTicks = 0;

    // Max horizontal distance (blocks) the VR-reported head may sit from the player
    // entity before we treat the VR data as DESYNCED (stale room origin right after a
    // Vivecraft teleport). In roomscale the head wanders < ~1 block from the body, so
    // 1.5 gives comfortable margin while still catching even short 2-block teleports.
    private static final double VR_DESYNC_DIST = 1.5;

    // Wall-stuck anti-freeze constants.
    // After WALL_STUCK_MAX ticks (2.5 s) of being stationary while wall-gripping,
    // the mod briefly forces wallStickiness to 0 so the player falls free.
    private static final int    WALL_STUCK_MAX     = 50;   // ticks (2.5 s at 20 tps)
    private static final int    DROP_RELEASE_TICKS = 10;   // ticks of forced drop (~0.5 s)
    private static final double STUCK_THRESHOLD    = 0.05; // blocks/tick to count as "moving"

    // -----------------------------------------------------------------------
    // PER-HAND STATE
    // -----------------------------------------------------------------------

    // gripping   = was this hand touching a block last tick.
    // floorGrip  = is this grab a FLOOR grab (hand on a supporting top face) rather
    //              than a WALL grab (side face). Set at first contact, and can be
    //              PROMOTED wall→floor mid-grip when the hand lands on a top face
    //              (e.g. brushing a step's side, then planting on its top to mantle).
    //              Never demoted floor→wall, so behaviour can't flip-flop.
    // prevOffset = (raw hand - head) last tick, used to measure this tick's swing.
    // wasGripping = gripping state from the previous tick (used to detect first-contact).
    private static class HandState {
        boolean gripping    = false;
        boolean floorGrip   = false;
        boolean wasGripping = false;
        Vec3    prevOffset  = null;

        // LEGACY groundedGripRelease: once a still floor grip is dropped because you're
        // standing on the ground, this stays true so the SAME hand won't instantly
        // re-grip the floor it's still resting on (which would re-wedge you). Cleared
        // the moment the hand actually swings again or leaves the surface.
        boolean floorGripSuppressed = false;

        // GT ANCHOR MODE ONLY: the REAL hand's world position at the moment of
        // contact (arm-clamped, NO reach extension). While gripping, the body is
        // dragged each tick so the real hand returns here — Player.cs
        // "lastHandPosition". Measured on the REAL hand because the drag must see
        // surface PENETRATION (pressing into the floor = upward drag = push-off).
        Vec3    anchor        = null;

        // GT ANCHOR MODE ONLY: the surface-clamped touch point at contact —
        // used only for the hand marker so the cube sits on the block face.
        Vec3    anchorDisplay = null;

        // GT ANCHOR MODE ONLY: (realHand − head) at contact. Unstick measures
        // against this so it reflects PHYSICAL hand stray, unaffected by the
        // push-strength anchor receding.
        Vec3    gripOffset    = null;

        int     latchTicks    = 0;

        void release() {
            gripping      = false;
            floorGrip     = false;
            prevOffset    = null;
            anchor        = null;
            anchorDisplay = null;
            gripOffset    = null;
            latchTicks    = 0;
            // wasGripping intentionally NOT cleared — the grab-end logic needs it this tick
        }

        void tickStart() {
            wasGripping = gripping;
        }
    }

    // -----------------------------------------------------------------------
    // STATE
    // -----------------------------------------------------------------------

    private final VivecraftBridge vr = new VivecraftBridge();
    private final HoverboardHandler hoverboard = new HoverboardHandler();
    private final HandState mainHand = new HandState();
    private final HandState offHand  = new HandState();

    // Rolling average of the intended swing velocity (blocks/tick), used for the
    // throw/jump when you let go.
    private Vec3[]  swingHistory = null;
    private int     swingIndex   = 0;
    private Vec3    swingAvg     = Vec3.ZERO;
    private boolean wasAnyGripping = false;

    // Smoothed grip velocity used by the low-pass filter that removes VR tracking
    // noise jitter. Reset to ZERO when not gripping so each new contact starts clean.
    private Vec3 smoothedGripVel = Vec3.ZERO;

    // True while WE have turned the player's gravity off (during a grip), so we
    // know to turn it back on when you let go / disable the mod.
    private boolean noGravSet = false;

    // Wall-stuck anti-freeze state.
    // prevWallPos: player world-pos last tick during a wall grip (null when not gripping).
    // wallStuckTicks: ticks spent NOT moving while wall-gripping.
    // dropCooldown: ticks remaining in the forced-drop phase after getting unstuck.
    private Vec3 prevWallPos    = null;
    private int  wallStuckTicks = 0;
    private int  dropCooldown   = 0;

    // Teleport detection: player's position last tick.
    // A jump of > 2 blocks in one tick is impossible from normal swinging —
    // treat it as a teleport and wipe all grip/physics state so the player
    // doesn't get welded to a surface at the new location.
    // prevTickVel = deltaMovement at the END of our previous tick — the movement we
    // EXPECTED the engine to apply. A teleport moves the player WITHOUT velocity, so
    // (actual distance moved) >> (expected velocity) is the reliable teleport signal —
    // it catches short teleports (< 2 blocks) and multi-tick dashes that a plain
    // absolute-distance threshold misses, while fast legitimate launches don't trip it
    // (their movement is fully explained by velocity).
    private Vec3 prevPlayerPos  = null;
    private Vec3 prevTickVel    = Vec3.ZERO;
    private static final double TELEPORT_SLACK = 0.75;

    // Ticks of forced inactivity after a teleport is detected. Vivecraft can take a
    // few ticks to re-sync its room origin to the new player position; if we re-grip
    // immediately we anchor to stale hand positions and recreate the stuck state.
    private int teleportCooldown = 0;
    private static final int TELEPORT_COOLDOWN_TICKS = 5;

    // Longer settle used after a Vivecraft TELEPORT-AIM ends: the teleport executes on
    // release and the room origin re-syncs over several ticks, so we stay inert a bit
    // longer here than for a detected position jump. ~0.6 s.
    private static final int TELEPORT_SETTLE_TICKS = 12;


    // True this tick while actively sliding DOWN a wall (low-stick or ice). Drives
    // the yellow hand-marker tint. Reset at the start of every tick.
    private boolean wallSliding = false;

    // True this tick whenever we're gripping (climbing OR sliding) — i.e. the same
    // condition under which we suppress fall damage. Read by VivemonkecraftClient
    // after tick() so it can tell a DEDICATED server to zero our fall distance too
    // (server-authoritative fall damage can't be cancelled from the client alone).
    private boolean grippingThisTick = false;

    // Saved step height so we can restore it when the mod turns off.
    private Double savedStepHeight = null;

    // -----------------------------------------------------------------------
    // MAIN TICK
    // -----------------------------------------------------------------------

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;
        if (!vr.isVRActive()) return;

        // ELYTRA / VEHICLE GUARD: gorilla locomotion must not fight vanilla movement
        // that the engine owns. While elytra-gliding it would inject hand velocity on
        // top of flight and rocket you off; while riding a horse/minecart/boat the
        // player isn't the thing moving. In both cases go fully inert (drop grips,
        // restore gravity) and let vanilla drive — resumes the moment you land / dismount.
        if (player.isFallFlying() || player.isPassenger()) {
            onGuiPause(client);
            prevTickVel = player.getDeltaMovement();
            return;
        }

        // LADDERS / VINES are handled NOT by going inert here, but by making climbable
        // blocks pass-through for the HANDS in isUngrabbable() below. That way the mod
        // never grabs the ladder (so it can't fight the climb), yet the rest of gorilla
        // locomotion stays fully live: floor-pushing still works when you're in front of
        // or standing at a ladder, and Vivecraft's own ladder climbing (its climbey-claw
        // hand grab, a separate system the mod never touches) keeps working on top.

        // TELEPORT-AIM GUARD: while the teleport button is held, Vivecraft freezes the
        // hand/room pose. If we kept processing we'd anchor to the stale hand and slide
        // in place. Go fully inert (drop grips, stop mining, restore gravity).
        //
        // The teleport actually EXECUTES as you RELEASE the button, and Vivecraft then
        // re-syncs its room origin over the next several ticks. If we resumed instantly
        // we'd re-grip onto a stale hand position and drag the body toward a now-wrong
        // anchor forever ("slide in place after releasing"). So arm the post-teleport
        // settle cooldown here — it keeps us inert (grips released) for a window AFTER
        // the aim ends, so we only re-grip once the position has landed.
        if (vr.isTeleportAiming()) {
            onGuiPause(client);
            teleportCooldown = Math.max(teleportCooldown, TELEPORT_SETTLE_TICKS);
            logState(client, "AIM", player);
            prevTickVel = player.getDeltaMovement();
            return;
        }

        wallSliding = false;      // recomputed each tick by the wall-slide branches
        grippingThisTick = false; // set true in the anyGrip blocks (both physics modes)

        // ---- TELEPORT DETECTION ----
        // Compare how far the player ACTUALLY moved since last tick against how far
        // their velocity SAID they should move. A teleport (QuestCraft teleport arc)
        // relocates the player without any corresponding deltaMovement, so the actual
        // distance hugely exceeds the expected one. The old absolute > 2.0-blocks-in-
        // one-tick check missed short teleports and teleports interpolated over
        // several ticks — this velocity-mismatch check catches both.
        // TELEPORT_SLACK (0.75) absorbs movement that bypasses deltaMovement
        // legitimately: roomscale walking (~0.07 b/t), joystick locomotion (~0.2 b/t),
        // and small server position corrections.
        Vec3 curPlayerPos = player.position();
        if (prevPlayerPos != null
                && curPlayerPos.distanceTo(prevPlayerPos)
                       > prevTickVel.length() + TELEPORT_SLACK) {
            if (VmcDebugLog.on()) VmcDebugLog.event("TP", String.format(
                    "position jump %.2f blocks (expected ≤%.2f) → teleport detected, settling",
                    curPlayerPos.distanceTo(prevPlayerPos), prevTickVel.length() + TELEPORT_SLACK));
            // Full reset. release() also clears floorGrip/prevOffset; we additionally
            // clear wasGripping so the post-teleport tick can't fire grab-end logic.
            mainHand.release();
            offHand.release();
            mainHand.wasGripping = false;
            offHand.wasGripping  = false;
            wasAnyGripping     = false;
            smoothedGripVel    = Vec3.ZERO;
            swingHistory       = null;
            swingAvg           = Vec3.ZERO;
            prevWallPos        = null;
            wallStuckTicks     = 0;
            wallSlideTicks     = 0;
            dropCooldown       = 0;

            // CRITICAL: if we were mid-grip when the teleport fired, gravity was off and
            // the player had leftover grip velocity. Without clearing both, the player
            // arrives at the new spot floating and sliding with no way to move — exactly
            // the "after teleport the hitboxes just keep sliding" bug. Re-enable gravity
            // and zero the residual momentum so normal control resumes immediately.
            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }
            player.setDeltaMovement(Vec3.ZERO);
            prevTickVel = Vec3.ZERO;
            teleportCooldown = TELEPORT_COOLDOWN_TICKS;
        }
        prevPlayerPos = curPlayerPos;

        // Post-teleport settle window: do nothing for a few ticks so we can't re-grip
        // stale VR hand positions while Vivecraft re-syncs its room origin.
        if (teleportCooldown > 0) {
            teleportCooldown--;
            // ROOT FIX for "teleport breaks physics": a Vivecraft teleport leaves the
            // room origin behind the player entity, so our hand/head world positions are
            // stale → grips anchor to the old spot and drag you (slide in place) or
            // pushes don't register. Force the origin to re-sync to the entity each
            // settle tick so that, once the cooldown ends, everything lines up again.
            vr.snapRoomOriginToPlayer(player);
            // NOTE: we deliberately DON'T touch velocity here. An earlier version zeroed
            // horizontal velocity (keeping vertical) to stop a post-teleport slide — but
            // that asymmetry wiped your sideways momentum while preserving upward motion,
            // so you could only launch "straight up" after a teleport. The room-origin
            // re-sync above already fixes the slide (it was a desync, not real velocity),
            // so we leave momentum intact and you keep your sideways speed.
            // Keep grips DROPPED through the whole settle so the first grip after it is
            // computed fresh from the re-synced hand positions (no stale anchor → no
            // slide-in-place). wasGripping cleared too so grab-end logic can't fire.
            mainHand.release();
            offHand.release();
            mainHand.wasGripping = false;
            offHand.wasGripping  = false;
            wasAnyGripping       = false;
            smoothedGripVel      = Vec3.ZERO;
            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }
            stopMining(client);
            prevMainOffsetMining = null;
            prevOffOffsetMining  = null;
            prevMainRaw = null;
            prevOffRaw  = null;
            HandMarkerRenderer.clearState();
        VrHandClamp.clear();
            logState(client, "SETTLE", player);
            prevTickVel = player.getDeltaMovement();
            return;
        }

        Vec3 rawMain = vr.getMainHandPos();
        Vec3 rawOff  = vr.getOffHandPos();
        Vec3 headPos = vr.getHeadPos();
        if (rawMain == null || rawOff == null || headPos == null) return;

        // Main-hand punch velocity for tool-touch mining: this tick's change in the
        // hand's offset from the head (so body/locomotion motion doesn't read as a
        // punch). Zero until we have a baseline, and after any teleport/desync reset.
        Vec3 curMainOffset = rawMain.subtract(headPos);
        Vec3 mainHandVel = (prevMainOffsetMining != null)
                ? curMainOffset.subtract(prevMainOffsetMining) : Vec3.ZERO;
        prevMainOffsetMining = curMainOffset;
        Vec3 curOffOffset = rawOff.subtract(headPos);
        Vec3 offHandVel = (prevOffOffsetMining != null)
                ? curOffOffset.subtract(prevOffOffsetMining) : Vec3.ZERO;
        prevOffOffsetMining = curOffOffset;

        // ---- VR-DESYNC GUARD (Vivecraft teleport) ----
        // Vivecraft's own teleport moves the PLAYER ENTITY but its room origin (which
        // our head/hand world positions are derived from) can stay at the OLD location
        // until Vivecraft re-syncs (e.g. on joystick input). While desynced, our hands
        // are still "touching" blocks back at the old spot, so grips stay alive and
        // keep writing velocity — the "after teleport the hitboxes keep sliding and I
        // can't move" bug. Detection: the reported head should sit roughly above the
        // player's body (horizontal offset < ~1 block in roomscale). If it's further
        // than this, the VR data is stale: release everything and do NOTHING this tick
        // so vanilla / Vivecraft movement works normally until the data re-converges.
        // (Ender-pearl/server teleports snap Vivecraft's room origin immediately, which
        // is why those never desynced.)
        // Horizontal: head should be within VR_DESYNC_DIST of the body.
        // Vertical: the head normally sits ~0.3–2.5 above the feet (crouching to
        // standing). Far outside that range means the head data is from a different
        // elevation — i.e. a mostly-VERTICAL teleport (up/down a cliff) left the room
        // origin behind. Checked separately because the horizontal test can't see it.
        double headDx = headPos.x - curPlayerPos.x;
        double headDy = headPos.y - curPlayerPos.y;
        double headDz = headPos.z - curPlayerPos.z;
        // FAST-MOVE EXEMPTION: when we're flinging the body fast under our own power,
        // Vivecraft's room origin lags the moving body, so the head legitimately drifts
        // far behind for a few ticks — that's NOT a teleport desync. A real teleport
        // desync happens with ~zero horizontal velocity (the body jumped without us
        // moving it). So skip the guard while horizontal self-speed is high, otherwise
        // big throws hitch as the guard falsely trips and goes inert mid-flight.
        double selfSpeedH = Math.sqrt(prevTickVel.x * prevTickVel.x + prevTickVel.z * prevTickVel.z);
        boolean fastSelfMove = selfSpeedH > 0.2;
        if (!fastSelfMove
                && (Math.sqrt(headDx * headDx + headDz * headDz) > VR_DESYNC_DIST
                || headDy < -0.5 || headDy > 3.5)) {
            if (VmcDebugLog.on()) VmcDebugLog.event("VR", String.format(
                    "desync guard fired (head off body: horiz=%.2f dy=%.2f) → inert this tick",
                    Math.sqrt(headDx * headDx + headDz * headDz), headDy));
            mainHand.release();
            offHand.release();
            mainHand.wasGripping = false;
            offHand.wasGripping  = false;
            wasAnyGripping  = false;
            smoothedGripVel = Vec3.ZERO;
            swingHistory    = null;
            swingAvg        = Vec3.ZERO;
            prevWallPos     = null;
            wallStuckTicks  = 0;
            wallSlideTicks  = 0;
            dropCooldown    = 0;
            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }
            stopMining(client);
            prevMainOffsetMining = null;
            prevOffOffsetMining  = null;
            prevMainRaw = null;
            prevOffRaw  = null;
            HandMarkerRenderer.clearState();
        VrHandClamp.clear();
            prevTickVel = player.getDeltaMovement();
            return;
        }

        // Snapshot each hand's "was gripping?" before this tick modifies it,
        // so we can detect FIRST CONTACT later.
        mainHand.tickStart();
        offHand.tickStart();

        // How fast the player is currently falling (positive = falling down).
        // Used for fall-sweep block detection and fall absorption on first grip.
        double fallSpeed = Math.max(0.0, -player.getDeltaMovement().y);

        // Two versions of each hand:
        //   touch* = REACH-extended + clamped, used to DETECT the grab (so you can
        //            reach the ground while standing) and to draw the marker.
        //   swing* = RAW hand clamped to arm length, used to measure your real arm
        //            motion 1:1 (so reach doesn't make movement twitchy).
        // The raycast hit also tells us WHICH FACE was grabbed: a TOP face is a
        // supporting surface (hand plants on it, you can stand/hold), a side face is
        // a wall. This face-based split is what keeps "standing on your hands"
        // independent of wallStickiness.
        //
        // TWO rays per hand, with different jobs:
        //   HEAD ray  — clamps the GRAB POINT. The head can never be inside a block,
        //               so this ray always finds an outer face and the hand hitbox
        //               can never sink inside a wall (anti-embedding).
        //   HAND ray  — decides the FACE for floor-vs-wall classification. The hand
        //               approaches the surface directly, so it reports the face the
        //               player actually grabbed. The head ray is WRONG for this job:
        //               looking down at the ground in front of you, it arrives at a
        //               shallow diagonal and often clips a block's SIDE face — which
        //               misclassified ground grabs as wall grips and made hands slide
        //               on the ground / fail to mantle 1-block steps.
        //   If the hand ray misses (hand already inside a block), fall back to the
        //   head ray's face — an embedded hand therefore still counts as a WALL grab,
        //   which keeps the old hover-inside-the-block exploit impossible.
        Vec3 targetMain = clampArm(extendReach(rawMain, headPos), headPos);
        Vec3 targetOff  = clampArm(extendReach(rawOff,  headPos), headPos);
        // Clamp-ray ORIGIN: the TORSO (midway between feet and head), NOT the head.
        // In 1-block-tall areas the head presses against the ceiling block — a
        // head-origin ray then starts inside that block and instantly "hits" it,
        // sticking the hands to the ceiling. The torso midpoint stays inside the
        // body, which is never inside blocks, so the clamp still prevents grabbing
        // through walls without the ceiling false-grab.
        Vec3 castOrigin = new Vec3(headPos.x, (headPos.y + player.getY()) * 0.5, headPos.z);
        BlockHitResult hitMain     = raycastGrab(client, player, castOrigin, targetMain);
        BlockHitResult hitOff      = raycastGrab(client, player, castOrigin, targetOff);
        BlockHitResult handHitMain = raycastGrab(client, player, rawMain, targetMain);
        BlockHitResult handHitOff  = raycastGrab(client, player, rawOff,  targetOff);
        Vec3 touchMain = (hitMain != null) ? hitMain.getLocation()
                             : (handHitMain != null) ? handHitMain.getLocation() : targetMain;
        Vec3 touchOff  = (hitOff  != null) ? hitOff.getLocation()
                             : (handHitOff  != null) ? handHitOff.getLocation()  : targetOff;
        boolean topFaceMain = (handHitMain != null)
                ? handHitMain.getDirection() == Direction.UP
                : hitMain != null && hitMain.getDirection() == Direction.UP;
        boolean topFaceOff  = (handHitOff != null)
                ? handHitOff.getDirection() == Direction.UP
                : hitOff != null && hitOff.getDirection() == Direction.UP;
        Vec3 swingMain = clampArm(rawMain, headPos);
        Vec3 swingOff  = clampArm(rawOff,  headPos);

        // ---- STEP ASSIST ----
        // Suppress raised step height when a barrier block is immediately adjacent —
        // barrier blocks are server admin boundaries that players shouldn't walk over.
        // Also suppress when touching ice (ice push-off path handles movement itself).
        boolean barrierAdjacent = hasBarrierInAABB(client.level,
                player.getBoundingBox().inflate(0.12, 0, 0.12));
        if (MovementConfig.stepAssist && !barrierAdjacent) {
            applyStepHeight(player, MovementConfig.stepHeight);
        } else {
            restoreStepHeight(player);
        }

        ensureSwingHistory();

        // ---- PHYSICS MODE DISPATCH ----
        int activeMode = (int) MovementConfig.locomotionMode;

        // ---- HOVERBOARD ----
        // The hand holding a board steers it instead of grabbing (Speed Swing mode
        // only; probe() returns null in the other modes, leaving both hands normal).
        InteractionHand boardHand = hoverboard.probe(client, player, vr, activeMode == MODE_SPEED_SWING);
        boolean boardMain = boardHand == InteractionHand.MAIN_HAND;
        boolean boardOff  = boardHand == InteractionHand.OFF_HAND;
        boolean riding    = hoverboard.isRiding();
        if (activeMode == MODE_GT_ANCHOR) {
            applyGtPhysics(client, player, touchMain, touchOff,
                           swingMain, swingOff, headPos,
                           topFaceMain, topFaceOff, fallSpeed);
        } else if (activeMode == MODE_CHARGE_JUMP) {
            applyChargePhysics(client, player, touchMain, touchOff,
                           swingMain, swingOff, headPos, fallSpeed);
        } else {

        // ---- PER-HAND SWING -> VELOCITY ----
        // Legacy swing → velocity per hand; everything after this (throw, gravity,
        // friction, wall/floor stickiness, jumping) is the legacy system.
        Vec3 mainVel = boardMain ? holdingBoard(mainHand)
                     : processHand(client, mainHand, touchMain, swingMain, headPos, fallSpeed, topFaceMain);
        Vec3 offVel  = boardOff  ? holdingBoard(offHand)
                     : processHand(client, offHand,  touchOff,  swingOff,  headPos, fallSpeed, topFaceOff);

        // SINGLE FLOOR GRIP (see the GT-mode note): keep only one floor hand — the one
        // CLOSEST to the floor (physically lower) — so a stray second floor touch can't
        // snag walking. Drop the higher hand + its vel; its model still rests on the
        // surface via handSurfaceResolve.
        if (MovementConfig.singleFloorGrip
                && mainHand.gripping && mainHand.floorGrip
                && offHand.gripping  && offHand.floorGrip) {
            if (swingMain.y <= swingOff.y) {
                offHand.release();  offVel  = Vec3.ZERO;   // main is closer to the floor
            } else {
                mainHand.release(); mainVel = Vec3.ZERO;   // off is closer to the floor
            }
        }

        // ---- FALL ABSORPTION: first floor contact while falling fast zeroes downward vel ----
        // When you extend your hand toward the ground during a fall, the first tick
        // your hand actually touches a block should stop your fall — otherwise you'd
        // bounce/clip through because the raw impact velocity is still huge.
        boolean newMainFloor = (!mainHand.wasGripping && mainHand.gripping && mainHand.floorGrip);
        boolean newOffFloor  = (!offHand.wasGripping  && offHand.gripping  && offHand.floorGrip);
        if ((newMainFloor || newOffFloor) && fallSpeed > 0.1) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, 0.0, v.z); // kill downward velocity on contact
        }

        // ---- PER-HAND ICE-WALL DETECTION ----
        // Ice is slippery on ALL faces. Probe ±0.06 around each grab point so we catch
        // the ice block regardless of which face the grab landed on. A hand counts as
        // being on an ICE WALL when it grips a non-floor (vertical) ice surface.
        // These multipliers are reused further down for the floor/ground ice handling,
        // so we compute them once here.
        double  mainGrabMult = mainHand.gripping ? iceNearMultiplier(client, touchMain) : 0.0;
        double  offGrabMult  = offHand.gripping  ? iceNearMultiplier(client, touchOff)  : 0.0;
        boolean mainIceWall  = mainHand.gripping && !mainHand.floorGrip && mainGrabMult > 0.0;
        boolean offIceWall   = offHand.gripping  && !offHand.floorGrip && offGrabMult  > 0.0;

        // ---- ICE WALL: DISABLE THE OTHER HAND ----
        // While one hand is on an ice wall you are committed to the slide / push-off:
        // the other hand's grab is switched off so you can't arrest the slide by grabbing
        // a second block. The main hand wins if both land on ice the same tick.
        // Ice walls are no longer "pushed off" via a one-tick release impulse (that
        // fought with the throw-on-release path and lost most of the gesture) Instead
        // an ice-wall grip now PERSISTS like a normal grip but with gravity left on and
        // no cling (see the wall-grip branch below) — so a sustained push builds real
        // momentum that the existing throw-on-release then launches you.
        if (mainIceWall && offHand.gripping) {
            offHand.release();
            offVel      = Vec3.ZERO;
            offGrabMult = 0.0;
            offIceWall  = false;
        } else if (offIceWall && mainHand.gripping) {
            mainHand.release();
            mainVel      = Vec3.ZERO;
            mainGrabMult = 0.0;
            mainIceWall  = false;
        }

        int gripCount = (mainHand.gripping ? 1 : 0) + (offHand.gripping ? 1 : 0);
        boolean anyGrip = gripCount > 0;

        // Two hands at once -> average (so two hands don't double your speed).
        Vec3 vel = (gripCount == 2) ? mainVel.add(offVel).scale(0.5) : mainVel.add(offVel);

        // Ignore micro-movements (controller jitter) so a resting hand truly rests.
        if (vel.length() < MovementConfig.minImpulse) vel = Vec3.ZERO;

        // Cap so a tracking glitch can't fling you.
        // Also honours the server's maxJumpSpeed limit if one is set.
        double maxSpd = ServerLimits.maxJumpSpeed(MovementConfig.maxJumpSpeed);
        if (vel.length() > maxSpd) {
            vel = vel.normalize().scale(maxSpd);
        }

        // Feed this tick's swing velocity into the rolling average (for the throw).
        // NOTE: we use the RAW vel here (before smoothing) so the throw reads the real
        // intended swing speed and not a noise-averaged-down version.
        storeSwing(anyGrip ? vel : Vec3.ZERO);

        // ---- GRIP SMOOTHING (low-pass filter on locomotion velocity) ----
        // VR controllers have ~1-2 mm of tracking noise even when held still.
        // Multiplied by pullStrength (default 2.0) this becomes tiny oscillating
        // velocity spikes every tick that are very visible at VR framerates (90fps).
        // An exponential moving average kills that high-frequency noise:
        // smoothedVel = lerp(smoothedVel, rawVel, 1 - gripSmoothing)
        // gripSmoothing = 0.0 → instant response (raw, old behaviour)
        // gripSmoothing = 0.5 → each tick is 50% new + 50% last tick (default, ~67%
        //                       attenuation of tick-rate noise) with ~5 tick rise-time
        // gripSmoothing = 0.8 → heavy smoothing, good for sickness-prone players but
        //                       slightly lazy response
        // When NOT gripping, reset to ZERO so the filter starts clean next contact.
        double smoothing = MovementConfig.gripSmoothing;
        if (anyGrip && smoothing > 0.0) {
            double alpha = 1.0 - Math.min(0.95, smoothing);
            smoothedGripVel = new Vec3(
                smoothedGripVel.x + (vel.x - smoothedGripVel.x) * alpha,
                smoothedGripVel.y + (vel.y - smoothedGripVel.y) * alpha,
                smoothedGripVel.z + (vel.z - smoothedGripVel.z) * alpha
            );
            vel = smoothedGripVel;
        } else {
            smoothedGripVel = Vec3.ZERO;
        }

        // ---- ICE SURFACE DETECTION ----
        // Feet: check the block directly under the player.
        // All ice variants share one speed-cap multiplier (×0.4 — see
        // iceBlockMultiplier), kept low for Quest performance.
        // 0.1 below minY rather than blockPosition().below() so it works correctly
        // at any sub-block Y position (e.g. standing partway off a slab edge).
        BlockPos   iceFeetPos   = BlockPos.containing(
                player.getX(), player.getBoundingBox().minY - 0.1, player.getZ());
        double     feetIceMult  = iceBlockMultiplier(client, iceFeetPos);
        boolean    onIce        = feetIceMult > 0.0;
        // iceSpeedCap > 0 signals "feet on ice"; 0 means normal surface.
        double     effMaxSpd    = ServerLimits.maxJumpSpeed(MovementConfig.maxJumpSpeed);
        double     iceSpeedCap  = onIce ? effMaxSpd * feetIceMult : 0.0;

        // ---- GRAB-POINT ICE (reuses the per-hand multipliers computed up top) ----
        double  grabIceMult     = Math.max(mainGrabMult, offGrabMult);
        boolean grabOnIce       = grabIceMult > 0.0;
        double  grabIceSpeedCap = grabOnIce ? effMaxSpd * grabIceMult : 0.0;

        // EXPERIMENTAL vanilla ice friction: keep factor of the icy floor/grab surface
        // (block friction × 0.91). >0 means "skate with vanilla physics this tick" — the
        // branches below then use this instead of the frictionless+capped ice handling.
        double  iceKeepVanilla  = vanillaIceKeep(client, iceFeetPos, touchMain, touchOff);
        boolean vanillaIce      = iceKeepVanilla > 0.0;

        // ---- APPLY ----
        // Whether the velocity was SET this tick (grip or throw) rather than carried
        // over through vanilla's drag — the hoverboard needs to know which.
        boolean velocityFresh = anyGrip;

        if (anyGrip) {
            // While gripping (climbing OR sliding) you're in control — never take fall
            // damage from the descent. Resetting each tick means that if you LET GO,
            // fall damage accumulates only from THAT point: slide all the way = no
            // damage, fail near the ground = only the small remaining drop hurts.
            player.resetFallDistance();
            grippingThisTick = true;
            suppressServerFall(client);

            // Is at least one gripping hand on a WALL (vertical surface) rather than the
            // floor? Wall grips use wallStickiness (vertical cling / slide-down); a grip
            // where every touching hand is on the floor uses floorStickiness (horizontal
            // slide). Mixed grips (one wall, one floor) are treated as a wall grip
            // because you're effectively climbing.
            boolean wallGrip = (mainHand.gripping && !mainHand.floorGrip)
                            || (offHand.gripping  && !offHand.floorGrip);

            // EXPERIMENTAL OPTION (iceFloorWallLogic): route ICE FLOOR grabs through
            // the ice-WALL branch instead of the floor branch — gravity stays on,
            // no anchor glue, pure push-off momentum, exactly like an ice wall.
            // One line by design so it's trivial to remove after testing.
            // ...but vanilla ice friction takes precedence: it WANTS ice floors handled as
        // skating floors (momentum-preserving), not pushed into the wall branch which
        // would replace your slide velocity with the swing and dead-stop you.
        if (MovementConfig.iceFloorWallLogic && grabOnIce && !vanillaIce) wallGrip = true;

            if (wallGrip) {

                // ---- WALL-STUCK ANTI-FREEZE ----
                // If the player hasn't moved for WALL_STUCK_MAX ticks (2.5 s) while
                // wall-gripping, briefly force wallStickiness to 0 so they drop free
                // and can find a new grip point instead of being welded to the wall.
                Vec3 curPos = player.position();
                if (prevWallPos != null && curPos.distanceTo(prevWallPos) < STUCK_THRESHOLD) {
                    wallStuckTicks++;
                } else {
                    wallStuckTicks = 0;
                }
                prevWallPos = curPos;
                if (wallStuckTicks >= WALL_STUCK_MAX) {
                    dropCooldown   = DROP_RELEASE_TICKS;
                    wallStuckTicks = 0;
                }
                if (dropCooldown > 0) dropCooldown--;

                // ---- WALL GRIP: vertical behaviour, controlled by wallStickiness ----
                //   stick >= 1.0 -> FULL CLING: hang in place, gravity OFF. Upward swing climbs.
                //   0 < stick < 1 -> SLIDE: gravity ON, you fall but the slide is damped
                //                    by stickiness (higher stick = slower slide). Upward
                //                    swing still climbs a normal wall.
                //   stick == 0    -> SLIDE at full gravity (no cling at all).
                //
                // Ice walls force stick to 0 (slippery on every face) AND ignore the
                // upward climb push, so ice can be pushed off / slid on but never climbed.
                // dropCooldown forces stick to 0 while the anti-freeze drop is active.
                //
                // KEY FIX (walls didn't slide at low stickiness): the SLIDE branch must
                // NOT overwrite the gravity-accumulated downward velocity every tick.
                // The original code honoured ANY upward swing (vel.y > 0) as a "climb" —
                // but VR controller jitter makes vel.y flicker just above 0 almost every
                // tick, so the fall kept being cancelled and the player stuck in place.
                // Fix: only a DELIBERATE upward swing (vel.y above CLIMB_THRESHOLD, well
                // above jitter amplitude) counts as a climb/push; anything below it is
                // treated as a still hand and the gravity slide proceeds. Ice walls
                // ignore the climb entirely (slippery — can be pushed off, never climbed).
                // Note: hands planted on TOP faces never reach this branch any more —
                // they're classified as floor grips (see processHand), which is what
                // makes standing on blocks work at any wallStickiness value.
                boolean iceWall = grabOnIce;
                double  stick   = (iceWall || dropCooldown > 0) ? 0.0 : MovementConfig.wallStickiness;

                if (stick >= 1.0) {
                    // Full cling — hang in place, gravity off. Upward swing climbs.
                    double finalY = (vel.y > 0) ? vel.y : 0.0;
                    player.setDeltaMovement(vel.x, finalY, vel.z);
                    if (!noGravSet) { player.setNoGravity(true); noGravSet = true; }
                } else if (iceWall) {
                    // Ice wall — free gravity slide, uncapped, never climbable.
                    if (noGravSet) { player.setNoGravity(false); noGravSet = false; }
                    double down = Math.min(0.0, player.getDeltaMovement().y);
                    // iceFloorWallLogic routes ICE FLOOR grabs through this branch —
                    // but on a floor you must be able to push UPWARD off the ice.
                    // Honour a deliberate upward swing (above jitter, ~0.03 b/t);
                    // sustained climbing still can't happen (each push needs a
                    // fresh re-grab and gravity is on the whole time).
                    double ny = (MovementConfig.iceFloorWallLogic && vel.y > 0.03)
                            ? vel.y : down;
                    // Vanilla ice: KEEP horizontal momentum (block friction × 0.91) and
                    // add the swing, so even a grab routed here skates instead of
                    // dead-stopping. Otherwise horizontal = the swing (vanilla-off feel).
                    Vec3   icw = player.getDeltaMovement();
                    double nxw = vanillaIce ? vel.x + icw.x * iceKeepVanilla : vel.x;
                    double nzw = vanillaIce ? vel.z + icw.z * iceKeepVanilla : vel.z;
                    player.setDeltaMovement(nxw, ny, nzw);
                    if (ny < 0 && !player.onGround()) wallSliding = true;
                } else {
                    // LOW-STICK WALL — the swing drives the body 1:1 with a downward
                    // slide superimposed on top:
                    //     y = swing Y - (1 - stick) * WALL_SLIDE_CAP * ramp
                    // The slide RAMPS UP over WALL_SLIDE_RAMP_TICKS of continuous wall
                    // grip instead of hitting at full rate instantly:
                    //   * quick pushes / 1-block mantles happen in the first few ticks,
                    //     while the slide is still near zero — so they keep the snappy
                    //     feel of the old build instead of being yanked down mid-push
                    //   * hanging still ramps to the full rate within ~½ s, so one-hand
                    //     hovering is still impossible (the anti-hover fix holds);
                    //     jiggling can't cancel it because the slide ignores hand motion
                    //   * releasing and re-grabbing resets the ramp — intentional, per
                    //     the bug note: a fresh (red→green) grab may stick again briefly
                    // While standing ON THE GROUND the slide never pushes downward —
                    // that only drilled the player's legs into the floor.
                    if (!noGravSet) { player.setNoGravity(true); noGravSet = true; }
                    wallSlideTicks++;
                    double ramp   = Math.min(1.0, wallSlideTicks / (double) WALL_SLIDE_RAMP_TICKS);
                    double slideY = -(1.0 - stick) * WALL_SLIDE_CAP * ramp;
                    double ny     = vel.y + slideY;
                    if (player.onGround() && ny < 0) ny = 0;
                    player.setDeltaMovement(vel.x, ny, vel.z);
                    if (ny < 0 && !player.onGround()) wallSliding = true;
                }

            } else {
                // Floor grip: not on a wall, so reset the wall-stuck timer and slide ramp.
                prevWallPos    = null;
                wallStuckTicks = 0;
                wallSlideTicks = 0;

                // ---- FLOOR GRIP: horizontal cling ----
                // Gravity stays OFF (planted hand holds you up).
                //
                // How much existing momentum is KEPT each tick (= 1 - stickiness):
                //   floorStickiness 1.0 → keep=0   → stop dead on contact (default)
                //   floorStickiness 0.5 → keep=0.5 → glide a bit after each push
                //   floorStickiness 0.0 → keep=1.0 → full momentum retained (ice-like)
                //
                // Ice OVERRIDES floorStickiness to keep=1.0 so hands don't act as
                // glue on a slippery surface. The speed cap switches to the ice cap
                // (maxJumpSpeed × 0.4) so ice stays controllable on Quest hardware.
                if (!noGravSet) { player.setNoGravity(true); noGravSet = true; }
                // Merge feet-ice and grab-ice: ice is slippery from any contact face.
                // VANILLA ICE: keep = block friction × 0.91 (a slow decay → real skating),
                // instead of the mod's keep=1.0 (never decays) on ice.
                double keep = vanillaIce ? iceKeepVanilla
                            : (onIce || grabOnIce) ? 1.0
                            : 1.0 - MovementConfig.floorStickiness;
                keep = Math.max(0.0, Math.min(1.0, keep));
                Vec3   cur      = player.getDeltaMovement();
                double nx       = vel.x + cur.x * keep;
                double nz       = vel.z + cur.z * keep;
                // Use the higher speed cap from either feet or grab. Vanilla ice removes
                // the artificial ice cap so momentum can build like real skating.
                double effectiveIceSpeedCap = Math.max(iceSpeedCap, grabIceSpeedCap);
                double speedCap = vanillaIce ? effMaxSpd
                                : (effectiveIceSpeedCap > 0.0) ? effectiveIceSpeedCap : effMaxSpd;
                double hl       = Math.sqrt(nx * nx + nz * nz);
                if (hl > speedCap) {
                    double f = speedCap / hl;
                    nx *= f; nz *= f;
                }
                player.setDeltaMovement(nx, vel.y, nz);
            }

            // WALL-CLIP GUARD: after all grip logic has set deltaMovement, sweep the
            // player's AABB along each velocity axis against block geometry. Any axis
            // that would enter a solid block this tick is zeroed, so the player stops
            // at the wall face instead of clipping through it.
            // expandTowards() gives a swept AABB covering the full movement path so
            // even thin (1-block) walls are caught at high speeds.
            Vec3 clampIn = player.getDeltaMovement();
            Vec3 clampOut = clampToBlocks(client, player, clampIn);
            if (clampOut != clampIn) player.setDeltaMovement(clampOut);

        } else {
            // Not gripping: gravity back on. Reset wall-stuck state and the slide
            // ramp so a fresh wall contact starts both timers from zero.
            prevWallPos    = null;
            wallStuckTicks = 0;
            wallSlideTicks = 0;
            if (dropCooldown > 0) dropCooldown--;
            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }

            // GRAVITY MULTIPLIER: counteract a fraction of vanilla gravity each tick.
            // Vanilla adds ~0.08 downward per tick. We cancel (1-g)*0.08 upward,
            // leaving only g*0.08 net gravity. We only do this in the air (onGround
            // handles stopping the fall naturally) and only when g < 1.
            double g = MovementConfig.gravityMultiplier;
            // (The hoverboard handles its own drag and gravity while riding.)
            if (g < 1.0 && !player.onGround() && !riding) {
                double counterGrav = 0.08 * (1.0 - g);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x, v.y + counterGrav, v.z);
            }

            // THROW / JUMP: the moment you let go after swinging fast, fling yourself.
            boolean threw = false;
            if (wasAnyGripping && swingAvg.length() > MovementConfig.velocityLimit) {
                Vec3 throwVel = swingAvg.scale(MovementConfig.jumpMultiplier);
                if (throwVel.length() > effMaxSpd) {
                    throwVel = throwVel.normalize().scale(effMaxSpd);
                }
                player.setDeltaMovement(throwVel);
                threw = true;
                velocityFresh = true;
            }

            // GROUND FRICTION: damp horizontal speed when standing on the ground.
            //
            // Ice state was already sampled above (shared with floor-grip path).
            //   Any ice          → zero friction, speed cap ×0.4 (capped below normal)
            //   Normal surface   → groundFriction × floorStickiness,
            //     floorStickiness 1.0 = full friction (stops fast)
            //     floorStickiness 0.0 = frictionless (same feel as ice)
            if (!threw && player.onGround() && !riding) {
                double effectiveFriction = vanillaIce
                        ? iceKeepVanilla   // vanilla ice: slow decay (≈0.89) → skating
                        : onIce
                        ? 1.0   // zero friction — no damping at all
                        : Math.max(0.0, Math.min(1.0,
                              MovementConfig.groundFriction * MovementConfig.floorStickiness));

                if (effectiveFriction < 1.0) {
                    Vec3 v = player.getDeltaMovement();
                    player.setDeltaMovement(v.x * effectiveFriction, v.y, v.z * effectiveFriction);
                }

                // Ice speed cap (horizontal only). Skipped under vanilla ice so momentum
                // can build like real skating.
                if (!vanillaIce && iceSpeedCap > 0.0) {
                    Vec3 v = player.getDeltaMovement();
                    double hs = Math.sqrt(v.x * v.x + v.z * v.z);
                    if (hs > iceSpeedCap) {
                        double sc = iceSpeedCap / hs;
                        player.setDeltaMovement(v.x * sc, v.y, v.z * sc);
                    }
                }
            }

            // AIR FRICTION COUNTER: partially cancel vanilla horizontal air drag while
            // airborne and not gripping. Vanilla multiplies horizontal velocity by ~0.91
            // per tick. We compensate by multiplying by 0.91^(af-1), so:
            //   af=1.0  → compensate=0.91^0 =1.0    (no change — full vanilla drag)
            //   af=0.5  → compensate=0.91^(-0.5)≈1.048  (partial cancel — longer throws)
            //   af=0.0  → compensate=0.91^(-1) ≈1.099  (full cancel — throws carry forever)
            double af = MovementConfig.airFriction;
            if (af < 1.0 && !player.onGround() && !riding) {
                double compensate = Math.pow(0.91, af - 1.0);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x * compensate, v.y, v.z * compensate);
            }

            // LAUNCH STEP BOOST: Minecraft's step-height attribute only works when the
            // player is on the ground. When airborne and launched toward a block side, the
            // normal collision resolution kills horizontal momentum instead of stepping up.
            // This detects that situation each tick and adds enough upward velocity to
            // ride over the block cleanly, preserving horizontal momentum.
            // Skip the launch step boost on ice (the push-off path handles movement)
            // and skip it entirely if the blocking a block ahead is a barrier.
            if (MovementConfig.stepAssist && !player.onGround()
                    && !onIce && !grabOnIce && !riding) {
                applyLaunchStepBoost(client, player);
            }
        }
        wasAnyGripping = anyGrip;

        // ---- HOVERBOARD RIDE ----
        // Layered on top of any free-hand push. Riding counts as "gripping" for fall
        // damage, so the dedicated-server keepalive covers it too.
        if (boardHand != null && hoverboard.apply(player, velocityFresh)) {
            grippingThisTick = true;
            suppressServerFall(client);
        }

        } // end of speed-based physics (MODE_SPEED_SWING)

        // ---- HAND MARKERS ----
        // Push this tick's hand positions to HandMarkerRenderer so it can draw them
        // every render frame (not just every game tick — smoother in VR).

        // Shoulder joints: anchored to the player model's arm sockets.
        //
        // In Minecraft, right direction = (cos(yaw), 0, sin(yaw)).
        //   yaw=0   → facing South, right = East  (+X) ✓
        //   yaw=90  → facing West,  right = South (+Z) ✓
        //
        // Vivecraft convention: c0 = right/main hand, c1 = left/offhand.
        // sw = lateral shoulder offset from spine (blocks); sd = drop below head centre.
        // Vanilla body is 0.6 wide → half-width = 0.30, head-to-shoulder ≈ 0.24.
        float  yawRad = (float) Math.toRadians(player.getYRot());
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double sw = 0.30;   // vanilla body half-width
        double sd = 0.24;   // vanilla head-to-shoulder drop

        // In QuestCraft the "main" controller is the LEFT hand, so its shoulder
        // socket is on the LEFT side (negative right direction) and vice versa.
        HandMarkerRenderer.shoulderMain = new Vec3(
            headPos.x - rightX * sw,
            headPos.y - sd,
            headPos.z - rightZ * sw
        );
        HandMarkerRenderer.shoulderOff  = new Vec3(
            headPos.x + rightX * sw,
            headPos.y - sd,
            headPos.z + rightZ * sw
        );

        // (In GT anchor mode a gripping hand displays at its planted surface point.)
        HandMarkerRenderer.grabMain     = (mainHand.anchorDisplay != null) ? mainHand.anchorDisplay : touchMain;
        HandMarkerRenderer.grippingMain = mainHand.gripping;
        HandMarkerRenderer.sliding      = wallSliding;   // yellow markers while sliding
        HandMarkerRenderer.grabOff      = (offHand.anchorDisplay != null) ? offHand.anchorDisplay : touchOff;

        // Publish the same surface points to the VR hand-model clamp (render-frame
        // mixin) so Vivecraft's hand MODELS plant on the block face while gripping
        // instead of sinking inside — null when free or when the feature is off.
        if (MovementConfig.clampHandModels) {
            // While GRIPPING: plant on the grab surface point (existing behaviour).
            // While FREE: optionally resolve the hand out of any block it's inside /
            // swept through this tick, so the non-pushing hand's model rests ON the
            // ground instead of sinking into it (walking, falling, fast swings).
            VrHandClamp.clampMain = mainHand.gripping ? HandMarkerRenderer.grabMain
                    : (MovementConfig.handSurfaceCollision
                            ? handSurfaceResolve(client, rawMain, prevMainRaw) : null);
            VrHandClamp.clampOff  = offHand.gripping  ? HandMarkerRenderer.grabOff
                    : (MovementConfig.handSurfaceCollision
                            ? handSurfaceResolve(client, rawOff, prevOffRaw) : null);
        } else {
            VrHandClamp.clear();
        }
        HandMarkerRenderer.grippingOff  = offHand.gripping;

        // Emit the hand-marker particles for this tick (no-op unless showHandMarkers).
        HandMarkerRenderer.emit(client);

        // ---- TOOL-TOUCH MINING ----
        // Break the block the MAIN hand touches — but only while holding the tool
        // MEANT for it (getDestroySpeed > 1). Bare hands / wrong tools just grab, so
        // climbing stays safe; equip the matching tool and contact mines the block.
        // Uses the hand-ray block (the face you actually reached) and falls back to
        // the head-ray block. Runs for BOTH physics modes (gripping is finalised by
        // whichever ran above).
        processToolTouchMining(client, player,
                (handHitMain != null) ? handHitMain : hitMain, mainHand.gripping, mainHandVel,
                (handHitOff  != null) ? handHitOff  : hitOff,  offHand.gripping,  offHandVel);

        // Grabbing a MAGMA block (any face, including the sides) hurts you, same as
        // standing on one.
        processMagmaTouch(client, player,
                (handHitMain != null) ? handHitMain : hitMain, mainHand.gripping,
                (handHitOff  != null) ? handHitOff  : hitOff,  offHand.gripping);

        // Comprehensive per-tick trace: grip transitions + one line of full state.
        logGripChanges();
        logState(client, "TICK", player);

        // Record the velocity we are handing to the engine this tick — next tick's
        // teleport detection compares actual movement against this expectation.
        prevTickVel = player.getDeltaMovement();

        // Baseline for next tick's hand-surface sweep (raw, un-clamped positions).
        prevMainRaw = rawMain;
        prevOffRaw  = rawOff;
    }

    // -----------------------------------------------------------------------
    // TOOL-TOUCH MINING
    // -----------------------------------------------------------------------
    //
    // Drives Minecraft's STANDARD block-break calls from the hand-touched block, so
    // mining progress, tool speed, hardness, drops and creative instant-break all work
    // exactly as vanilla — and the SERVER registers the damage on that block (the
    // calls send the normal action packets, validated only by reach, which a hand at
    // arm's length always satisfies). Works on dedicated servers with no companion mod.
    //
    // The "is this the tool meant for the block?" test is getDestroySpeed > 1.0: an
    // empty hand and non-matching items return 1.0 (so they only grab → locomotion is
    // untouched), while the appropriate tool returns higher (so it mines).
    //
    // VELOCITY GATING (MovementConfig.punchMining): progress only happens on ticks
    // where the hand is moving INTO the block face faster than PUNCH_SPEED — i.e. you
    // PUNCH it. Gentle contact (grabbing to move) never mines, even with the tool in
    // hand. Between punches we hold progress (don't stop), so repeated jabs on a hard
    // block accumulate; we only cancel when the hand leaves the block or loses the tool.
    private BlockPos miningPos = null;

    // Ticks the breaking state is HELD after the last punch. A punch winds up by
    // pulling the hand OFF the block, so contact (and thus the break) would otherwise
    // reset between jabs — this keeps the accumulated progress alive long enough for
    // repeated punches to add up and finish the block.
    private int miningGrace = 0;
    private static final int MINING_GRACE = 30;   // ~1.5 s between jabs before it lapses

    // Each hand's offset (hand − head) last tick, for measuring punch speed RELATIVE
    // to the head — so free-move/locomotion (which moves the hand in world space
    // without an arm thrust) never counts as a punch. Null = no baseline (post-reset).
    private Vec3 prevMainOffsetMining = null;
    private Vec3 prevOffOffsetMining  = null;

    // Previous-tick RAW hand positions for handSurfaceResolve's last->current sweep, so
    // a fast swing/fall that skips a hand through a block in one tick still resolves to
    // the entry face. Null = no baseline (first tick / post-reset) → sweep disabled that
    // tick (push-out still runs).
    private Vec3 prevMainRaw = null;
    private Vec3 prevOffRaw  = null;

    // EITHER hand can mine (both deal block damage). We can only drive ONE vanilla
    // destroy at a time, so each tick we pick the punching hand's block — preferring
    // the one we're already breaking for continuity. Both the tool gate and the actual
    // break use the MAIN-hand item (that's what vanilla mines with), so progress and
    // drops stay consistent no matter which hand swings.
    private void processToolTouchMining(Minecraft client, LocalPlayer player,
                                        BlockHitResult hitMain, boolean mainTouching, Vec3 mainVel,
                                        BlockHitResult hitOff,  boolean offTouching,  Vec3 offVel) {
        MultiPlayerGameMode gm = client.gameMode;
        // CREATIVE: punch mining is OFF. In creative every block instant-breaks, so any
        // hand that brushes a block while you climb or push would destroy the world out
        // from under you — making it very hard to move. Use normal creative controls to
        // build/break instead. instabuild = the creative "break instantly" ability.
        if (gm == null || client.level == null || !MovementConfig.punchMining
                || player.getAbilities().instabuild) {
            stopMining(client);
            return;
        }

        ItemStack tool = player.getMainHandItem();
        BlockHitResult mainPunch = punchTarget(client, hitMain, mainTouching, mainVel, tool);
        BlockHitResult offPunch  = punchTarget(client, hitOff,  offTouching,  offVel,  tool);

        // Continuity first (keep advancing the block already in progress), else either.
        BlockHitResult target;
        if (mainPunch != null && mainPunch.getBlockPos().equals(miningPos))      target = mainPunch;
        else if (offPunch != null && offPunch.getBlockPos().equals(miningPos))   target = offPunch;
        else                                                                     target = (mainPunch != null) ? mainPunch : offPunch;

        if (target != null) {
            BlockPos pos = target.getBlockPos();
            Direction face = target.getDirection();
            if (pos.equals(miningPos)) {
                gm.continueDestroyBlock(pos, face);
            } else {
                if (miningPos != null) gm.stopDestroyBlock();
                gm.startDestroyBlock(pos, face);  // instant-breaks soft blocks
                miningPos = pos;
                if (VmcDebugLog.on()) VmcDebugLog.event("MINE",
                        "start " + pos + " " + client.level.getBlockState(pos).getBlock());
            }
            VmcCompat.swingMainHand(player);
            miningGrace = MINING_GRACE;            // refresh the hold window
            if (client.level.getBlockState(pos).isAir()) miningPos = null;
            return;
        }

        // No punch this tick (winding up, or hands momentarily off the block): HOLD the
        // accumulated progress for the grace window instead of cancelling, so the next
        // jab continues where it left off. Only when the window lapses do we let go.
        if (miningPos != null && --miningGrace <= 0) {
            stopMining(client);
        }
    }

    // Returns the block this hand is PUNCHING (touching a mineable block, with the tool
    // gate satisfied, moving into the face faster than the threshold), or null.
    private BlockHitResult punchTarget(Minecraft client, BlockHitResult hit,
                                       boolean touching, Vec3 handVel, ItemStack tool) {
        if (!touching || hit == null) return null;
        BlockState state = client.level.getBlockState(hit.getBlockPos());
        if (state.isAir()) return null;
        boolean toolOk = MovementConfig.punchMiningNoTool || tool.getDestroySpeed(state) > 1.0f;
        if (!toolOk) return null;
        Direction face = hit.getDirection();
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        double inward = -handVel.dot(n);
        return (inward > MovementConfig.punchMiningThreshold) ? hit : null;
    }

    // -----------------------------------------------------------------------
    // MAGMA TOUCH DAMAGE
    // -----------------------------------------------------------------------
    //
    // Vanilla only hurts you for STANDING on a magma block; gripping its sides with a
    // VR hand wouldn't. When enabled, a hand touching a magma block (any face) applies
    // the same hot-floor damage. Like fall damage it's server-authoritative, so in
    // singleplayer/LAN we hurt the integrated server's player here; on a dedicated
    // server VivemonkecraftClient forwards isTouchingMagma() to the companion mod.
    // Fire resistance negates it automatically (hot-floor is a fire damage type), and
    // vanilla hurt invulnerability frames throttle the cadence to match standing on it.
    private boolean touchingMagmaThisTick = false;
    private boolean loggedMagma = false;   // edge state for the MAGMA debug event

    private void processMagmaTouch(Minecraft client, LocalPlayer player,
                                   BlockHitResult hitMain, boolean mainGrip,
                                   BlockHitResult hitOff,  boolean offGrip) {
        touchingMagmaThisTick = false;
        if (!MovementConfig.magmaTouchDamage || client.level == null) return;

        boolean magma = (mainGrip && isMagma(client, hitMain))
                     || (offGrip  && isMagma(client, hitOff));
        if (!magma) {
            if (loggedMagma) { VmcDebugLog.event("MAGMA", "off magma"); loggedMagma = false; }
            return;
        }
        touchingMagmaThisTick = true;
        if (VmcDebugLog.on() && !loggedMagma) {
            VmcDebugLog.event("MAGMA", "hand on magma → hot-floor damage");
            loggedMagma = true;
        }

        if (client.hasSingleplayerServer()) {
            var server = client.getSingleplayerServer();
            if (server == null) return;
            java.util.UUID id = player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(id);
                if (sp != null) sp.hurt(sp.damageSources().hotFloor(), 1.0f);
            });
        }
        // Dedicated server: the C2S forward happens in VivemonkecraftClient.
    }

    private boolean isMagma(Minecraft client, BlockHitResult hit) {
        return hit != null && client.level.getBlockState(hit.getBlockPos()).is(Blocks.MAGMA_BLOCK);
    }

    // True this tick if a gripping hand is on a magma block — read by
    // VivemonkecraftClient to forward hot-floor damage on a dedicated server.
    public boolean isTouchingMagma() {
        return touchingMagmaThisTick;
    }

    // Cancels any in-progress hand-mine (hand left the block, lost the tool, mod off,
    // or a teleport/desync reset). Safe to call when nothing is mining.
    private void stopMining(Minecraft client) {
        if (miningPos != null) {
            if (client != null && client.gameMode != null) client.gameMode.stopDestroyBlock();
            if (VmcDebugLog.on()) VmcDebugLog.event("MINE", "stop " + miningPos);
            miningPos = null;
        }
        miningGrace = 0;
    }

    // Called when the mod is toggled off: drop grips, restore attributes, reset tracking.
    public void onDisable(Minecraft client) {
        stopMining(client);
        mainHand.release();
        offHand.release();
        swingHistory = null;
        swingAvg = Vec3.ZERO;
        wasAnyGripping = false;
        HandMarkerRenderer.clearState();
        VrHandClamp.clear();
        if (client != null && client.player != null) {
            restoreStepHeight(client.player);
            // Make sure we don't leave the player floating with gravity off.
            if (noGravSet) { client.player.setNoGravity(false); noGravSet = false; }
        }
        noGravSet = false;
    }

    // Called every tick a GUI screen is open (inventory, chat, settings, ...):
    // gorilla locomotion must not move the player while they're in a menu.
    // Drops all grips and restores gravity — same reset as the teleport guard —
    // and primes the trackers so closing the GUI resumes cleanly without
    // tripping the teleport detector on the accumulated position gap.
    public void onGuiPause(Minecraft client) {
        stopMining(client);
        prevMainOffsetMining = null;
        prevOffOffsetMining  = null;
        prevMainRaw = null;
        prevOffRaw  = null;
        mainHand.release();
        offHand.release();
        mainHand.wasGripping = false;
        offHand.wasGripping  = false;
        wasAnyGripping  = false;
        smoothedGripVel = Vec3.ZERO;
        swingHistory    = null;
        swingAvg        = Vec3.ZERO;
        prevWallPos     = null;
        wallStuckTicks  = 0;
        wallSlideTicks  = 0;
        dropCooldown    = 0;
        HandMarkerRenderer.clearState();
        VrHandClamp.clear();
        if (client.player != null) {
            if (noGravSet) { client.player.setNoGravity(false); noGravSet = false; }
            prevTickVel = client.player.getDeltaMovement();
        }
        prevPlayerPos = null;   // skip one teleport check when the GUI closes
    }

    // =========================================================================
    // GT ANCHOR PHYSICS  (MovementConfig.gtPhysics = true)
    // =========================================================================
    //
    // Faithful port of the official GorillaLocomotion Player.cs algorithm:
    //   * On contact a hand ANCHORS at the REAL hand's world position. Each tick
    //     the body is dragged by (anchor − currentRealHand) × dragGain so the
    //     hand stays planted — position-based 1:1.
    //   * gtPushStrength: the anchor RECEDES by (X−1)× the head-relative hand
    //     swing, so body travel = hand swing × X (X=1 is authentic GT).
    //   * Two anchored hands average their drags. Ice lets the anchor chase the
    //     hand (gtIceSlip) — push off, never hold. A hand straying further than
    //     gtUnstickDistance (physically, head-relative) lets go.
    //   * THE THROW: commanded velocity is averaged; on release the player is
    //     flung at avg × jumpMultiplier capped at maxJumpSpeed.

    private void applyGtPhysics(Minecraft client, LocalPlayer player,
                                 Vec3 touchMain, Vec3 touchOff,
                                 Vec3 realMain,  Vec3 realOff, Vec3 headPos,
                                 boolean topFaceMain, boolean topFaceOff,
                                 double fallSpeed) {

        double effMaxSpd = SettingCaps.maxJumpSpeed();

        Vec3 dragMain = gtProcessHand(client, mainHand, touchMain, realMain, headPos, topFaceMain, fallSpeed);
        Vec3 dragOff  = gtProcessHand(client, offHand,  touchOff,  realOff,  headPos, topFaceOff,  fallSpeed);

        // SINGLE FLOOR GRIP: never let BOTH hands hold the floor at once. The hand
        // CLOSEST to the floor (physically lower) stays as the one that pushes you up;
        // the other floor grip is dropped so two anchors can't fight, double-push, or
        // snag the walking gait. The dropped hand's MODEL still rests on the surface via
        // handSurfaceResolve, so it visually "stays on the ground". Walls/ceilings are
        // untouched, so two-handed climbing still works.
        if (MovementConfig.singleFloorGrip
                && mainHand.gripping && mainHand.floorGrip
                && offHand.gripping  && offHand.floorGrip) {
            if (realMain.y <= realOff.y) {
                offHand.release();  dragOff  = Vec3.ZERO;   // main is closer to the floor
            } else {
                mainHand.release(); dragMain = Vec3.ZERO;   // off is closer to the floor
            }
        }

        int gripCount   = (mainHand.gripping ? 1 : 0) + (offHand.gripping ? 1 : 0);
        boolean anyGrip = gripCount > 0;

        // EXPERIMENTAL vanilla ice friction — detect an icy floor/grab surface once for
        // BOTH the gripping (momentum-preserving skate) and free-slide paths below.
        BlockPos feetPos        = BlockPos.containing(
                player.getX(), player.getBoundingBox().minY - 0.1, player.getZ());
        double   iceKeepVanilla = vanillaIceKeep(client, feetPos, touchMain, touchOff);
        boolean  vanillaIce     = iceKeepVanilla > 0.0;

        // Both hands anchored → average so two hands don't double the drag.
        Vec3 move = (gripCount == 2) ? dragMain.add(dragOff).scale(0.5)
                                     : dragMain.add(dragOff);

        // Jitter floor and tracking-glitch cap (same guards as speed mode).
        if (move.length() < MovementConfig.minImpulse) move = Vec3.ZERO;
        if (move.length() > effMaxSpd) move = move.normalize().scale(effMaxSpd);

        // PROPORTIONAL SERVO GAIN — deliberately NOT an EMA filter (an EMA keeps
        // memory of old corrections and re-applies them after the hand reached its
        // anchor = the suck-in/rebound oscillation). Must stay below 1.0: Vivecraft
        // reports hand positions ~1 tick late, so full-strength corrections resonate.
        Vec3 vel = move.scale(Math.max(0.05, Math.min(0.95, MovementConfig.gtDragGain)));

        // Feed the throw average with the velocity we actually command.
        storeSwing(anyGrip ? vel : Vec3.ZERO);

        if (anyGrip) {
            // Gripping = in control, no fall damage from the descent (see speed-mode note).
            player.resetFallDistance();
            grippingThisTick = true;
            suppressServerFall(client);
            // Gripping: gravity off (the anchor holds you), body dragged to anchor.
            if (!noGravSet) { player.setNoGravity(true); noGravSet = true; }

            // Normally the anchor servo REPLACES velocity with the drag, so gripping an
            // icy floor kills your glide dead. With vanilla ice on, instead KEEP the
            // incoming horizontal momentum (block friction × 0.91) and ADD the servo
            // push on top — so you skate/push off the ice instead of stopping.
            Vec3 commanded = vel;
            if (vanillaIce) {
                Vec3 cur = player.getDeltaMovement();
                commanded = new Vec3(vel.x + cur.x * iceKeepVanilla, vel.y, vel.z + cur.z * iceKeepVanilla);
            }

            // Wall-clip guard — zero any axis that would enter solid geometry.
            player.setDeltaMovement(clampToBlocks(client, player, commanded));

        } else {
            smoothedGripVel = Vec3.ZERO;
            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }

            // Gravity multiplier (identical to speed mode).
            double g = SettingCaps.gravityMultiplier();
            if (g < 1.0 && !player.onGround()) {
                double counterGrav = 0.08 * (1.0 - g);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x, v.y + counterGrav, v.z);
            }

            // THE THROW — Player.cs: velocity = jumpMultiplier × avg, capped.
            boolean threw = false;
            if (wasAnyGripping && swingAvg.length() > MovementConfig.velocityLimit) {
                Vec3 throwVel = swingAvg.scale(SettingCaps.jumpMultiplier());
                if (throwVel.length() > effMaxSpd) {
                    throwVel = throwVel.normalize().scale(effMaxSpd);
                }
                player.setDeltaMovement(throwVel);
                threw = true;
            }

            // Ground friction (ice = frictionless, capped at the ice speed cap).
            // feetPos / iceKeepVanilla / vanillaIce were computed up top (shared).
            double   feetIce = iceBlockMultiplier(client, feetPos);
            boolean  onIce   = feetIce > 0.0;
            if (!threw && player.onGround()) {
                double effectiveFriction = vanillaIce
                        ? iceKeepVanilla
                        : onIce
                        ? 1.0
                        : Math.max(0.0, Math.min(1.0,
                              MovementConfig.groundFriction * MovementConfig.floorStickiness));
                if (effectiveFriction < 1.0) {
                    Vec3 v = player.getDeltaMovement();
                    player.setDeltaMovement(v.x * effectiveFriction, v.y, v.z * effectiveFriction);
                }
                if (onIce && !vanillaIce) {
                    double iceCap = effMaxSpd * feetIce;
                    Vec3 v  = player.getDeltaMovement();
                    double hs = Math.sqrt(v.x * v.x + v.z * v.z);
                    if (hs > iceCap) {
                        double sc = iceCap / hs;
                        player.setDeltaMovement(v.x * sc, v.y, v.z * sc);
                    }
                }
            }

            // Air friction compensation (identical to speed mode).
            double af = SettingCaps.airFriction();
            if (af < 1.0 && !player.onGround()) {
                double compensate = Math.pow(0.91, af - 1.0);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x * compensate, v.y, v.z * compensate);
            }

            // Step assist while airborne.
            if (MovementConfig.stepAssist && !player.onGround() && !onIce) {
                applyLaunchStepBoost(client, player);
            }
        }
        wasAnyGripping = anyGrip;
    }

    // ---- CHARGE-JUMP PHYSICS (MODE_CHARGE_JUMP) ----
    private void applyChargePhysics(Minecraft client, LocalPlayer player,
                                    Vec3 touchMain, Vec3 touchOff,
                                    Vec3 realMain,  Vec3 realOff, Vec3 headPos,
                                    double fallSpeed) {

        double effMaxSpd = SettingCaps.maxJumpSpeed();

        boolean fireMain = chargeProcessHand(client, mainHand, touchMain, fallSpeed);
        boolean fireOff  = chargeProcessHand(client, offHand,  touchOff,  fallSpeed);

        HandState firing = null;
        if (fireMain && fireOff) firing = (mainHand.latchTicks >= offHand.latchTicks) ? mainHand : offHand;
        else if (fireMain)       firing = mainHand;
        else if (fireOff)        firing = offHand;

        if (firing != null && firing.anchorDisplay != null) {
            double charge = Math.min(1.0, firing.latchTicks / (double) CHARGE_FULL_TICKS);
            double power  = MovementConfig.chargeMinLaunch
                          + charge * (MovementConfig.chargeMaxLaunch - MovementConfig.chargeMinLaunch);
            power = Math.min(power, effMaxSpd);

            Vec3 dir = headPos.subtract(firing.anchorDisplay);
            if (dir.lengthSqr() < 0.01) dir = new Vec3(0, 1, 0);
            Vec3 shot = dir.normalize().scale(power);

            if (noGravSet) { player.setNoGravity(false); noGravSet = false; }
            player.setDeltaMovement(clampToBlocks(client, player, shot));
            mainHand.release();
            offHand.release();
            wasAnyGripping = false;
            return;
        }

        boolean anyLatch = mainHand.gripping || offHand.gripping;

        if (anyLatch) {
            player.resetFallDistance();
            grippingThisTick = true;
            suppressServerFall(client);
            if (!noGravSet) { player.setNoGravity(true); noGravSet = true; }
            player.setDeltaMovement(Vec3.ZERO);
            wasAnyGripping = true;
            return;
        }

        if (noGravSet) { player.setNoGravity(false); noGravSet = false; }

        double g = SettingCaps.gravityMultiplier();
        if (g < 1.0 && !player.onGround()) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, v.y + 0.08 * (1.0 - g), v.z);
        }
        if (player.onGround()) {
            double f = Math.max(0.0, Math.min(1.0,
                    MovementConfig.groundFriction * MovementConfig.floorStickiness));
            if (f < 1.0) {
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x * f, v.y, v.z * f);
            }
        }
        double af = SettingCaps.airFriction();
        if (af < 1.0 && !player.onGround()) {
            double comp = Math.pow(0.91, af - 1.0);
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x * comp, v.y, v.z * comp);
        }
        if (MovementConfig.stepAssist && !player.onGround()) applyLaunchStepBoost(client, player);

        wasAnyGripping = false;
    }

    private boolean chargeProcessHand(Minecraft client, HandState state,
                                      Vec3 touchHand, double fallSpeed) {
        boolean touching = isTouchingBlockFallSweep(client, touchHand, fallSpeed);

        if (!state.gripping) {
            if (touching) {
                state.gripping      = true;
                state.anchorDisplay = touchHand;
                state.latchTicks    = 0;
            }
            return false;
        }

        if (touching) {
            state.anchorDisplay = touchHand;
            state.latchTicks++;
            return false;
        }

        if (state.latchTicks >= CHARGE_MIN_FIRE_TICKS) {
            return true;
        }
        state.release();
        return false;
    }

    // GT per-hand processing: anchor management + drag computation.
    //   touchHand — surface-clamped, reach-extended point: detects the grab and
    //               places the visual marker (never used for the drag).
    //   realHand  — the real controller position, arm-clamped only: the servo
    //               measures drag = anchor − realHand, so pressing INTO a surface
    //               produces real penetration depth and therefore real push-off.
    private Vec3 gtProcessHand(Minecraft client, HandState state,
                                Vec3 touchHand, Vec3 realHand, Vec3 headPos,
                                boolean topFace, double fallSpeed) {

        boolean touching = isTouchingBlockFallSweep(client, touchHand, fallSpeed);
        if (!touching) {
            state.release();
            return Vec3.ZERO;
        }

        if (!state.gripping) {
            // First contact: plant the anchor at the REAL hand, no movement yet.
            // (The zero drag this tick stops a fall dead on hand-plant, matching
            // GT's velocity-zero on touch.)
            state.gripping      = true;
            state.floorGrip     = topFace;
            state.anchor        = realHand;
            state.anchorDisplay = touchHand;
            state.prevOffset    = realHand.subtract(headPos);
            state.gripOffset    = state.prevOffset;
            return Vec3.ZERO;
        }

        // PUSH STRENGTH amplification: move the anchor AWAY from the hand by
        // (X − 1) of this tick's head-relative hand swing, so the body ends up
        // travelling X× the swing. X = 1.0 leaves the anchor fixed = authentic GT.
        Vec3 offset = realHand.subtract(headPos);
        // Head-relative hand motion this tick — the swing you ACTUALLY made with your
        // arm. It stays ~0 when the hand is held still even as the body moves (both the
        // hand and the head ride the same play-space), which is exactly how we tell an
        // intentional pull from a body-displacement artifact below.
        Vec3 handSwing = offset.subtract(state.prevOffset);
        double push = SettingCaps.gtPushStrength();
        if (push != 1.0) {
            state.anchor = state.anchor.subtract(handSwing.scale(push - 1.0));
        }
        state.prevOffset = offset;

        // Drag the body so the REAL hand returns to its anchor. A still hand sits
        // exactly at its anchor → zero drag → no movement (no phantom suction).
        Vec3 drag = state.anchor.subtract(realHand);

        // GROUNDED GRIP RELEASE — the "jump straight up, land, then you're wedged until
        // you pull your hand back" fix. When you rise with your hand held in place the
        // anchor stays at the floor point, so once you're back on the ground it produces
        // a big drag that presses you DOWN into the floor / holds you put — but your arm
        // never actually swung (handSwing ≈ 0), so that drag is a body-displacement
        // artifact, not a pull. While standing on solid ground with a floor grip and a
        // still hand, keep only an UPWARD push (so pressing down to hop still works) and
        // drop the downward/horizontal hold, so you're free to walk without retracting.
        // An intentional pull (handSwing above the jitter floor) is untouched, so normal
        // floor locomotion and climbing are unaffected.
        if (MovementConfig.groundedGripRelease && state.floorGrip
                && handSwing.length() < MovementConfig.minImpulse
                && bodyOnSolidGround(client)) {
            drag = new Vec3(0.0, Math.max(0.0, drag.y), 0.0);
        }

        // Surface slip (GT Surface.slipPercentage): on ice the anchor chases the
        // hand — push-offs work, hanging on doesn't.
        double slip = (iceNearMultiplier(client, touchHand) > 0.0)
                ? Math.max(0.0, Math.min(1.0, MovementConfig.gtIceSlip)) : 0.0;
        if (slip > 0.0) {
            state.anchor = state.anchor.subtract(drag.scale(slip));
            drag = drag.scale(1.0 - slip);
        }

        // Unstick (Player.cs unStickDistance): release when the hand has PHYSICALLY
        // strayed too far from where it grabbed (head-relative, so push-strength
        // anchor receding doesn't trigger it early).
        if (offset.subtract(state.gripOffset).length() > MovementConfig.gtUnstickDistance) {
            state.release();
            return Vec3.ZERO;
        }

        return drag;
    }

    // -----------------------------------------------------------------------
    // PER-HAND SWING  — returns the velocity (blocks/tick) this hand contributes
    // -----------------------------------------------------------------------

    // The hand holding a hoverboard steers it and never grabs.
    private static Vec3 holdingBoard(HandState state) {
        state.release();
        return Vec3.ZERO;
    }

    private Vec3 processHand(Minecraft client, HandState state,
                              Vec3 touchHand, Vec3 swingHand, Vec3 headPos,
                              double fallSpeed, boolean topFace) {

        // Use the fall-sweep version: when falling fast the extended-reach endpoint can
        // skip past thin floors in one tick. Extending the detection AABB upward by the
        // fall speed catches any block the hand passed through this tick.
        boolean touching = isTouchingBlockFallSweep(client, touchHand, fallSpeed);
        if (!touching) {
            state.floorGripSuppressed = false;   // hand left the surface — re-enable grip
            state.release();
            return Vec3.ZERO;
        }

        // Measure swing from swingHand (real hand, clamped to arm length) relative to
        // the head. We ALWAYS use swingHand here — never touchHand — so the reference is
        // consistent between the init tick and every subsequent tick. Using touchHand
        // (which is extended 2.5× by reach) on init but swingHand afterwards created a
        // phantom delta on the very first movement tick ("reach-gap suction"). Rebound
        // from punches (swingHand briefly inside a block) is handled by clampToBlocks.
        Vec3 offset = swingHand.subtract(headPos);

        if (!state.gripping) {
            boolean wouldFloor = topFace || touchHand.y < headPos.y - FLOOR_GRIP_DEPTH;
            // Don't re-latch a floor grip you were just freed from while you're still
            // resting on it (see floorGripSuppressed) — otherwise walking away re-wedges
            // you every tick. It re-enables as soon as the hand swings or lifts off.
            if (wouldFloor && state.floorGripSuppressed && bodyOnSolidGround(client)) {
                state.prevOffset = offset;
                return Vec3.ZERO;
            }
            // First contact: record the baseline, produce NO velocity yet.
            state.gripping  = true;
            // FLOOR vs WALL is decided by the FACE the hand grabbed, not just depth:
            //   - any TOP face (upward-facing surface) = floor grip — the hand plants
            //     on it and supports you, no matter how high the block is. This is what
            //     lets you stand on / push up from blocks at waist height regardless of
            //     wallStickiness (which only governs SIDE-face wall grips).
            //   - otherwise fall back to the depth rule (grab point well below the
            //     head = reached down to the ground), which covers the no-raycast case
            //     (hand already inside a block, ray missed).
            state.floorGrip = wouldFloor;
            state.prevOffset = offset;  // swingHand-based, same as every subsequent tick
            return Vec3.ZERO;
        }

        // WALL → FLOOR PROMOTION (one-way, re-checked every tick of the grip).
        // Mantling a step, the hand usually brushes the block's SIDE first (so the
        // grip latches as a wall) and only then lands on TOP. Without promotion the
        // grip stayed "wall" forever, the low-stick slide kicked in, and pushing up
        // a 1-block step was nearly impossible. The moment the hand's ray sees a top
        // face, the grip becomes a supporting floor grip — this also makes sliding
        // up over a wall's top edge a proper ledge grab. One-way only (never floor →
        // wall) so the behaviour can't flip-flop mid-grip.
        if (!state.floorGrip && topFace) {
            state.floorGrip = true;
        }

        // How far your hand moved (relative to head) since last tick = your swing.
        Vec3 swing = offset.subtract(state.prevOffset);
        state.prevOffset = offset;

        // GROUNDED GRIP RELEASE (see the GT-mode note): a resting floor grip while
        // standing on the ground is dropped — and SUPPRESSED so it won't re-latch while
        // the hand keeps resting on it — so you can walk off without pulling your hand
        // back (the "wedged after landing" bug). A real swing (hand moved) keeps/re-arms
        // the grip; airborne ledge grips have no ground under the feet and are untouched.
        if (MovementConfig.groundedGripRelease && state.floorGrip
                && bodyOnSolidGround(client)) {
            if (swing.length() < MovementConfig.minImpulse) {
                state.floorGripSuppressed = true;
                state.release();
                return Vec3.ZERO;
            }
            state.floorGripSuppressed = false;   // you swung — this is a real pull
        }

        // Body moves OPPOSITE to the swing, scaled by pull strength.
        return swing.scale(-MovementConfig.pullStrength);
    }

    // -----------------------------------------------------------------------
    // SWING-SPEED AVERAGE  (for the throw)
    // -----------------------------------------------------------------------

    private void ensureSwingHistory() {
        int size = Math.max(1, (int) MovementConfig.velocityHistorySize);
        if (swingHistory == null || swingHistory.length != size) {
            swingHistory = new Vec3[size];
            Arrays.fill(swingHistory, Vec3.ZERO);
            swingIndex = 0;
            swingAvg = Vec3.ZERO;
        }
    }

    private void storeSwing(Vec3 v) {
        swingIndex = (swingIndex + 1) % swingHistory.length;
        Vec3 oldest = swingHistory[swingIndex];
        swingAvg = swingAvg.add(v.subtract(oldest).scale(1.0 / swingHistory.length));
        swingHistory[swingIndex] = v;
    }

    // -----------------------------------------------------------------------
    // TOUCH TEST
    // -----------------------------------------------------------------------

    // Normal point check — is this position inside/touching any solid block?
    private boolean isTouchingBlock(Minecraft client, Vec3 handPos) {
        return isTouchingAABB(client, handAABB(handPos));
    }

    // Fall-sweep check: when the player is falling fast the extended-reach
    // endpoint can overshoot a floor entirely in a single tick. We widen the
    // detection box UPWARD by `fallSpeed` (blocks/tick) so any block the hand
    // passed through on the way down is still caught.
    private boolean isTouchingBlockFallSweep(Minecraft client, Vec3 handPos, double fallSpeed) {
        if (fallSpeed < 0.1) return isTouchingBlock(client, handPos);
        double r = MovementConfig.handRadius;
        // Extend the AABB upward by the fall speed so the sweep covers the full
        // vertical distance the hand could have skipped over this tick.
        AABB sweepBox = new AABB(
            handPos.x - r, handPos.y - r,              handPos.z - r,
            handPos.x + r, handPos.y + r + fallSpeed,  handPos.z + r
        );
        return isTouchingAABB(client, sweepBox);
    }

    // Core AABB-vs-blocks test — shared by both touch variants above.
    private boolean isTouchingAABB(Minecraft client, AABB box) {
        BlockPos minPos = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos maxPos = BlockPos.containing(box.maxX, box.maxY, box.maxZ);

        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int y = minPos.getY(); y <= maxPos.getY(); y++) {
                for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState bs = client.level.getBlockState(pos);
                    if (bs.isAir()) continue;
                    // Ice: hands slip off all faces (slippery, not climbable).
                    // Barrier: server admin block — completely invisible to the mod
                    //          so operators can wall off areas players can't swing past.
                    if (isUngrabbable(bs)) continue;

                    VoxelShape shape = bs.getCollisionShape(client.level, pos);
                    if (shape.isEmpty()) continue;

                    for (AABB piece : shape.toAabbs()) {
                        AABB worldBox = piece.move(pos.getX(), pos.getY(), pos.getZ());
                        if (box.intersects(worldBox)) return true;
                    }
                }
            }
        }
        return false;
    }

    // Returns true if any barrier block exists within the given AABB.
    // Used to suppress step assist so players can't walk/boost over admin barriers.
    private static boolean hasBarrierInAABB(Level level, AABB box) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (int x = min.getX(); x <= max.getX(); x++)
            for (int y = min.getY(); y <= max.getY(); y++)
                for (int z = min.getZ(); z <= max.getZ(); z++)
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) return true;
        return false;
    }

    // Blocks that hands cannot grip at all. Routes through isTouchingAABB so it
    // covers every face (wall, floor, ceiling) with a single check.
    //   Barrier     — server admin block; the mod treats it as invisible so operators
    //                 can fence off areas this mod cannot bypass.
    //   Leaf litter — thin ground-cover decoration (added in 1.21.5). It has a tiny
    //                 collision shape your hand catches on whenever you reach to the
    //                 floor, snagging locomotion on something you can't actually grab.
    //                 Make it pass-through for the HANDS only — the player body still
    //                 sees vanilla's real collision (this list is checked solely by the
    //                 grab detection, never by the movement/body collision path).
    // NOTE: ice is NOT listed here — ice DOES allow a brief first-contact grip so
    //       the push-off impulse path (above) can fire. Ice just can't be sustained.
    private static boolean isUngrabbable(BlockState bs) {
        // Ladders, vines, scaffolding, cave/weeping/twisting vines (the CLIMBABLE tag):
        // pass-through for the hands so gorilla physics never latches onto the ladder and
        // fights the climb. Vivecraft's own ladder climbing is a separate system and is
        // unaffected; floor-pushing on nearby solid blocks still works normally.
        return bs.is(Blocks.BARRIER)
                //? if >=1.21.5
                || bs.is(Blocks.LEAF_LITTER)
                || bs.is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    // -----------------------------------------------------------------------
    // SERVER-SIDE FALL-DAMAGE SUPPRESSION (while gripping)
    // -----------------------------------------------------------------------

    // Fall damage is applied SERVER-SIDE from the server's OWN copy of fallDistance,
    // so resetting the client LocalPlayer alone never stops the hit. In singleplayer /
    // LAN host the integrated server runs in this JVM, so each gripping tick we also
    // reset its ServerPlayer's fallDistance. Same graded result as the client reset:
    // slide all the way down = zero damage; let go partway = damage only from the
    // point you released (as if dropped from that height).
    //
    // On a DEDICATED server fall damage is authoritative on its side and a pure-client
    // install can't reach it — that would need the companion mod to handle grip state.
    private static void suppressServerFall(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.player == null) return;
        var server = client.getSingleplayerServer();
        if (server == null) return;
        java.util.UUID id = client.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.resetFallDistance();
        });
    }

    // One comprehensive per-tick state line (no-op unless debug logging is on). Covers
    // everything the mod is doing this tick: physics mode, teleport state, player vs VR
    // head position (hOff = the desync signal), per-hand grip kind, velocity, and ground/
    // ice/gravity flags. Phase tags the moment: TICK (normal), AIM/SETTLE (teleport).
    private void logState(Minecraft client, String phase, LocalPlayer player) {
        if (!MovementConfig.debugLogging) return;
        Vec3 pp = player.position();
        Vec3 hp = vr.getHeadPos();
        Vec3 v  = player.getDeltaMovement();
        double hOff = (hp == null) ? -1.0
                : Math.sqrt((hp.x - pp.x) * (hp.x - pp.x) + (hp.z - pp.z) * (hp.z - pp.z));
        boolean onIce = client.level != null && iceBlockMultiplier(client,
                BlockPos.containing(pp.x, player.getBoundingBox().minY - 0.1, pp.z)) > 0.0;
        VmcDebugLog.log(String.format(
            "%-6s mode=%s aim=%-5s cd=%-2d player=(%.2f,%.2f,%.2f) head=%s hOff=%.2f grip=%s/%s vel=(%.3f,%.3f,%.3f) ground=%b ice=%b noGrav=%b",
            phase, MovementConfig.gtPhysics ? "GT" : "LEG",
            vr.isTeleportAiming(), teleportCooldown,
            pp.x, pp.y, pp.z,
            (hp == null ? "null" : String.format("(%.2f,%.2f,%.2f)", hp.x, hp.y, hp.z)),
            hOff, gripStr(mainHand), gripStr(offHand), v.x, v.y, v.z,
            player.onGround(), onIce, noGravSet));
    }

    // Compact per-hand grip descriptor for the state line: "-" free, "F" floor, "W" wall.
    private static String gripStr(HandState h) {
        return !h.gripping ? "-" : (h.floorGrip ? "F" : "W");
    }

    // Last-logged grip state, so we emit a GRIP event only on a change.
    private boolean loggedGripM = false, loggedGripO = false;

    private void logGripChanges() {
        if (!VmcDebugLog.on()) return;
        if (mainHand.gripping != loggedGripM) {
            VmcDebugLog.event("GRIP", "main " + (mainHand.gripping
                    ? "grab " + (mainHand.floorGrip ? "FLOOR" : "WALL") : "release"));
            loggedGripM = mainHand.gripping;
        }
        if (offHand.gripping != loggedGripO) {
            VmcDebugLog.event("GRIP", "off " + (offHand.gripping
                    ? "grab " + (offHand.floorGrip ? "FLOOR" : "WALL") : "release"));
            loggedGripO = offHand.gripping;
        }
    }

    // Whether we gripped this tick (climbing or sliding) — the no-fall-damage state.
    // VivemonkecraftClient reads this after tick() to drive the dedicated-server
    // fall-suppression keepalive (WallSlideC2SPayload). True only for the tick that
    // just ran; the GUI-pause / disabled paths leave it false.
    public boolean isGripping() {
        return grippingThisTick;
    }

    // -----------------------------------------------------------------------
    // WALL-CLIP GUARD
    // -----------------------------------------------------------------------

    // Sweeps the player AABB along each velocity axis using Level.noCollision().
    // Any axis that would enter a solid block this tick has its velocity zeroed.
    // Called at the end of every gripping tick after all grip maths have settled.
    private static Vec3 clampToBlocks(Minecraft mc, LocalPlayer player, Vec3 vel) {
        if (mc.level == null || vel.lengthSqr() < 1e-10) return vel;
        AABB box = player.getBoundingBox();

        // Fast path: the full swept AABB hits nothing → entire movement is safe.
        if (mc.level.noCollision(player, box.expandTowards(vel.x, vel.y, vel.z))) return vel;

        // Slow path: at least one axis would clip. Zero each offending component.
        double vx = vel.x, vy = vel.y, vz = vel.z;
        if (vx != 0 && !mc.level.noCollision(player, box.expandTowards(vx, 0, 0))) vx = 0;
        if (vy != 0 && !mc.level.noCollision(player, box.expandTowards(0, vy, 0))) vy = 0;
        if (vz != 0 && !mc.level.noCollision(player, box.expandTowards(0, 0, vz))) vz = 0;
        return new Vec3(vx, vy, vz);
    }

    // -----------------------------------------------------------------------
    // LAUNCH STEP BOOST
    // -----------------------------------------------------------------------

    // Called each tick while the player is airborne and NOT gripping.
    // Detects when the player's horizontal movement is blocked by a block
    // that is within stepHeight reach, and adds just enough upward velocity
    // to carry the player over the top edge — preserving horizontal momentum.
    //
    // How it works:
    //   1. Look 0.15 blocks ahead in the velocity direction.
    //   2. Check if that strip is blocked at current height (feet hit a side).
    //   3. If blocked, iterate upward in 1/16-block steps to find the minimum
    //      dy that would give a clear horizontal path (= block top height).
    //   4. If dy ≤ stepHeight, boost vel.y to sqrt(2 * g * dy) * 1.5 so the
    //      player arcs cleanly onto the block top.
    //
    // Only fires when vel.y is below the needed target, so it never cancels
    // an upward throw that is already clearing the obstacle.
    private static void applyLaunchStepBoost(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return;
        Vec3 vel = player.getDeltaMovement();

        // Must be moving meaningfully in the horizontal plane.
        double hSq = vel.x * vel.x + vel.z * vel.z;
        if (hSq < 0.008 * 0.008) return;

        // Already going up fast enough, or falling so hard that a step-up
        // would feel wrong — skip.
        if (vel.y > 0.20) return;
        if (vel.y < -0.65) return;

        AABB box = player.getBoundingBox();

        // Project 0.15 blocks ahead in the velocity direction so we catch
        // blocks the player is touching OR is one frame away from touching.
        double hLen = Math.sqrt(hSq);
        double lookX = vel.x / hLen * 0.15;
        double lookZ = vel.z / hLen * 0.15;

        // Fast-exit: if horizontal path is already clear, nothing to do.
        if (mc.level.noCollision(player, box.expandTowards(lookX, 0, lookZ))) return;
        // Don't boost over barrier blocks — they're admin boundaries.
        if (hasBarrierInAABB(mc.level, box.expandTowards(lookX, 0, lookZ))) return;

        // Step iteration: find the smallest upward offset that clears the path.
        double maxStep = MovementConfig.stepHeight;
        double foundStep = -1;
        for (double dy = 0.0625; dy <= maxStep + 0.01; dy += 0.0625) {
            if (mc.level.noCollision(player,
                    box.move(0, dy, 0).expandTowards(lookX, 0, lookZ))) {
                foundStep = dy;
                break;
            }
        }

        // Path stays blocked all the way to the step limit → can't step over,
        // and tiny steps (< 0.04) are handled by vanilla collision resolution.
        if (foundStep < 0.04) return;

        // Physics: minimum upward velocity to clear `foundStep` blocks =
        //   v = sqrt(2 * g * h)
        // Use effective gravity (respects gravityMultiplier, floor at 0.02 so
        // we get a valid velocity even in near-zero-gravity modes).
        // Multiply by 1.5 for a safety margin — better to slightly overshoot
        // the block top than to barely clip it and fall back.
        double effG    = Math.max(0.02, 0.08 * MovementConfig.gravityMultiplier);
        double boostY  = Math.sqrt(2.0 * effG * foundStep) * 1.5;
        boostY = Math.max(boostY, 0.15);   // floor so tiny steps still get a kick
        boostY = Math.min(boostY, 0.65);   // cap so launching never rockets the player

        if (vel.y < boostY) {
            player.setDeltaMovement(vel.x, boostY, vel.z);
        }
    }

    // -----------------------------------------------------------------------
    // STEP HEIGHT
    // -----------------------------------------------------------------------

    private void applyStepHeight(LocalPlayer player, double value) {
        AttributeInstance inst = player.getAttribute(Attributes.STEP_HEIGHT);
        if (inst == null) return;
        if (savedStepHeight == null) savedStepHeight = inst.getBaseValue();
        if (inst.getBaseValue() != value) inst.setBaseValue(value);
    }

    private void restoreStepHeight(LocalPlayer player) {
        if (savedStepHeight == null) return;
        AttributeInstance inst = player.getAttribute(Attributes.STEP_HEIGHT);
        if (inst != null) inst.setBaseValue(savedStepHeight);
        savedStepHeight = null;
    }

    // -----------------------------------------------------------------------
    // ICE BLOCK HELPERS
    // -----------------------------------------------------------------------

    // Returns the speed multiplier for the ice block at `pos`:
    //   0.4 → any ice variant (ICE, PACKED_ICE, BLUE_ICE)
    //   0.0 → not ice
    // Kept well below 1.0 so ice surfaces slow you down rather than launching
    // you at high velocity, keeping physics cost low on Quest hardware.
    private static double iceBlockMultiplier(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return 0.0;
        BlockState bs = mc.level.getBlockState(pos);
        if (bs.is(Blocks.ICE) || bs.is(Blocks.PACKED_ICE) || bs.is(Blocks.BLUE_ICE)) return 0.4;
        return 0.0;
    }

    // Returns the highest ice multiplier found within ±0.06 blocks of `pos`.
    // clampGrabToSurface() places the grab point exactly on the block face, so a plain
    // BlockPos.containing() may resolve to the adjacent air block rather than the ice
    // itself. Probing all 6 face-adjacent samples guarantees we catch the ice block
    // regardless of which face was hit.
    private static double iceNearMultiplier(Minecraft mc, Vec3 pos) {
        if (mc.level == null) return 0.0;
        double d = 0.06;
        double[][] probes = {
            {0, 0, 0}, {d, 0, 0}, {-d, 0, 0},
            {0, d, 0}, {0, -d, 0}, {0, 0, d}, {0, 0, -d}
        };
        double best = 0.0;
        for (double[] o : probes) {
            best = Math.max(best, iceBlockMultiplier(mc,
                    BlockPos.containing(pos.x + o[0], pos.y + o[1], pos.z + o[2])));
        }
        return best;
    }

    // VANILLA ICE FRICTION (experimental) — per-tick horizontal momentum KEEP factor for
    // a slippery surface, = the block's vanilla friction × 0.91 (ice ≈ 0.89, blue ice a
    // touch higher). Returns -1 when the toggle is off or no icy block is in contact, so
    // callers fall back to the mod's own ice handling. Checks the feet block and both
    // grab points, so it works whether you're standing on OR gripping the ice.
    private double vanillaIceKeep(Minecraft client, BlockPos feetPos, Vec3 grabMain, Vec3 grabOff) {
        if (!MovementConfig.vanillaIceFriction || client.level == null) return -1.0;
        double k = iceFrictionKeep(client, feetPos);
        if (k > 0.0) return k;
        k = iceFrictionKeepNear(client, grabMain);
        if (k > 0.0) return k;
        return iceFrictionKeepNear(client, grabOff);
    }

    private double iceFrictionKeep(Minecraft client, BlockPos pos) {
        if (pos == null || client.level == null) return -1.0;
        BlockState bs = client.level.getBlockState(pos);
        if (!(bs.is(Blocks.ICE) || bs.is(Blocks.PACKED_ICE) || bs.is(Blocks.BLUE_ICE))) return -1.0;
        return bs.getBlock().getFriction() * 0.91;
    }

    // Same probe pattern as iceNearMultiplier, but returns the vanilla keep factor of the
    // first icy block found around the (face-clamped) grab point.
    private double iceFrictionKeepNear(Minecraft client, Vec3 point) {
        if (point == null || client.level == null) return -1.0;
        double d = 0.06;
        double[][] probes = {
            {0, 0, 0}, {d, 0, 0}, {-d, 0, 0}, {0, d, 0}, {0, -d, 0}, {0, 0, d}, {0, 0, -d}
        };
        for (double[] o : probes) {
            double k = iceFrictionKeep(client,
                    BlockPos.containing(point.x + o[0], point.y + o[1], point.z + o[2]));
            if (k > 0.0) return k;
        }
        return -1.0;
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    private AABB handAABB(Vec3 pos) {
        double r = MovementConfig.handRadius;
        return new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);
    }

    // -----------------------------------------------------------------------
    // HAND SURFACE RESOLVE — keep a FREE hand's model out of solid geometry
    // -----------------------------------------------------------------------
    //
    // Independent of grab detection and of whichever physics motor runs, so it stays
    // identical in legacy and GT anchor mode. Two passes over the local
    // blocks, inflated by handRadius so the hand's cube (not just its centre) stops
    // at the face:
    //   1) SWEEP  — trace last->current hand through each block box; if the hand
    //               entered a block this tick (t in (0,1]) clamp it to that entry
    //               point (catches fast swings/falls that tunnel a hand through a
    //               block in one tick).
    //   2) PUSH-OUT — if the hand is currently sitting INSIDE a block, shove it to
    //               the nearest face.
    // Sweep wins when both fire (motion-aware). Returns the resolved surface point,
    // or null when the hand is in open air. Ungrabbable blocks are skipped so the
    // hand passes through them just like the grab detection.
    private Vec3 handSurfaceResolve(Minecraft client, Vec3 hand, Vec3 prevHand) {
        if (client.level == null) return null;
        double r = MovementConfig.handRadius;

        Vec3 lo = (prevHand != null) ? new Vec3(Math.min(hand.x, prevHand.x),
                                                Math.min(hand.y, prevHand.y),
                                                Math.min(hand.z, prevHand.z)) : hand;
        Vec3 hi = (prevHand != null) ? new Vec3(Math.max(hand.x, prevHand.x),
                                                Math.max(hand.y, prevHand.y),
                                                Math.max(hand.z, prevHand.z)) : hand;
        BlockPos min = BlockPos.containing(lo.x - r - 1, lo.y - r - 1, lo.z - r - 1);
        BlockPos max = BlockPos.containing(hi.x + r + 1, hi.y + r + 1, hi.z + r + 1);

        double bestT = Double.MAX_VALUE;
        Vec3   sweepPoint = null;
        double bestPush = Double.MAX_VALUE;
        Vec3   pushPoint = null;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState bs = client.level.getBlockState(pos);
                    if (bs.isAir() || isUngrabbable(bs)) continue;
                    VoxelShape shape = bs.getCollisionShape(client.level, pos);
                    if (shape.isEmpty()) continue;

                    for (AABB piece : shape.toAabbs()) {
                        AABB box = piece.move(pos.getX(), pos.getY(), pos.getZ()).inflate(r);

                        if (prevHand != null) {
                            double t = rayAabbEntry(prevHand, hand, box);
                            if (t > 1.0e-4 && t <= 1.0 && t < bestT) {
                                bestT = t;
                                sweepPoint = prevHand.add(hand.subtract(prevHand).scale(t));
                            }
                        }

                        if (box.contains(hand.x, hand.y, hand.z)) {
                            double dxMin = hand.x - box.minX, dxMax = box.maxX - hand.x;
                            double dyMin = hand.y - box.minY, dyMax = box.maxY - hand.y;
                            double dzMin = hand.z - box.minZ, dzMax = box.maxZ - hand.z;
                            double m = Math.min(Math.min(Math.min(dxMin, dxMax),
                                                Math.min(dyMin, dyMax)),
                                                Math.min(dzMin, dzMax));
                            if (m < bestPush) {
                                bestPush = m;
                                if      (m == dxMin) pushPoint = new Vec3(box.minX, hand.y, hand.z);
                                else if (m == dxMax) pushPoint = new Vec3(box.maxX, hand.y, hand.z);
                                else if (m == dyMin) pushPoint = new Vec3(hand.x, box.minY, hand.z);
                                else if (m == dyMax) pushPoint = new Vec3(hand.x, box.maxY, hand.z);
                                else if (m == dzMin) pushPoint = new Vec3(hand.x, hand.y, box.minZ);
                                else                 pushPoint = new Vec3(hand.x, hand.y, box.maxZ);
                            }
                        }
                    }
                }
            }
        }

        return sweepPoint != null ? sweepPoint : pushPoint;
    }

    // Entry parameter t in [0,1] where segment origin->end first enters box (slab
    // method); Double.MAX_VALUE on a miss. If the origin is already inside, t clamps
    // to 0 and the caller treats that as "not a sweep hit".
    private static double rayAabbEntry(Vec3 origin, Vec3 end, AABB box) {
        double dx = end.x - origin.x, dy = end.y - origin.y, dz = end.z - origin.z;
        double tmin = 0.0, tmax = 1.0;

        if (Math.abs(dx) < 1.0e-9) {
            if (origin.x < box.minX || origin.x > box.maxX) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minX - origin.x) / dx, t2 = (box.maxX - origin.x) / dx;
            if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }
        if (Math.abs(dy) < 1.0e-9) {
            if (origin.y < box.minY || origin.y > box.maxY) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minY - origin.y) / dy, t2 = (box.maxY - origin.y) / dy;
            if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }
        if (Math.abs(dz) < 1.0e-9) {
            if (origin.z < box.minZ || origin.z > box.maxZ) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minZ - origin.z) / dz, t2 = (box.maxZ - origin.z) / dz;
            if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }
        return tmin;
    }

    // Is the player's BODY actually resting on solid ground? Used by
    // groundedGripRelease to tell "standing on the floor" (safe to drop a resting
    // floor grip so you can walk) from "hanging off a ledge by a floor grip" (must
    // keep it). onGround() alone is unreliable while gripping because the anchor
    // holds the body with gravity off, so we also probe a thin slab just under the
    // feet for any collision.
    private boolean bodyOnSolidGround(Minecraft client) {
        LocalPlayer p = client.player;
        if (p == null || client.level == null) return false;
        if (p.onGround()) return true;
        AABB bb = p.getBoundingBox();
        AABB probe = new AABB(bb.minX + 0.001, bb.minY - 0.10, bb.minZ + 0.001,
                              bb.maxX - 0.001, bb.minY,         bb.maxZ - 0.001);
        return isTouchingAABB(client, probe);
    }

    private Vec3 clampArm(Vec3 hand, Vec3 head) {
        Vec3   offset = hand.subtract(head);
        double len    = offset.length();
        double max    = MovementConfig.maxArmLength;
        return len > max ? head.add(offset.scale(max / len)) : hand;
    }

    // Pushes the hand further out along head->hand so you can reach the ground from
    // standing height. Used ONLY for grab detection, not for measuring your swing.
    private Vec3 extendReach(Vec3 hand, Vec3 head) {
        double m = MovementConfig.handReachMultiplier;
        if (m == 1.0) return hand;
        return head.add(hand.subtract(head).scale(m));
    }

    // Raycasts from `origin` (the HEAD) toward touchPoint. If the ray hits a block
    // face before reaching touchPoint, the grab point snaps to that face — so the
    // hand AABB (and its visual cube) can never sink inside a wall or the floor,
    // no matter how hard the player pushes the controller into the block.
    //
    // Returns the full BlockHitResult (location + WHICH FACE was hit) or null on a
    // miss. The face drives the floor-vs-wall grip classification: Direction.UP =
    // supporting top surface, anything else = wall/ceiling.
    //
    // NOTE: the detection AABB used by isTouchingAABB is a cube of side 2*handRadius.
    // The grab-point marker in HandMarkerRenderer uses the same radius — they match.
    private static BlockHitResult raycastGrab(Minecraft mc, LocalPlayer player,
                                               Vec3 origin, Vec3 touchPoint) {
        if (mc.level == null) return null;
        Vec3 dir = touchPoint.subtract(origin);
        double dirLenSq = dir.lengthSqr();
        if (dirLenSq < 1e-10) return null;
        // Start the ray slightly BEHIND the origin so the cast isn't blinded when
        // the head is pressed right up against a block face.
        Vec3 start = origin.subtract(dir.scale(0.1 / Math.sqrt(dirLenSq)));
        // COLLIDER mode = solid block faces only; same collision layer the player uses.
        BlockHitResult hit = mc.level.clip(new ClipContext(
            start, touchPoint,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            player
        ));
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }
}
