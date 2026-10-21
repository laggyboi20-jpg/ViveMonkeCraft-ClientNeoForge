package laggyboi.vivemonkecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.phys.Vec3;

// =====================================================================
// HAND MARKER RENDERER (particle-based)
// =====================================================================
//
// Shows where each hand grabs: a coloured dot at the grab point plus a sparse
// line of dots back to the shoulder.
//   GREEN  = the hand is gripping a block
//   RED    = the hand is free
//   YELLOW = wall-sliding (a no-fall-damage slide) — overrides green/red
//
// WHY PARTICLES: the original drew crisp lines + a wireframe cube straight into
// the world render pipeline via Fabric's WorldRenderEvents. That hook was removed
// in the 1.21.9 render rework (and 26.2 removed MultiBufferSource too), and there
// is no replacement world-render event. Client-side dust particles are render-API
// stable across every version, need no pipeline access, and are purely local
// (never sent to the server), so they work on any server. The trade-off is the
// marker is a soft glowing dot/trail rather than a crisp line + cube.
//
// State fields are written once per game tick by GorillaLocomotionHandler; emit()
// is called right after, also once per tick.
// =====================================================================

public final class HandMarkerRenderer {

    private HandMarkerRenderer() {}

    // Dots spawned along each shoulder→grab arm (plus the grab-point dot itself).
    private static final int ARM_SEGMENTS = 4;
    // Dust size. ~1.0 reads clearly in VR without swamping the view.
    private static final float DOT_SCALE = 1.0f;

    // =========================================================================
    // Tick-to-frame state — written by GorillaLocomotionHandler
    // =========================================================================

    // Shoulder joint positions (world-space), computed from headPositon + player yaw
    // each tick so they track the player model's arm sockets.
    public static Vec3    shoulderMain = null;
    public static Vec3    shoulderOff  = null;

    // Grab / touch points — where the hand hitbox currently lands.
    public static Vec3    grabMain     = null;
    public static Vec3    grabOff      = null;

    public static boolean grippingMain = false;
    public static boolean grippingOff  = false;

    // True while sliding down a wall — tints BOTH markers yellow (overrides the
    // usual green/red), the visual cue that you're in a no-fall-damage slide.
    public static boolean sliding      = false;

    public static void clearState() {
        shoulderMain = shoulderOff = grabMain = grabOff = null;
        grippingMain = grippingOff = false;
        sliding = false;
    }

    // =========================================================================
    // Emit — called once per game tick by GorillaLocomotionHandler after it has
    // updated the state above. Spawns the marker particles for this frame.
    // =========================================================================

    public static void emit(Minecraft client) {
        if (!VivemonkecraftClient.isEnabled()) return;
        if (!MovementConfig.showHandMarkers) return;
        ClientLevel level = client.level;
        if (level == null) return;

        emitHand(level, shoulderMain, grabMain, grippingMain);
        emitHand(level, shoulderOff,  grabOff,  grippingOff);
    }

    // A grab-point dot + a few evenly spaced dots up the arm toward the shoulder.
    private static void emitHand(ClientLevel level, Vec3 shoulder, Vec3 grab, boolean gripping) {
        if (grab == null) return;
        // 0xRRGGBB: yellow while sliding, else green (gripping) / red (free).
        int color = sliding ? 0xFFFF00 : (gripping ? 0x00FF00 : 0xFF0000);
        DustParticleOptions dust = new DustParticleOptions(color, DOT_SCALE);

        // Grab point. force=true so it shows even on "minimal" particle settings;
        // zero velocity so it stays put at the grab spot.
        level.addParticle(dust, true, false, grab.x, grab.y, grab.z, 0.0, 0.0, 0.0);

        // Sparse arm line: interpolate a few points between shoulder and grab.
        if (shoulder != null) {
            for (int i = 1; i < ARM_SEGMENTS; i++) {
                double t = i / (double) ARM_SEGMENTS;
                level.addParticle(dust, true, false,
                        shoulder.x + (grab.x - shoulder.x) * t,
                        shoulder.y + (grab.y - shoulder.y) * t,
                        shoulder.z + (grab.z - shoulder.z) * t,
                        0.0, 0.0, 0.0);
            }
        }
    }
}
