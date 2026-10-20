package laggyboi.vivemonkecraft.client;

import laggyboi.vivemonkecraft.client.platform.VmcPlatform;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// =====================================================================
// MOVEMENT CONFIG
// =====================================================================
//
// Plain-text settings so you can tune WITHOUT editing Java/recompiling. File:
//     .minecraft/config/vivemonkecraft.properties
//
// Edit a value, save, then run /vmc reload in-game (or toggle the mod off+on).
//
// These settings drive the REAL Gorilla Tag locomotion algorithm (ported from
// the official GorillaLocomotion Player.cs): while a hand touches a block it
// "anchors" to that spot and your body is dragged so the hand stays there
// (1:1, no sliding). Letting go while moving fast flings you (the jump).
// =====================================================================

public final class MovementConfig {

    // Bumped when defaults change in a way that should override old config files.
    // An older saved version resets to the new defaults once.
    private static final int CURRENT_CONFIG_VERSION = 17;

    // -----------------------------------------------------------------------
    // The tunable values (DEFAULTS until the file is loaded)
    // -----------------------------------------------------------------------

    // Max distance a hand can be from your head (blocks). Your head is ~1.6
    // above the floor, so this must be bigger than that to reach the ground.
    public static double maxArmLength = 3.0;

    // LENGTH OF YOUR GORILLA ARMS. Your effective hand reaches this many times
    // further from your head than your real hand, so you can touch the ground
    // WITHOUT bending/reaching your real arm much. 1.0 = real length, 2.5 = long
    // gorilla arms (default). Raise to ~3.0 if you still have to reach too far;
    // lower toward 1.5 if your hands reach the ground too eagerly.
    // (This only affects WHERE you can grab — it does NOT make movement twitchy,
    // because your swing is measured on your real hand separately.)
    public static double handReachMultiplier = 2.5;

    // Radius of the hand "touch" sphere (blocks). Bigger = easier to grab.
    // 0.10 feels the smoothest (a smaller sphere snags the ground less).
    public static double handRadius = 0.10;

    // PUSH SPEED — how strongly your hand SWING becomes body movement.
    // 1.0 = 1:1 (move exactly as far as you swing), 2.0 = twice as much (default),
    // 3.0+ = very strong. This is the main "how fast do I move when I pull" knob —
    // crank it up if pulling yourself feels too weak.
    public static double pullStrength = 2.0;

    // Movements slower than this (blocks/tick) are ignored as controller jitter,
    // so a hand resting still on the ground produces ZERO movement (no creeping).
    public static double minImpulse = 0.002;

    // GROUND FRICTION — stops you sliding across the ground after a move/launch.
    // Each tick (when NOT gripping and standing on ground) horizontal velocity is
    // multiplied by this. 1.0 = no friction (slide forever), 0.5 = stops fast,
    // 0.2 = stops almost instantly. Ice surfaces ignore this (always frictionless).
    public static double groundFriction = 0.5;

    // JUMP/THROW threshold. When you let go of a surface, you only get flung if
    // your body was moving faster than this (blocks/tick). Below it, you just
    // stop (precise climbing). Raise = need a faster swing to launch.
    public static double velocityLimit = 0.06;

    // How much your built-up speed is multiplied into the throw when you let go.
    // 1.0 = release at the speed you were moving, 1.5 = a boost. (GT jumpMultiplier.)
    public static double jumpMultiplier = 1.4;

    // Hard cap on launch speed (blocks/tick) so you can't rocket off. 1.0 = 20 b/s.
    public static double maxJumpSpeed = 1.0;

    // How many ticks of body movement get averaged for the throw. More = smoother
    // but laggier launches. GT uses a small history; 6 is a good start.
    public static double velocityHistorySize = 6;

    // STEP ASSIST: raise step height while the mod is on so legs stop snagging.
    public static boolean stepAssist = true;

    // Ledge height you can step over (blocks). 0.6 = vanilla, 1.0 = full block.
    public static double stepHeight = 1.5;

    // "DELETE THE LEGS": shrinks ONLY your collision box height (not the model or
    // camera) while the mod is on, so your lower body stops catching on block
    // edges when you climb. 1.0 = normal full-height box, 0.5 = half height
    // (compact gorilla, climbs onto blocks easily), 0.35 = tiny. Width is kept
    // normal so you don't clip sideways into walls.
    public static double hitboxHeightScale = 0.25;

    // HAND MARKERS: render split arm lines at each hand's grab point.
    // GREEN = touching a block, RED = not. true/false.
    public static boolean showHandMarkers = true;

    // WALL STICKINESS — how strongly your hands cling to SIDE faces of blocks while
    // gripping. (Hands planted on TOP of a block always support you — that's a floor
    // grip and is controlled by floorStickiness, not this.)
    //   1.0 = full stick: you HANG in place forever and can climb by swinging
    //         (the original behavior — great for climbing, you never slip).
    //   0.5 = you constantly slide down the wall at half the max slide rate.
    //         The slide applies even while your hand moves, so you can't hover —
    //         but pushes keep their momentum (only the vertical is offset).
    //   0.0 = full slide rate; walls can be pushed off but never rested on.
    // Lower it if you stick to walls too well; raise it to cling harder.
    public static double wallStickiness = 1.0;

    // FLOOR STICKINESS — the floor version of wall stickiness. Controls how much you
    // SLIDE across the ground while a hand is planted on the floor (reaching down):
    //   1.0 = stop dead: your hand sticks to the spot, no sliding (default).
    //   0.5 = some glide after you push.
    //   0.0 = keep all your momentum (ice-like — you slide a long way).
    // Unlike "Ground friction", this applies WHILE you're gripping the floor; ground
    // friction applies after you let go. Lower it for a slippery, momentum-y feel.
    public static double floorStickiness = 1.0;

    // AIR FRICTION — how quickly your horizontal velocity bleeds off while airborne
    // and NOT gripping a surface.
    //   1.0 = normal Minecraft air drag (~0.91× horizontal speed per tick)
    //   0.5 = half the drag (carries momentum further in air after a throw)
    //   0.0 = no drag at all (speed preserved indefinitely — launches stay fast)
    // Lower this to make throws carry farther and feel more "floaty".
    public static double airFriction = 1.0;

    // GRAVITY MULTIPLIER — scales how strongly gravity pulls you down while the mod
    // is active, and you are NOT gripping a surface.
    //   1.0 = normal Minecraft gravity (default, feels vanilla)
    //   0.5 = half gravity — you fall slowly, can jump farther
    //   0.0 = zero gravity — no falling at all (float in place after releasing a surface)
    // Change via: /vmc gravity <0–1>   (requires operator access on the server).
    // This is intentionally op-locked because zero/low gravity effectively lets you fly,
    // which is a significant survival advantage on servers.
    public static double gravityMultiplier = 1.0;

    // CAMERA STABILIZATION: draws a black vignette (border that narrows the FOV)
    // while locomotion velocity is high. Reduces perceived motion and motion sickness.
    // true/false.
    public static boolean cameraStabEnabled = true;

    // How strong the vignette is at maximum speed.
    //   0.0 = invisible (no effect)
    //   0.5 = moderate tunnel (good starting point)
    //   1.0 = very strong tunnel vision
    public static double cameraStabStrength = 0.65;

