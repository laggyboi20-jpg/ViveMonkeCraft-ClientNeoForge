package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.compat.VmcCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// =====================================================================
// SETTING CAPS — what players may use WITHOUT cheats / operator access
// =====================================================================
//
// Three permission tiers, checked at USE time (every tick, in the physics
// handler) so editing the config file or sliders can't bypass them:
//
//   1. CHEATS / OP (permission level 2+ — singleplayer with cheats enabled,
//      or /op on a server)        → UNRESTRICTED, raw config values apply.
//   2. SERVER ALLOWANCE — the monke-server companion's config can RAISE any
//      cap for everyone on that server (e.g. allowPushStrength=5.0 lets every
//      player run push 5 even without op).
//   3. DEFAULT CAPS — the constants below. Without cheats and without a
//      server allowance, settings are clamped to these.
//
// ============================ TUNE ME =================================
// These are the default allowed limits. Edit freely — each one is the value
// a normal, non-privileged player is clamped to. MAX_* clamp from above,
// MIN_* clamp from below (for settings where LOWER = more advantage).
// =======================================================================
public final class SettingCaps {

    public static final double MAX_PUSH         = 2.5;  // pullStrength AND gtPushStrength
    public static final double MAX_JUMP_MULT    = 1.8;  // jumpMultiplier (throw boost)
    public static final double MAX_JUMP_SPEED   = 1.5;  // maxJumpSpeed (blocks/tick launch cap)
    public static final double MAX_HAND_REACH   = 2.5;  // handReachMultiplier
    public static final double MAX_ARM_LENGTH   = 3.0;  // maxArmLength (blocks)
    public static final double MAX_STEP_HEIGHT  = 1.5;  // stepHeight (blocks)
    public static final double MAX_HAND_RADIUS  = 0.15; // handRadius (grab cube size)
    public static final double MIN_GRAVITY      = 1.0;  // gravityMultiplier (1.0 = no reduction)
    public static final double MIN_AIR_FRICTION = 0.8;  // airFriction (lower = less drag)
    public static final double MAX_HOVER_SPEED  = 0.7;  // hoverMaxSpeed (blocks/tick)
    public static final double MAX_HOVER_ACCEL  = 0.05; // hoverAccel (blocks/tick²)
    public static final double MAX_HOVER_HEIGHT = 0.5;  // hoverHeight on servers — see hoverHeight()
    public static final double MAX_HOVER_HEIGHT_FREE = 4.0; // hoverHeight in singleplayer / with op

    private SettingCaps() {}