    // GRIP SMOOTHING: low-pass filter on the locomotion velocity produced while you
    // are gripping a surface. VR controllers have ~1-2 mm of inherent tracking noise;
    // at 20 ticks/sec and pullStrength 2.0 that becomes tiny back-and-forth velocity
    // spikes that are very visible at VR framerates (90 fps) and cause motion sickness.
    // This filter blends each tick's raw velocity with the previous smoothed velocity,
    // killing the high-frequency noise while intentional swings still feel snappy.
    //   0.0 = no smoothing (raw, jittery - old behaviour)
    //   0.5 = each tick is 50% new + 50% last tick (default, ~67% noise reduction,
    //         ~5 ticks / 250 ms rise-time - recommended for VR motion sickness)
    //   0.8 = heavy smoothing, very steady camera but slightly lazy response
    // NOTE: the throw (launch on release) is unaffected - it always reads the raw
    // swing velocity so launches don't feel muted.
    public static double gripSmoothing = 0.5;

    // GORILLA TAG PHYSICS (anchor mode):
    //   true  = faithful port of the official GorillaLocomotion algorithm — a hand
    //           ANCHORS to the exact spot it touches and your body is dragged 1:1
    //           so the hand stays planted (position-based, like real Gorilla Tag).
    //           pullStrength is ignored in this mode (1:1 by definition);
    //           wall/floor stickiness are ignored too (anchors always hold, ice slips).
    //   false = the LEGACY speed-based model (body velocity follows hand swing speed
    //           scaled by pullStrength; stickiness settings apply). THIS IS THE MODE.
    //
    // NOTE: GT anchor mode is no longer exposed in-game — the toggle (and its whole
    // tuning page) is hidden in ModMenuIntegration and legacy is the one supported
    // motor. The engine below is left fully intact and still honours this flag, so it
    // can be re-enabled for testing by hand-editing config/vivemonkecraft.properties
    // (or by flipping ModMenuIntegration.SHOW_GT_PHYSICS_UI back to true).
    public static boolean gtPhysics = false;

    // LOCOMOTION MODE — index into GorillaLocomotionHandler's mode registry.
    //   0 = Speed Swing, 1 = Gorilla Tag (Player.cs clone), 2 = Charge Jump
    public static double locomotionMode = 0;

    // CHARGE JUMP (mode 2) — launch speed (blocks/tick) for a bare tap vs full hold.
    public static double chargeMinLaunch = 0.45;
    public static double chargeMaxLaunch = 1.10;

    // STEP TELEPORT: how step assist lifts you over a ledge while airborne.
    //   true  = place you directly on top of the block (instant, never overshoots)
    //   false = old behaviour: an upward velocity boost arcs you over it
    public static boolean stepTeleport = true;

    // GT DRAG GAIN — fraction of the remaining distance to the anchor corrected
    // per tick (anchor mode only). Must stay below 1.0: Vivecraft reports hand
    // positions ~1 tick late, so a full-strength correction overshoots and
    // oscillates. 0.30 converges smoothly with no bounce; raise toward 0.6
    // for snappier grabs, lower toward 0.2 for softer ones.
    public static double gtDragGain = 0.30;

    // GT UNSTICK DISTANCE — how far (blocks) a hand may stray from its anchor
    // before the grip releases (Player.cs unStickDistance; 1.0 in official GT).
    public static double gtUnstickDistance = 1.0;

    // GT ICE SLIP — fraction per tick the anchor drifts toward the hand on ice
    // (Surface.cs slipPercentage analogue). 0 = ice grips like stone,
    // 0.95 = you can push off ice but never hold onto it (default).
    public static double gtIceSlip = 0.95;

    // GT PUSH STRENGTH — anchor-mode version of pullStrength: body movement =
    // hand movement × this. 1.0 = authentic Gorilla Tag (true 1:1), 2.0 = every
    // swing carries you twice as far, 0.5 = half. Implemented by letting the
    // anchor RECEDE as the hand moves (the servo itself stays 1:1 and stable).
    public static double gtPushStrength = 1.0;

    // GORILLA TAG CLONE (locomotion mode 1) — Player.cs's own tuning, in Gorilla
    // Tag's units (1 block = 1 m). Defaults are the GorillaLocomotion values.
    //   gtJumpMultiplier  — throw = average body speed × this
    //   gtMaxJumpSpeed    — throw speed cap, m/s
    //   gtVelocityLimit   — slower than this (m/s) and touching never throws
    //   gtVelocityHistory — ticks averaged for the throw (GT: 3 frames ≈ 2 ticks)
    //   gtArmLength       — hands are clamped this far (m) from the head
    //   gtHandRadius      — hand collision sphere radius, m
    //   gtGravity         — m/s² (Minecraft's own is ~32; GT is real-world 9.81)
    //   gtGroundFriction  — horizontal speed kept per tick standing on the ground
    // gtUnstickDistance and gtIceSlip (hand slide on ice) are shared with it.
    public static double gtJumpMultiplier  = 1.1;
    public static double gtMaxJumpSpeed    = 6.5;
    public static double gtVelocityLimit   = 0.3;
    public static double gtVelocityHistory = 2;
    public static double gtArmLength       = 1.5;
    public static double gtHandRadius      = 0.05;
    public static double gtGravity         = 9.81;
    public static double gtGroundFriction  = 0.6;

    // REAL MONKE — gorilla size: caps the collision box HEIGHT at 0.5 blocks on
    // both client and server so you fit through 1-block tunnels. Height only —
    // width, model, camera scale and reach stay normal, and it no longer drops
    // the VR view (that desynced your own body model — use cameraHeightOffset
    // manually if you want the lower view). Only active while locomotion is ON.
    public static boolean realMonke = false;

    // CAMERA HEIGHT OFFSET — blocks to LOWER the VR view (and the hand models,
    // which share the same room origin). 0 = off.
    // TRADE-OFF: this shifts only the head/hands, not your own VR body model
    // (Vivecraft anchors that to your feet), so a non-zero value makes your own
    // body look stretched toward the floor. Real Monke does NOT use this (it
    // fits tunnels via the collision box alone); set it manually only if you
    // want the lower view and don't mind your own avatar looking squashed.
    public static double cameraHeightOffset = 0.0;

    // MONKE MODEL — the Gorilla Tag body look: player rendered WITHOUT legs and
    // with a shorter torso. Synced through monke-server so every mod user sees
    // every other opted-in player legless. Optional. true/false.
    public static boolean monkeModel = false;

    // Monke-model body part tuning (applies to every monke-model player YOU see).
    // Offsets in model pixels (1 px = 1/16 block; +Y is DOWN in model space),
    // rotations in degrees (full -360..360 allowed), scale as a multiplier.
    public static double modelTorsoOffsetY = 0.0;   // torso up/down (+down)
    public static double modelTorsoPitch   = -120.0; // torso lean (degrees) — only
                                                     // visible while monkeModel is on,
                                                     // so -120 IS the monke-look default
    public static double modelTorsoScaleY  = 0.75;  // torso height multiplier
    public static double modelArmsOffsetY  = 0.0;   // arms up/down (+down)
    public static double modelArmsPitch    = 0.0;   // arms rotation (degrees)
    public static double modelHeadOffsetY  = 0.0;   // head up/down (+down)
    public static double modelHeadPitch    = 0.0;   // head rotation (degrees)