    // -----------------------------------------------------------------------
    // Permission check: "are cheats / commands available to this player?"
    // Singleplayer with cheats ON → level 4. Op on a server → level 2+.
    // Singleplayer with cheats OFF / normal server player → level 0 → capped.
    // -----------------------------------------------------------------------
    public static boolean unrestricted() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && VmcCompat.isOperator(p);
    }

    // -----------------------------------------------------------------------
    // Effective values — the handler reads THESE instead of MovementConfig
    // for every advantage-relevant setting.
    // -----------------------------------------------------------------------

    /** pullStrength (legacy swing multiplier), capped. */
    public static double pullStrength() {
        return capMax(MovementConfig.pullStrength, MAX_PUSH, ServerLimits.allowPushStrength());
    }

    /** gtPushStrength (GT anchor-mode push multiplier), capped by the same allowance. */
    public static double gtPushStrength() {
        return capMax(MovementConfig.gtPushStrength, MAX_PUSH, ServerLimits.allowPushStrength());
    }

    /** jumpMultiplier (throw boost), capped. */
    public static double jumpMultiplier() {
        return capMax(MovementConfig.jumpMultiplier, MAX_JUMP_MULT, ServerLimits.allowJumpMultiplier());
    }

    /**
     * maxJumpSpeed: capped from above by default/allowance, AND still subject to
     * the server's hard enforcement limit (ServerLimits.maxJumpSpeed) which the
     * server backs up with position correction + kicks.
     */
    public static double maxJumpSpeed() {
        double capped = capMax(MovementConfig.maxJumpSpeed, MAX_JUMP_SPEED, ServerLimits.allowMaxJumpSpeed());
        return ServerLimits.maxJumpSpeed(capped);
    }

    /** handReachMultiplier, capped. */
    public static double handReach() {
        return capMax(MovementConfig.handReachMultiplier, MAX_HAND_REACH, ServerLimits.allowHandReach());
    }

    /** maxArmLength, capped. */
    public static double maxArmLength() {
        return capMax(MovementConfig.maxArmLength, MAX_ARM_LENGTH, ServerLimits.allowArmLength());
    }

    /** stepHeight, capped. */
    public static double stepHeight() {
        return capMax(MovementConfig.stepHeight, MAX_STEP_HEIGHT, ServerLimits.allowStepHeight());
    }

    /** handRadius, capped. */
    public static double handRadius() {
        return capMax(MovementConfig.handRadius, MAX_HAND_RADIUS, ServerLimits.allowHandRadius());
    }

    /** gravityMultiplier, clamped from BELOW (lower gravity = stronger advantage). */
    public static double gravityMultiplier() {
        return capMin(MovementConfig.gravityMultiplier, MIN_GRAVITY, ServerLimits.allowGravityMin());
    }

    /** airFriction, clamped from BELOW (less drag = longer throws). */
    public static double airFriction() {
        return capMin(MovementConfig.airFriction, MIN_AIR_FRICTION, ServerLimits.allowAirFrictionMin());
    }

    /** hoverMaxSpeed, capped, and never above the server's hard launch-speed limit. */
    public static double hoverMaxSpeed() {
        double capped = capMax(MovementConfig.hoverMaxSpeed, MAX_HOVER_SPEED, 0.0);
        return ServerLimits.maxJumpSpeed(capped);
    }

    /** hoverAccel, capped. */
    public static double hoverAccel() {
        return capMax(MovementConfig.hoverAccel, MAX_HOVER_ACCEL, 0.0);
    }

    /**
     * hoverHeight. Vanilla servers without allow-flight kick a player whose feet
     * stay more than ~0.55 above any block for 4 s, so on a server it is clamped
     * to 0..0.5. Singleplayer (flight is always allowed there) and ops (whose
     * server can turn allow-flight on) get up to MAX_HOVER_HEIGHT_FREE.
     */
    public static double hoverHeight() {
        boolean free = Minecraft.getInstance().hasSingleplayerServer() || unrestricted();
        return Math.max(0.0, Math.min(free ? MAX_HOVER_HEIGHT_FREE : MAX_HOVER_HEIGHT,
                                      MovementConfig.hoverHeight));
    }

    /** Hover distance kept off a wall — the hover height, but never more than 0.5. */
    public static double hoverWallGap() {
        return Math.min(MAX_HOVER_HEIGHT, hoverHeight());
    }

    // ---- Gorilla Tag clone (mode 1) — same allowances, converted to GT units ----

    /** gtJumpMultiplier, capped like jumpMultiplier. */
    public static double gtJumpMultiplier() {
        return capMax(MovementConfig.gtJumpMultiplier, MAX_JUMP_MULT, ServerLimits.allowJumpMultiplier());
    }

    /** gtMaxJumpSpeed in blocks/tick: the m/s setting under the same launch caps as maxJumpSpeed. */
    public static double gtMaxJumpSpeedPerTick() {
        double allow = ServerLimits.allowMaxJumpSpeed();
        double capped = capMax(MovementConfig.gtMaxJumpSpeed / 20.0, MAX_JUMP_SPEED, allow);
        return ServerLimits.maxJumpSpeed(capped);
    }

    /** gtArmLength, capped like maxArmLength. */
    public static double gtArmLength() {
        return capMax(MovementConfig.gtArmLength, MAX_ARM_LENGTH, ServerLimits.allowArmLength());
    }

    /** gtHandRadius, capped like handRadius. */
    public static double gtHandRadius() {
        return capMax(MovementConfig.gtHandRadius, MAX_HAND_RADIUS, ServerLimits.allowHandRadius());
    }

    /** gtGravity (m/s²), clamped from below by the gravity allowance (9.81 × multiplier). */
    public static double gtGravity() {
        double allowMin = ServerLimits.allowGravityMin();
        return capMin(MovementConfig.gtGravity, 9.81 * MIN_GRAVITY, allowMin >= 0.0 ? 9.81 * allowMin : -1.0);
    }

    // -----------------------------------------------------------------------
    // Cap helpers
    // -----------------------------------------------------------------------

    // serverAllow > 0 replaces the default cap (the server may raise OR lower it).
    private static double capMax(double raw, double defaultCap, double serverAllow) {
        if (unrestricted()) return raw;
        double cap = (serverAllow > 0.0) ? serverAllow : defaultCap;
        return Math.min(raw, cap);
    }

    // serverAllowMin >= 0 replaces the default minimum (e.g. server grants
    // gravity down to 0.5 — or 0.0 for full zero-G — for everyone).
    private static double capMin(double raw, double defaultMin, double serverAllowMin) {
        if (unrestricted()) return raw;
        double min = (serverAllowMin >= 0.0) ? serverAllowMin : defaultMin;
        return Math.max(raw, min);
    }
}