    // CLAMP HAND MODELS — while a hand grips a block, draw Vivecraft's hand MODEL
    // at the surface point instead of letting it sink inside the block (like the
    // official Gorilla Tag hand followers). Purely visual — the physics always
    // uses the real tracked hand. true/false.
    public static boolean clampHandModels = true;

    // HAND SURFACE COLLISION — keep Vivecraft's hand MODEL from sinking into blocks
    // even when NOT gripping. Every tick each free hand is resolved out of any block
    // it's inside (pushed to the nearest surface) and swept last->current so a fast
    // swing/fall that tunnels a hand through a block is stopped at the entry face.
    // This is what keeps the non-pushing hand's model resting ON the ground instead of
    // buried in it. Purely visual (physics still uses the real tracked hand); requires
    // clampHandModels. true/false.
    public static boolean handSurfaceCollision = true;

    // EXPERIMENTAL — ICE FLOOR WALL LOGIC: treat grabbing an ICE FLOOR exactly
    // like an ice WALL (gravity on, no anchor glue, pure push-off momentum)
    // instead of the normal slippery-floor handling. Legacy physics only.
    // For testing — true/false.
    public static boolean iceFloorWallLogic = false;

    // EXPERIMENTAL — PUNCH MINING: break the block your hand touches, but only while
    // holding the tool MEANT for it AND only on ticks where you actually PUNCH it
    // (hand speed into the face above a threshold). Speed gates the damage, so a
    // gentle grab never mines — you have to strike the block. Off by default.
    // true/false.
    public static Boolean punchMining = false;

    // EXPERIMENTAL — PUNCH MINING THRESHOLD: minimum inward hand speed (blocks/tick,
    // measured relative to the head) that counts as a "punch" and deals block damage.
    // 0.0 = damage on the gentlest touch, higher = you must strike harder. Default 0.08.
    public static double punchMiningThreshold = 0.08;

    // EXPERIMENTAL — PUNCH MINING WITHOUT TOOLS: when true, punch mining no longer
    // requires the matching tool — any item (even bare hands) can break blocks by
    // punching, so hand SPEED alone decides. (Tool-required blocks still won't drop
    // items without the right tool, exactly like vanilla.) Default false.
    public static boolean punchMiningNoTool = false;

    // MAGMA TOUCH DAMAGE: grabbing a magma block (any face, including the sides) hurts
    // you, the same hot-floor damage as standing on one. Fire resistance negates it.
    // true/false.
    public static boolean magmaTouchDamage = true;

    // HOVERBOARD (hold the Hoverboard item in either hand). The board works like a
    // joystick: it thrusts out of its underside, so tilting it 45° forward drives
    // you forward, tilting it sideways drives you sideways, and holding it level
    // just hovers. Speed Swing locomotion mode only.
    public static boolean hoverboardEnabled = true;
    // Gap kept between your feet and the ground (blocks). Up to 4 in singleplayer
    // or with op; on servers it is held to 0.5 — above that they treat you as flying.
    public static double hoverHeight = 0.4;
    // Push added per tick at full tilt (45° or more), blocks/tick².
    public static double hoverAccel = 0.035;
    // Top board speed (blocks/tick). 0.7 = 14 blocks/sec.
    public static double hoverMaxSpeed = 0.7;
    // Speed kept each tick while riding. 1.0 = no drag, 0.9 = stops quickly.
    public static double hoverDrag = 0.98;
    // Tilt ignored around level (sine of the angle). 0.12 ≈ 7°.
    public static double hoverDeadzone = 0.12;
    // Minimum speed (blocks/tick) to start riding a wall. 0.45 = 9 blocks/sec.
    public static double hoverWallRideSpeed = 0.45;
    // Degrees added to the controller's pitch before reading tilt, so "level" can
    // match how you naturally hold the controller. + tips the board's nose down.
    public static double hoverTiltOffset = 0.0;

    // EXPERIMENTAL — VANILLA ICE FRICTION: when on, gripping/standing on ice makes you
    // skate with VANILLA ice physics (the block's own friction × 0.91 inertia, no speed
    // cap) instead of the mod's frictionless-but-capped ice. Floor/feet only — the
    // ice-WALL push-off is unchanged (legs have no wall analog). true/false.
    public static boolean vanillaIceFriction = false;

    // DEBUG LOGGING: when on, writes a focused trace of Vivecraft↔ViveMonkeCraft
    // interactions (teleport aim, room-origin re-sync, head-vs-body desync, grip state)
    // to logs/vivemonkecraft-debug.log. For diagnosing VR-only issues. true/false.
    public static boolean debugLogging = false;

    // ALLOW VIVECRAFT TELEPORT: by default the mod DISABLES Vivecraft's teleport while
    // gorilla locomotion is on, because teleporting desyncs the room origin and breaks
    // the hand physics. Turn this ON to keep teleport usable anyway (you accept the
    // post-teleport jank). When the mod is OFF, teleport always works regardless.
    // true/false.
    public static boolean allowTeleport = false;

    // =====================================================================
    // 1.0.0.3 FEATURES
    // =====================================================================

    // SURVIVE FATAL FALL — a "last stand": if a fall would KILL you and you were at
    // FULL health when you hit the ground, instead of dying you drop to 1 HP (half a
    // heart, near-death) and lose 3 food. Because you land below full it can't chain —
    // it only saves you again once you've healed all the way back up. true/false.
    // (Singleplayer / LAN host only from the client; a dedicated server needs the
    // companion mod to enforce it, like fall-damage suppression already does.)
    public static boolean surviveFatalFall = true;

    // DISABLE FALL DAMAGE — turn OFF fall damage entirely while the mod is on. Takes
    // precedence over surviveFatalFall (no damage at all, so nothing to survive).
    // true/false. (Same singleplayer/LAN caveat as above.)
    public static boolean disableFallDamage = false;

    // SINGLE FLOOR GRIP — only ONE hand may grip the FLOOR at a time. Walking with the
    // gorilla gait means alternating hands; if a trailing hand lightly brushes the
    // ground it anchors and stops you dead. With this on, while one hand holds the
    // floor the other hand's floor-touch is ignored (walls/climbing are unaffected —
    // two hands still work there), so walking flows without snagging. true/false.
    public static boolean singleFloorGrip = true;

    // GROUNDED GRIP RELEASE — fixes being wedged after jumping straight up with a hand
    // held in place: when you land back on the ground with a floor-anchored hand, the
    // anchor keeps dragging you down into the floor and you can't walk until you pull
    // the hand back. With this on, a FLOOR grip auto-releases once you're standing on
    // the ground again and no longer pulling yourself anywhere, so you're free to move
    // without retracting your hand. Walls/climbing are unaffected. true/false.
    public static boolean groundedGripRelease = true;

    // MOD TOGGLED — persisted on/off state of gorilla locomotion. When false, the
    // mod will NOT auto-enable on world join even if VR is active. Toggling the
    // keybind writes this to disk so the choice survives across sessions.
    public static boolean modToggled = true;

    // ACTIVE PRESET — the name of the last preset applied from the Mod Menu screen
    // (or "Default" on first run). The ModMenu reset buttons resolve their target
    // values from THIS preset, and the screen shows it so players know what "reset"
    // will do. Persisted like any other setting. Not a gameplay value.
    public static String activePreset = "Default";

    // COSMETICS — ids of the cosmetics you wear, comma-separated (e.g. pink_hat).
    // Empty = none. Edited from the Cosmetics page of the config screen.
    public static String cosmetics = "";


    public static final String[] PRESET_NAMES = {
        "Default", "tutorial", "Long Arms", "Zero Gravity", "Speed Run"
    };

    public static void applyPreset(String preset) {
        applyDefaultPreset();
        switch (preset) {
            case "Default":
                break;
            case "tutorial":
                pullStrength        = 2.5;
                groundFriction      = 1.0;
                airFriction         = 0.1;
                wallStickiness      = 1.0;
                floorStickiness     = 1.0;
                minImpulse          = 0.002;
                handReachMultiplier = 1.0;
                velocityLimit       = 0.05;
                jumpMultiplier      = 1.5;
                maxArmLength        = 3.0;
                handRadius          = 0.10;
                hitboxHeightScale   = 0.25;
                stepAssist          = true;
                stepTeleport        = true;
                stepHeight          = 1.5;
                maxJumpSpeed        = 1.0;
                velocityHistorySize = 6;
                gravityMultiplier   = 1.0;
                showHandMarkers     = true;
                monkeModel          = true;
                realMonke           = true;
                modelTorsoPitch     = -120.0;
                gtPhysics           = false;
                break;
            case "Long Arms":
                pullStrength        = 2.5;
                groundFriction      = 0.4;
                airFriction         = 0.1;
                wallStickiness      = 0.9;
                floorStickiness     = 1.0;
                minImpulse          = 0.002;
                handReachMultiplier = 3.5;
                velocityLimit       = 0.05;
                jumpMultiplier      = 1.8;
                maxJumpSpeed        = 1.5;
                velocityHistorySize = 5;
                gravityMultiplier   = 1.0;
                showHandMarkers     = true;
                monkeModel          = true;
                realMonke           = true;
                modelTorsoPitch     = -120.0;
                break;
            case "Zero Gravity":
                gravityMultiplier   = 0.1;
                airFriction         = 0.05;
                wallStickiness      = 1.0;
                floorStickiness     = 0.8;
                pullStrength        = 2.0;
                jumpMultiplier      = 2.0;
                maxJumpSpeed        = 2.5;
                groundFriction      = 0.9;
                monkeModel          = true;
                realMonke           = true;
                modelTorsoPitch     = -120.0;
                break;
            case "Speed Run":
                pullStrength        = 5.0;
                handReachMultiplier = 1.0;
                maxArmLength        = 1.5;
                airFriction         = 0.1;
                jumpMultiplier      = 3.5;
                maxJumpSpeed        = 3.0;
                velocityHistorySize = 4;
                groundFriction      = 0.7;
                gravityMultiplier   = 0.9;
                monkeModel          = true;
                realMonke           = true;
                modelTorsoPitch     = -120.0;
                break;
            default:
                break;
        }
    }

    // -----------------------------------------------------------------------
    // /vmc set <setting> <value> support — every public double/boolean field
    // above is settable in-game by its exact field name.
    // -----------------------------------------------------------------------

    /** All field names usable with /vmc set (tab-completion source). */
    public static java.util.List<String> settingNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : MovementConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && (f.getType() == double.class || f.getType() == boolean.class)) {
                names.add(f.getName());
            }
        }
        return names;
    }

    /**
     * Sets a config field by name from a string value and saves the file.
     * Returns a feedback string on success, or null if the name/value is invalid.
     */
    public static String setByName(String name, String value) {
        try {
            java.lang.reflect.Field f = MovementConfig.class.getField(name);
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) return null;
            if (f.getType() == double.class) {
                f.setDouble(null, Double.parseDouble(value.trim()));
            } else if (f.getType() == boolean.class) {
                String v = value.trim().toLowerCase(java.util.Locale.ROOT);
                if (!v.equals("true") && !v.equals("false")) return null;
                f.setBoolean(null, Boolean.parseBoolean(v));
            } else {
                return null;
            }
            save();
            return name + " = " + value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Loading / saving
    // -----------------------------------------------------------------------

    private static Path configPath() {
        return VmcPlatform.configDir().resolve("vivemonkecraft.properties");
    }

    // Applies the "Default" preset — the curated good-starting config. Lives HERE
    // (not in ModMenuIntegration) so it works with NO Mod Menu / Cloth Config
    // installed: that class imports Cloth types and can't be touched without them.
    // Applied automatically on first run and on a config-version reset, and the
    // Mod Menu "Default" preset delegates here so the values never drift apart.
    public static void applyDefaultPreset() {
        pullStrength        = 2.5;
        groundFriction      = 0.4;
        airFriction         = 0.1;
        wallStickiness      = 0.9;
        floorStickiness     = 1.0;
        minImpulse          = 0.002;
        handReachMultiplier = 1.0;
        velocityLimit       = 0.05;
        jumpMultiplier      = 1.8;
        maxArmLength        = 3.0;
        handRadius          = 0.10;
        hitboxHeightScale   = 0.25;
        stepAssist          = true;
        stepTeleport        = true;
        stepHeight          = 1.0;
        maxJumpSpeed        = 1.0;
        velocityHistorySize = 6;
        gravityMultiplier   = 1.0;
        showHandMarkers     = false;
        monkeModel          = true;
        realMonke           = true;
        modelTorsoPitch     = -120.0;
        gtPhysics           = false;
        locomotionMode      = 0;
        chargeMinLaunch     = 0.45;
        chargeMaxLaunch     = 1.10;
        punchMining         = true;
        punchMiningThreshold = 0.04;
        punchMiningNoTool   = true;
        magmaTouchDamage    = true;
        hoverboardEnabled   = true;
        hoverHeight         = 0.4;
        hoverAccel          = 0.035;
        hoverMaxSpeed       = 0.7;
        hoverDrag           = 0.98;
        hoverDeadzone       = 0.12;
        hoverWallRideSpeed  = 0.45;
        hoverTiltOffset     = 0.0;
        vanillaIceFriction  = false;
        debugLogging        = false;
        allowTeleport       = false;
        surviveFatalFall    = true;
        disableFallDamage   = false;
        singleFloorGrip     = true;
        groundedGripRelease = true;
        // Fields the flavour presets don't individually tune — set here too so
        // "Default" is a COMPLETE spec and every ModMenu reset button resolves to a
        // real value (not "whatever it happened to be").
        floorStickiness     = 1.0;
        maxArmLength        = 3.0;
        velocityHistorySize = 6;
        cameraStabEnabled   = true;
        cameraStabStrength  = 0.65;
        gripSmoothing       = 0.5;
        gtDragGain          = 0.30;
        gtUnstickDistance   = 1.0;
        gtIceSlip           = 0.95;
        gtPushStrength      = 1.0;
        gtJumpMultiplier    = 1.1;
        gtMaxJumpSpeed      = 6.5;
        gtVelocityLimit     = 0.3;
        gtVelocityHistory   = 2;
        gtArmLength         = 1.5;
        gtHandRadius        = 0.05;
        gtGravity           = 9.81;
        gtGroundFriction    = 0.6;
        cameraHeightOffset  = 0.0;
        clampHandModels     = true;
        handSurfaceCollision = true;
        iceFloorWallLogic   = false;
        modelTorsoOffsetY   = 0.0;
        modelTorsoScaleY    = 0.75;
        modelArmsOffsetY    = 0.0;
        modelArmsPitch      = 0.0;
        modelHeadOffsetY    = 0.0;
        modelHeadPitch      = 0.0;
    }

    // -----------------------------------------------------------------------
    // Snapshot / restore — capture every tunable field into a map and put it back.
    // Used by the Mod Menu screen to compute each reset button's target: it applies
    // the active preset to read its values, then restores the player's real values.
    // Reflection over the public static fields keeps this maintenance-free as
    // settings are added. Skips the private version constant (not public).
    // -----------------------------------------------------------------------

    public static java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (java.lang.reflect.Field f : MovementConfig.class.getFields()) {
            int mod = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod) && !java.lang.reflect.Modifier.isFinal(mod)) {
                try { m.put(f.getName(), f.get(null)); } catch (IllegalAccessException ignored) {}
            }
        }
        return m;
    }

    public static void restore(java.util.Map<String, Object> snapshot) {
        for (java.lang.reflect.Field f : MovementConfig.class.getFields()) {
            Object v = snapshot.get(f.getName());
            if (v == null) continue;
            try { f.set(null, v); } catch (IllegalAccessException ignored) {}
        }
    }

    // Reads the file into the fields above. Safe to call repeatedly. Never throws.
    public static void load() {
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(path)) {
                    p.load(r);
                }

                // Old file version? Ignore its values and apply the curated Default
                // preset (then re-save below), so improved defaults actually take effect.
                int savedVersion = (int) parseD(p, "configVersion", 0);
                if (savedVersion < CURRENT_CONFIG_VERSION) {
                    System.out.println("[ViveMonkeCraft] Config is old (v" + savedVersion
                        + "); resetting to v" + CURRENT_CONFIG_VERSION + " defaults.");
                    applyDefaultPreset();
                } else {
                    maxArmLength        = parseD(p, "maxArmLength",        maxArmLength);
                    handReachMultiplier = parseD(p, "handReachMultiplier", handReachMultiplier);
                    handRadius          = parseD(p, "handRadius",          handRadius);
                    pullStrength        = parseD(p, "pullStrength",        pullStrength);
                    minImpulse          = parseD(p, "minImpulse",          minImpulse);
                    groundFriction      = parseD(p, "groundFriction",      groundFriction);
                    velocityLimit       = parseD(p, "velocityLimit",       velocityLimit);
                    jumpMultiplier      = parseD(p, "jumpMultiplier",      jumpMultiplier);
                    maxJumpSpeed        = parseD(p, "maxJumpSpeed",        maxJumpSpeed);
                    velocityHistorySize = parseD(p, "velocityHistorySize", velocityHistorySize);
                    stepAssist          = parseB(p, "stepAssist",          stepAssist);
                    stepHeight          = parseD(p, "stepHeight",          stepHeight);
                    hitboxHeightScale   = parseD(p, "hitboxHeightScale",   hitboxHeightScale);
                    showHandMarkers     = parseB(p, "showHandMarkers",     showHandMarkers);
                    wallStickiness      = parseD(p, "wallStickiness",      wallStickiness);
                    floorStickiness     = parseD(p, "floorStickiness",     floorStickiness);
                    airFriction         = parseD(p, "airFriction",         airFriction);
                    gravityMultiplier   = parseD(p, "gravityMultiplier",   gravityMultiplier);
                    cameraStabEnabled   = parseB(p, "cameraStabEnabled",   cameraStabEnabled);
                    cameraStabStrength  = parseD(p, "cameraStabStrength",  cameraStabStrength);
                    gripSmoothing       = parseD(p, "gripSmoothing",       gripSmoothing);
                    gtPhysics           = parseB(p, "gtPhysics",           gtPhysics);
                    if (p.getProperty("locomotionMode") == null) {
                        locomotionMode = gtPhysics ? 1 : 0;
                    } else {
                        locomotionMode = parseD(p, "locomotionMode", locomotionMode);
                    }
                    chargeMinLaunch     = parseD(p, "chargeMinLaunch",     chargeMinLaunch);
                    chargeMaxLaunch     = parseD(p, "chargeMaxLaunch",     chargeMaxLaunch);
                    stepTeleport        = parseB(p, "stepTeleport",        stepTeleport);
                    gtDragGain          = parseD(p, "gtDragGain",          gtDragGain);
                    gtUnstickDistance   = parseD(p, "gtUnstickDistance",   gtUnstickDistance);
                    gtIceSlip           = parseD(p, "gtIceSlip",           gtIceSlip);
                    gtPushStrength      = parseD(p, "gtPushStrength",      gtPushStrength);
                    gtJumpMultiplier    = parseD(p, "gtJumpMultiplier",    gtJumpMultiplier);
                    gtMaxJumpSpeed      = parseD(p, "gtMaxJumpSpeed",      gtMaxJumpSpeed);
                    gtVelocityLimit     = parseD(p, "gtVelocityLimit",     gtVelocityLimit);
                    gtVelocityHistory   = parseD(p, "gtVelocityHistory",   gtVelocityHistory);
                    gtArmLength         = parseD(p, "gtArmLength",         gtArmLength);
                    gtHandRadius        = parseD(p, "gtHandRadius",        gtHandRadius);
                    gtGravity           = parseD(p, "gtGravity",           gtGravity);
                    gtGroundFriction    = parseD(p, "gtGroundFriction",    gtGroundFriction);
                    realMonke           = parseB(p, "realMonke",           realMonke);
                    cameraHeightOffset  = parseD(p, "cameraHeightOffset",  cameraHeightOffset);
                    monkeModel          = parseB(p, "monkeModel",          monkeModel);
                    modelTorsoOffsetY   = parseD(p, "modelTorsoOffsetY",   modelTorsoOffsetY);
                    modelTorsoPitch     = parseD(p, "modelTorsoPitch",     modelTorsoPitch);
                    modelTorsoScaleY    = parseD(p, "modelTorsoScaleY",    modelTorsoScaleY);
                    modelArmsOffsetY    = parseD(p, "modelArmsOffsetY",    modelArmsOffsetY);
                    modelArmsPitch      = parseD(p, "modelArmsPitch",      modelArmsPitch);
                    modelHeadOffsetY    = parseD(p, "modelHeadOffsetY",    modelHeadOffsetY);
                    modelHeadPitch      = parseD(p, "modelHeadPitch",      modelHeadPitch);
                    iceFloorWallLogic   = parseB(p, "iceFloorWallLogic",   iceFloorWallLogic);
                    punchMining         = parseB(p, "punchMining",         punchMining);
                    punchMiningThreshold = parseD(p, "punchMiningThreshold", punchMiningThreshold);
                    punchMiningNoTool   = parseB(p, "punchMiningNoTool",   punchMiningNoTool);
                    magmaTouchDamage    = parseB(p, "magmaTouchDamage",    magmaTouchDamage);
                    hoverboardEnabled   = parseB(p, "hoverboardEnabled",   hoverboardEnabled);
                    hoverHeight         = parseD(p, "hoverHeight",         hoverHeight);
                    hoverAccel          = parseD(p, "hoverAccel",          hoverAccel);
                    hoverMaxSpeed       = parseD(p, "hoverMaxSpeed",       hoverMaxSpeed);
                    hoverDrag           = parseD(p, "hoverDrag",           hoverDrag);
                    hoverDeadzone       = parseD(p, "hoverDeadzone",       hoverDeadzone);
                    hoverWallRideSpeed  = parseD(p, "hoverWallRideSpeed",  hoverWallRideSpeed);
                    hoverTiltOffset     = parseD(p, "hoverTiltOffset",     hoverTiltOffset);
                    vanillaIceFriction  = parseB(p, "vanillaIceFriction",  vanillaIceFriction);
                    debugLogging        = parseB(p, "debugLogging",        debugLogging);
                    allowTeleport       = parseB(p, "allowTeleport",       allowTeleport);
                    clampHandModels     = parseB(p, "clampHandModels",     clampHandModels);
                    handSurfaceCollision = parseB(p, "handSurfaceCollision", handSurfaceCollision);
                    surviveFatalFall    = parseB(p, "surviveFatalFall",    surviveFatalFall);
                    disableFallDamage   = parseB(p, "disableFallDamage",   disableFallDamage);
                    singleFloorGrip     = parseB(p, "singleFloorGrip",     singleFloorGrip);
                    groundedGripRelease = parseB(p, "groundedGripRelease", groundedGripRelease);
                    modToggled          = parseB(p, "modToggled",          modToggled);
                    activePreset        = p.getProperty("activePreset", activePreset).trim();
                }
                // Cosmetics aren't a tuning value, so they survive a config-version reset.
                cosmetics = p.getProperty("cosmetics", cosmetics).trim();
            } else {
                // FIRST RUN — no config file yet. Start from the curated Default
                // preset instead of the bare field initializers, then save it.
                System.out.println("[ViveMonkeCraft] First run — applying Default preset.");
                applyDefaultPreset();
            }
        } catch (Exception e) {
            System.out.println("[ViveMonkeCraft] Could not read config, using defaults: " + e);
        }
        save();
    }

    // Writes the current values back to the file WITH explanatory comments.
    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());

            StringBuilder sb = new StringBuilder();
            sb.append("# ============================================================\n");
            sb.append("# ViveMonkeCraft - Gorilla Tag locomotion settings\n");
            sb.append("# Edit a value, save, then run /vmc reload in-game (or toggle off+on).\n");
            sb.append("# Lines starting with '#' are comments and are ignored.\n");
            sb.append("# ============================================================\n\n");

            sb.append("# Internal version stamp. Don't edit. A lower number resets the file.\n");
            sb.append("configVersion=").append(CURRENT_CONFIG_VERSION).append("\n\n");

            sb.append("# Max hand reach from your head (blocks). Must be > ~1.6 to reach\n");
            sb.append("# the ground. Bigger = longer arms.\n");
            sb.append("maxArmLength=").append(maxArmLength).append("\n\n");

            sb.append("# Length of your gorilla arms: effective hand reaches this x further\n");
            sb.append("# from your head than your real hand, so you touch the ground without\n");
            sb.append("# reaching far. 1.0 = real, 2.5 = long (default), 3.0 = very long.\n");
            sb.append("# Does NOT affect movement feel (swing is measured on your real hand).\n");
            sb.append("handReachMultiplier=").append(handReachMultiplier).append("\n\n");

            sb.append("# Hand touch-sphere radius (blocks). Bigger = easier to grab.\n");
            sb.append("handRadius=").append(handRadius).append("\n\n");

            sb.append("# PUSH SPEED: how strongly your hand swing becomes movement. 1.0 = 1:1,\n");
            sb.append("# 2.0 = twice as much (default), 3.0+ = strong. Crank up if pulling\n");
            sb.append("# yourself feels too weak.\n");
            sb.append("pullStrength=").append(pullStrength).append("\n\n");

            sb.append("# Movements slower than this (blocks/tick) are ignored as jitter, so a\n");
            sb.append("# resting hand produces zero movement (no creeping).\n");
            sb.append("minImpulse=").append(minImpulse).append("\n\n");

            sb.append("# GROUND FRICTION: stops you sliding on the ground when not gripping.\n");
            sb.append("# 1.0 = slide forever, 0.5 = stops fast (default), 0.2 = near-instant.\n");
            sb.append("# Ice surfaces ignore this (always frictionless).\n");
            sb.append("groundFriction=").append(groundFriction).append("\n\n");

            sb.append("# Jump threshold: when you let go you only launch if your body was\n");
            sb.append("# moving faster than this (blocks/tick). Higher = need a faster swing.\n");
            sb.append("velocityLimit=").append(velocityLimit).append("\n\n");

            sb.append("# Built-up speed multiplied into the launch. 1.0 = release speed,\n");
            sb.append("# 1.4 = a boost. (Gorilla Tag jumpMultiplier.)\n");
            sb.append("jumpMultiplier=").append(jumpMultiplier).append("\n\n");

            sb.append("# Hard cap on launch speed (blocks/tick). 1.0 = 20 blocks/sec.\n");
            sb.append("maxJumpSpeed=").append(maxJumpSpeed).append("\n\n");

            sb.append("# Ticks of movement averaged for the throw. More = smoother/laggier.\n");
            sb.append("velocityHistorySize=").append(velocityHistorySize).append("\n\n");

            sb.append("# Step assist: raise step height while the mod is on. true/false.\n");
            sb.append("stepAssist=").append(stepAssist).append("\n\n");

            sb.append("# Ledge height you can step over (blocks). 0.6 = vanilla, 1.0 = full block.\n");
            sb.append("stepHeight=").append(stepHeight).append("\n\n");

            sb.append("# 'Delete the legs': shrink ONLY the collision box height (not model/\n");
            sb.append("# camera) so your lower body stops catching on blocks when climbing.\n");
            sb.append("# 1.0 = full height, 0.5 = half (compact, climbs easily), 0.35 = tiny.\n");
            sb.append("hitboxHeightScale=").append(hitboxHeightScale).append("\n\n");

            sb.append("# Hand markers: render split arm lines at grab points. true/false.\n");
            sb.append("showHandMarkers=").append(showHandMarkers).append("\n\n");

            sb.append("# WALL STICKINESS: how hard your hands cling to the SIDE faces of blocks.\n");
            sb.append("# (Hands planted on TOP of a block always hold you - see floorStickiness.)\n");
            sb.append("#   1.0 = full stick, hang in place forever (default, best for climbing)\n");
            sb.append("#   0.5 = constantly slide down the wall at half rate (no hovering)\n");
            sb.append("#   0.0 = full slide - walls can be pushed off but never rested on\n");
            sb.append("# Lower it if you stick too well; raise it to cling harder.\n");
            sb.append("wallStickiness=").append(wallStickiness).append("\n\n");

            sb.append("# FLOOR STICKINESS: how much you SLIDE across the ground while a hand is\n");
            sb.append("# planted on the floor (reaching down).\n");
            sb.append("#   1.0 = stop dead, hand sticks to the spot, no slide (default)\n");
            sb.append("#   0.5 = some glide after a push\n");
            sb.append("#   0.0 = keep all momentum (ice-like, slides a long way)\n");
            sb.append("# Applies WHILE gripping the floor (ground friction applies after letting go).\n");
            sb.append("floorStickiness=").append(floorStickiness).append("\n\n");

            sb.append("# AIR FRICTION: how much your horizontal speed bleeds off while airborne and\n");
            sb.append("# NOT gripping a surface.\n");
            sb.append("#   1.0 = normal Minecraft drag (~0.91x horizontal speed per tick)\n");
            sb.append("#   0.5 = half the drag (carries momentum further after a throw)\n");
            sb.append("#   0.0 = no drag at all (speed preserved indefinitely)\n");
            sb.append("# Lower to make throws carry farther and feel more floaty.\n");
            sb.append("airFriction=").append(airFriction).append("\n\n");

            sb.append("# GRAVITY MULTIPLIER: how strongly gravity pulls you while NOT gripping.\n");
            sb.append("#   1.0 = normal Minecraft gravity (default)\n");
            sb.append("#   0.5 = half gravity (slow fall, longer jumps)\n");
            sb.append("#   0.0 = zero gravity (float in place after releasing a surface)\n");
            sb.append("# Change in-game with: /vmc gravity <0-1>  (requires operator access).\n");
            sb.append("gravityMultiplier=").append(gravityMultiplier).append("\n\n");

            sb.append("# CAMERA STABILIZATION: shows a vignette (black border narrowing the view)\n");
            sb.append("# while moving fast. Reduces motion sickness during locomotion. true/false.\n");
            sb.append("cameraStabEnabled=").append(cameraStabEnabled).append("\n\n");

            sb.append("# How strong the vignette is at max speed. 0.0 = off, 0.65 = moderate\n");
            sb.append("# (default), 1.0 = very strong tunnel vision.\n");
            sb.append("cameraStabStrength=").append(cameraStabStrength).append("\n\n");

            sb.append("# GRIP SMOOTHING: low-pass filter on locomotion velocity to remove VR\n");
            sb.append("# controller tracking noise (the tiny jitter that causes motion sickness).\n");
            sb.append("#   0.0 = no smoothing (raw, old behaviour)\n");
            sb.append("#   0.5 = 50/50 blend per tick (default, ~67% noise reduction, snappy feel)\n");
            sb.append("#   0.8 = heavy smoothing, very steady but slightly lazy response\n");
            sb.append("# The throw/launch on release is unaffected (uses raw velocity).\n");
            sb.append("gripSmoothing=").append(gripSmoothing).append("\n\n");

            sb.append("# GORILLA TAG PHYSICS (anchor mode) - HIDDEN / UNSUPPORTED:\n");
            sb.append("#   true  = official GorillaLocomotion algorithm: a hand ANCHORS to the\n");
            sb.append("#           spot it touches and your body is dragged 1:1 so the hand stays\n");
            sb.append("#           planted. pullStrength + stickiness settings are ignored.\n");
            sb.append("#   false = LEGACY speed-based model (swing speed x pullStrength). DEFAULT.\n");
            sb.append("# No longer shown in the in-game config screen - legacy is the supported\n");
            sb.append("# motor. The GT engine is still in the mod, so you can flip this by hand\n");
            sb.append("# to test it, but it gets no tuning UI and no support.\n");
            sb.append("gtPhysics=").append(gtPhysics).append("\n\n");

            sb.append("# LOCOMOTION MODE: 0=Speed Swing, 1=Gorilla Tag clone, 2=Charge Jump\n");
            sb.append("locomotionMode=").append(locomotionMode).append("\n\n");

            sb.append("# CHARGE JUMP (mode 2): launch speed for a tap vs full ~1.5s hold\n");
            sb.append("chargeMinLaunch=").append(chargeMinLaunch).append("\n");
            sb.append("chargeMaxLaunch=").append(chargeMaxLaunch).append("\n\n");

            sb.append("# STEP TELEPORT: how step assist lifts you over ledges while airborne.\n");
            sb.append("#   true  = place you directly on top of the block (instant)\n");
            sb.append("#   false = old upward velocity boost (arcs you over)\n");
            sb.append("stepTeleport=").append(stepTeleport).append("\n\n");

            sb.append("# GT DRAG GAIN (anchor mode): fraction of the distance to the anchor\n");
            sb.append("# corrected per tick. Keep below 1.0 (overshoot/oscillation otherwise).\n");
            sb.append("# 0.45 default, 0.6 = snappier, 0.3 = softer.\n");
            sb.append("gtDragGain=").append(gtDragGain).append("\n\n");

            sb.append("# GT UNSTICK DISTANCE (anchor mode): how far (blocks) a hand may stray\n");
            sb.append("# from its anchor before the grip releases. Official GT uses 1.0.\n");
            sb.append("gtUnstickDistance=").append(gtUnstickDistance).append("\n\n");

            sb.append("# GT ICE SLIP (anchor mode): how fast the anchor drifts toward the hand\n");
            sb.append("# on ice. 0 = ice grips like stone, 0.95 = push off only (default).\n");
            sb.append("gtIceSlip=").append(gtIceSlip).append("\n\n");

            sb.append("# GT PUSH STRENGTH (anchor mode): body movement = hand movement x this.\n");
            sb.append("# 1.0 = authentic Gorilla Tag 1:1, 2.0 = twice as far, 0.5 = half.\n");
            sb.append("gtPushStrength=").append(gtPushStrength).append("\n\n");

            sb.append("# GORILLA TAG CLONE (mode 1): Player.cs tuning in GT units (1 block = 1 m).\n");
            sb.append("# Defaults are the official GorillaLocomotion values.\n");
            sb.append("gtJumpMultiplier=").append(gtJumpMultiplier).append("\n");
            sb.append("gtMaxJumpSpeed=").append(gtMaxJumpSpeed).append("\n");
            sb.append("gtVelocityLimit=").append(gtVelocityLimit).append("\n");
            sb.append("gtVelocityHistory=").append(gtVelocityHistory).append("\n");
            sb.append("gtArmLength=").append(gtArmLength).append("\n");
            sb.append("gtHandRadius=").append(gtHandRadius).append("\n");
            sb.append("gtGravity=").append(gtGravity).append("\n");
            sb.append("gtGroundFriction=").append(gtGroundFriction).append("\n\n");

            sb.append("# REAL MONKE: gorilla size - 0.5-block-tall collision box on client AND\n");
            sb.append("# server so you fit 1-block tunnels. Height only - no view drop. true/false.\n");
            sb.append("realMonke=").append(realMonke).append("\n\n");

            sb.append("# CAMERA HEIGHT OFFSET: blocks to LOWER the VR view + hands. Note: makes\n");
            sb.append("# your OWN body model look squashed (it stays anchored to your feet).\n");
            sb.append("# 0 = off. Overridden by realMonke.\n");
            sb.append("cameraHeightOffset=").append(cameraHeightOffset).append("\n\n");

            sb.append("# MONKE MODEL: render players WITHOUT legs and with a shorter torso\n");
            sb.append("# (the Gorilla Tag look). Synced via monke-server. true/false.\n");
            sb.append("monkeModel=").append(monkeModel).append("\n\n");

            sb.append("# Monke-model body part tuning (offsets in model pixels, +Y is DOWN;\n");
            sb.append("# rotations in degrees, -360..360; scale is a multiplier).\n");
            sb.append("modelTorsoOffsetY=").append(modelTorsoOffsetY).append("\n");
            sb.append("modelTorsoPitch=").append(modelTorsoPitch).append("\n");
            sb.append("modelTorsoScaleY=").append(modelTorsoScaleY).append("\n");
            sb.append("modelArmsOffsetY=").append(modelArmsOffsetY).append("\n");
            sb.append("modelArmsPitch=").append(modelArmsPitch).append("\n");
            sb.append("modelHeadOffsetY=").append(modelHeadOffsetY).append("\n");
            sb.append("modelHeadPitch=").append(modelHeadPitch).append("\n\n");

            sb.append("# CLAMP HAND MODELS: while gripping, draw the Vivecraft hand model ON the\n");
            sb.append("# block surface instead of inside it (like Gorilla Tag's hand followers).\n");
            sb.append("# Purely visual - physics always uses the real hand. true/false.\n");
            sb.append("clampHandModels=").append(clampHandModels).append("\n");
            sb.append("# Keep the non-gripping hand model out of blocks (rests on the surface, never sinks in). true/false.\n");
            sb.append("handSurfaceCollision=").append(handSurfaceCollision).append("\n\n");

            sb.append("# EXPERIMENTAL - ICE FLOOR WALL LOGIC: treat ice FLOOR grabs exactly like\n");
            sb.append("# ice WALLS (gravity on, no anchor glue, pure push-off). Legacy physics\n");
            sb.append("# only. For testing. true/false.\n");
            sb.append("iceFloorWallLogic=").append(iceFloorWallLogic).append("\n\n");

            sb.append("# EXPERIMENTAL - PUNCH MINING: strike a block with the tool meant for it\n");
            sb.append("# (hand speed gates the damage) to mine it. Off by default. true/false.\n");
            sb.append("punchMining=").append(punchMining).append("\n");
            sb.append("# Min inward hand speed (blocks/tick) that counts as a punch. 0 = touch.\n");
            sb.append("punchMiningThreshold=").append(punchMiningThreshold).append("\n");
            sb.append("# Punch mining without the matching tool (any item / bare hands). true/false.\n");
            sb.append("punchMiningNoTool=").append(punchMiningNoTool).append("\n\n");
            sb.append("# Grabbing a magma block (any face) hurts you like standing on it. true/false.\n");
            sb.append("magmaTouchDamage=").append(magmaTouchDamage).append("\n\n");

            sb.append("# HOVERBOARD (hold the Hoverboard item). Tilt the board like a joystick:\n");
            sb.append("# it pushes out of its underside, so 45 degrees forward = full speed ahead.\n");
            sb.append("# Speed Swing locomotion mode only. true/false.\n");
            sb.append("hoverboardEnabled=").append(hoverboardEnabled).append("\n");
            sb.append("# Gap between feet and ground (blocks). Up to 4 in singleplayer / with op; 0.5 on servers (they kick flyers).\n");
            sb.append("hoverHeight=").append(hoverHeight).append("\n");
            sb.append("# Push per tick at full tilt (blocks/tick^2).\n");
            sb.append("hoverAccel=").append(hoverAccel).append("\n");
            sb.append("# Top board speed (blocks/tick). 0.7 = 14 blocks/sec.\n");
            sb.append("hoverMaxSpeed=").append(hoverMaxSpeed).append("\n");
            sb.append("# Speed kept per tick while riding. 1.0 = no drag, 0.9 = stops fast.\n");
            sb.append("hoverDrag=").append(hoverDrag).append("\n");
            sb.append("# Tilt ignored around level (sine of angle). 0.12 = about 7 degrees.\n");
            sb.append("hoverDeadzone=").append(hoverDeadzone).append("\n");
            sb.append("# Speed needed to start riding a wall (blocks/tick). 0.45 = 9 blocks/sec.\n");
            sb.append("hoverWallRideSpeed=").append(hoverWallRideSpeed).append("\n");
            sb.append("# Degrees added to controller pitch so 'level' matches your grip.\n");
            sb.append("hoverTiltOffset=").append(hoverTiltOffset).append("\n\n");
            sb.append("# EXPERIMENTAL - vanilla ice friction on hands/feet (skate physics). true/false.\n");
            sb.append("vanillaIceFriction=").append(vanillaIceFriction).append("\n\n");
            sb.append("# Write a focused Vivecraft-interaction trace to logs/vivemonkecraft-debug.log. true/false.\n");
            sb.append("debugLogging=").append(debugLogging).append("\n\n");
            sb.append("# Keep Vivecraft teleport usable while the mod is on (it desyncs physics). true/false.\n");
            sb.append("allowTeleport=").append(allowTeleport).append("\n\n");

            sb.append("# Survive an otherwise-fatal fall at 1 HP / half a heart (−3 food) if you were at full\n");
            sb.append("# health. Self-limiting (can't chain). true/false.\n");
            sb.append("surviveFatalFall=").append(surviveFatalFall).append("\n\n");
            sb.append("# Disable fall damage entirely while the mod is on. Overrides surviveFatalFall. true/false.\n");
            sb.append("disableFallDamage=").append(disableFallDamage).append("\n\n");
            sb.append("# Only one hand can grip the FLOOR at a time — smoother walking (walls unaffected). true/false.\n");
            sb.append("singleFloorGrip=").append(singleFloorGrip).append("\n\n");
            sb.append("# Auto-release a floor grip once you're grounded again so you're not wedged after\n");
            sb.append("# jumping straight up with your hand held in place. true/false.\n");
            sb.append("groundedGripRelease=").append(groundedGripRelease).append("\n\n");

            sb.append("# Whether gorilla locomotion is toggled on. Persists across sessions — if you\n");
            sb.append("# turn it off with the keybind, it stays off until you turn it back on.\n");
            sb.append("modToggled=").append(modToggled).append("\n\n");

            sb.append("# The preset the Mod Menu 'reset' buttons revert to (set by picking a preset\n");
            sb.append("# in the config screen). One of: Default, tutorial, Long Arms, Zero Gravity,\n");
            sb.append("# Speed Run. Not a gameplay value.\n");
            sb.append("activePreset=").append(activePreset).append("\n\n");
            sb.append("# Cosmetics you wear, comma-separated ids (e.g. pink_hat). Empty = none.\n");
            sb.append("cosmetics=").append(cosmetics).append("\n");

            Files.writeString(path, sb.toString());
        } catch (Exception e) {
            System.out.println("[ViveMonkeCraft] Could not write config: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Tiny parse helpers — return the default if the value is missing or invalid
    // -----------------------------------------------------------------------

    private static double parseD(Properties p, String key, double def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseB(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }

    private MovementConfig() {} // no instances — this is a static holder
}
