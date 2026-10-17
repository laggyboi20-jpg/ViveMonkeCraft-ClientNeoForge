package laggyboi.vivemonkecraft.client;

import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Thin, dependency-free adapter over Vivecraft's VR API.
 *
 * <p>Supports two wiring paths, tried in order:
 * <ol>
 *   <li><b>PUBLIC (Vivecraft 1.3.x)</b> — {@code org.vivecraft.api.client.VRClientAPI},
 *       the stable public API introduced in 1.3.0. Used for 1.21.8+ (PCVR only).
 *   <li><b>LEGACY (Vivecraft 1.2.x / QuestCraft / QCXR)</b> — internal
 *       {@code org.vivecraft.client_vr.*} reflection. Used for 1.21.4/1.21.5 + QuestCraft.
 * </ol>
 *
 * <p>Vivecraft is NOT a compile-time dependency — reflection keeps the mod buildable
 * and runnable without Vivecraft on the classpath, degrading gracefully to MISSING.
 */
public final class VivecraftBridge {

    // -----------------------------------------------------------------------
    // Static singleton — lets renderer classes call VivecraftBridge.isVrActive()
    // without needing their own instance.
    // -----------------------------------------------------------------------

    private static final VivecraftBridge INSTANCE = new VivecraftBridge();

    /** True when Vivecraft is present AND VR is currently active. Never throws. */
    public static boolean isVrActive() {
        return INSTANCE.isVRActive();
    }

    /**
     * Enable/disable Vivecraft's teleport movement at runtime.
     * We disable it while gorilla locomotion is active so the teleport button can't
     * desync the room origin. No-op if Vivecraft is absent.
     */
    public static void setTeleportDisabled(boolean disabled) {
        INSTANCE.setTeleportOverride(!disabled);
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    private enum Link { UNTRIED, PUBLIC, LEGACY, MISSING }
    private Link link = Link.UNTRIED;

    // -----------------------------------------------------------------------
    // Public API handles (Vivecraft 1.3.x — org.vivecraft.api.*)
    // -----------------------------------------------------------------------

    private Object pubApi;          // VRClientAPI singleton instance
    private Method mPubIsVRActive;  // VRClientAPI#isVRActive()
    private Method mPubGetPose;     // VRClientAPI#getWorldRenderPose() → VRPose
    private Method mPubGetMain;     // VRPose#getMainHand() → VRBodyPartData
    private Method mPubGetOff;      // VRPose#getOffHand()  → VRBodyPartData
    private Method mPubGetHead;     // VRPose#getHead()     → VRBodyPartData
    private Method mPubGetPos;      // VRBodyPartData#getPos() → Vec3

    // -----------------------------------------------------------------------
    // Legacy API handles (Vivecraft 1.2.x / QCXR — org.vivecraft.client_vr.*)
    // -----------------------------------------------------------------------

    private Field  fVrRunning;     // VRState.VR_RUNNING  (static boolean)
    private Method mVrPlayerGet;   // VRPlayer.get()      (static → VRPlayer)
    private Field  fVrDataRender;  // VRPlayer#vrdata_world_render (VRData)
    private Field  fHmd;           // VRData#hmd          (VRDevicePose = head)
    private Field  fC0;            // VRData#c0           (VRDevicePose = main hand)
    private Field  fC1;            // VRData#c1           (VRDevicePose = off hand)
    private Method mGetPosition;   // VRDevicePose#getPosition() → Vec3

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /** @return true only when Vivecraft is present AND VR is currently active. */
    public boolean isVRActive() {
        return switch (tryWire()) {
            case PUBLIC -> pubIsVRActive();
            case LEGACY -> legacyIsVRActive();
            default -> false;
        };
    }

    /** World-space position of the right/main-hand controller, or null. */
    public Vec3 getMainHandPos() {
        // While the hand-model clamp is active, the c0 pose has been swapped to the
        // SURFACE point for rendering — physics must keep seeing the real hand.
        if (VrHandClamp.clampMain != null && VrHandClamp.rawMain != null) {
            return VrHandClamp.rawMain;
        }
        return switch (tryWire()) {
            case PUBLIC -> pubPosition(mPubGetMain);
            case LEGACY -> legacyPosition(fC0);
            default -> null;
        };
    }

    /** World-space position of the left/off-hand controller, or null. */
    public Vec3 getOffHandPos() {
        if (VrHandClamp.clampOff != null && VrHandClamp.rawOff != null) {
            return VrHandClamp.rawOff;
        }
        return switch (tryWire()) {
            case PUBLIC -> pubPosition(mPubGetOff);
            case LEGACY -> legacyPosition(fC1);
            default -> null;
        };
    }

    /** World-space position of the headset/eyes, or null. */
    public Vec3 getHeadPos() {
        return switch (tryWire()) {
            case PUBLIC -> pubPosition(mPubGetHead);
            case LEGACY -> legacyPosition(fHmd);
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Controller orientation (hoverboard tilt). Wired separately from the core
    // position API, like teleport-aim below, so a missing method can only
    // disable the hoverboard — never locomotion.
    // -----------------------------------------------------------------------

    private boolean axisTried  = false;
    private boolean axisBroken = false;
    private Method  mPubGetRotation;       // VRBodyPartData#getRotation() → Quaternionfc (1.3.x)
    private Method  mGetCustomVector;      // VRDevicePose#getCustomVector(Vector3fc) → Vector3f (1.2.x)

    /**
     * The given controller-local axis expressed in world space, for the main
     * ({@code main=true}) or off-hand controller. Null if VR/Vivecraft can't
     * provide it. Vivecraft's controller frame: +Y up out of the controller's
     * top, -Z along its pointing direction.
     */
    public Vec3 getHandAxis(boolean main, double x, double y, double z) {
        Link l = tryWire();
        if (axisBroken || (l != Link.PUBLIC && l != Link.LEGACY)) return null;
        try {
            if (!axisTried) {
                axisTried = true;
                if (l == Link.PUBLIC) {
                    mPubGetRotation = Class.forName("org.vivecraft.api.data.VRBodyPartData")
                            .getMethod("getRotation");
                } else {
                    mGetCustomVector = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose")
                            .getMethod("getCustomVector", org.joml.Vector3fc.class);
                }
            }
            org.joml.Vector3f axis = new org.joml.Vector3f((float) x, (float) y, (float) z);
            Object v;
            if (l == Link.PUBLIC) {
                Object pose = mPubGetPose.invoke(pubApi);
                if (pose == null) return null;
                Object part = (main ? mPubGetMain : mPubGetOff).invoke(pose);
                if (part == null) return null;
                Object rot = mPubGetRotation.invoke(part);
                if (!(rot instanceof org.joml.Quaternionfc q)) return null;
                v = q.transform(axis);
            } else {
                Object vrPlayer = mVrPlayerGet.invoke(null);
                if (vrPlayer == null) return null;
                Object vrData = fVrDataRender.get(vrPlayer);
                if (vrData == null) return null;
                Object devicePose = (main ? fC0 : fC1).get(vrData);
                if (devicePose == null) return null;
                v = mGetCustomVector.invoke(devicePose, axis);
            }
            if (!(v instanceof org.joml.Vector3fc r)) return null;
            return new Vec3(r.x(), r.y(), r.z());
        } catch (ReflectiveOperationException | LinkageError e) {
            axisBroken = true;
            System.out.println("[ViveMonkeCraft] Controller orientation unavailable, hoverboard disabled: " + e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Teleport-aim detection — wired separately; a failure here never disables
    // the core position API above.
    // -----------------------------------------------------------------------

    private boolean tpTried  = false;
    private boolean tpBroken = false;
    private Method mDataHolderGetInstance; // ClientDataHolderVR.getInstance()
    private Field  fTeleportTracker;       // ClientDataHolderVR#teleportTracker
    private Method mIsAiming;              // TeleportTracker#isAiming() → boolean

    /**
     * True while the player is holding the teleport button (Vivecraft teleport-aim
     * mode). During it Vivecraft freezes the hand/room pose, so our locomotion would
     * anchor to a stale hand. Never throws; returns false if unavailable.
     */
    public boolean isTeleportAiming() {
        if (tpBroken || tryWire() == Link.MISSING) return false;
        try {
            if (!tpTried) {
                tpTried = true;
                Class<?> dh = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
                mDataHolderGetInstance = dh.getMethod("getInstance");
                fTeleportTracker       = dh.getField("teleportTracker");
                Class<?> tt = Class.forName("org.vivecraft.client_vr.gameplay.trackers.TeleportTracker");
                mIsAiming = tt.getMethod("isAiming");
                VmcDebugLog.log("isTeleportAiming wired OK");
            }
            Object holder = mDataHolderGetInstance.invoke(null);
            if (holder == null) return false;
            Object tracker = fTeleportTracker.get(holder);
            if (tracker == null) return false;
            Object aiming = mIsAiming.invoke(tracker);
            return (aiming instanceof Boolean) && (Boolean) aiming;
        } catch (Throwable t) {
            tpBroken = true;
            VmcDebugLog.log("isTeleportAiming FAILED: " + t);
            return false;
        }
    }

    private boolean snapTried  = false;
    private boolean snapBroken = false;
    private Method  mSnapOrigin; // VRPlayer#snapRoomOriginToPlayerEntity(Entity,boolean,boolean)

    /**
     * Force Vivecraft's room origin to re-sync to the player entity — fixes a
     * teleport leaving the origin behind. Args (false, true) mirror Vivecraft's
     * own JumpTracker re-sync. Call ONLY right after a teleport.
     */
    public void snapRoomOriginToPlayer(net.minecraft.world.entity.Entity player) {
        if (snapBroken || player == null || tryWire() == Link.MISSING) return;
        try {
            if (!snapTried) {
                snapTried = true;
                Class<?> vrPlayerCls = Class.forName("org.vivecraft.client_vr.gameplay.VRPlayer");
                mSnapOrigin = vrPlayerCls.getMethod("snapRoomOriginToPlayerEntity",
                        net.minecraft.world.entity.Entity.class, boolean.class, boolean.class);
                if (mVrPlayerGet == null) {
                    mVrPlayerGet = vrPlayerCls.getMethod("get");
                }
                VmcDebugLog.log("snapRoomOriginToPlayer wired OK");
            }
            Object vp = mVrPlayerGet.invoke(null);
            if (vp != null) mSnapOrigin.invoke(vp, player, false, true);
            else VmcDebugLog.log("snapRoomOriginToPlayer: VRPlayer.get() returned null");
        } catch (Throwable t) {
            snapBroken = true;
            VmcDebugLog.log("snapRoomOriginToPlayer FAILED: " + t);
        }
    }

    private boolean tpoTried  = false;
    private boolean tpoBroken = false;
    private Method  mSetTeleportOverride; // VRPlayer#setTeleportOverride(boolean)
    private Method  mUpdateTeleportKeys;  // VRPlayer#updateTeleportKeys()
    private Method  mIsTeleportEnabled;   // VRPlayer#isTeleportEnabled()
    private Boolean tpoLast = null;

    /**
     * Set Vivecraft's teleport override and immediately re-apply it.
     * Setting the flag alone does nothing live — updateTeleportKeys() pushes it
     * onto the input actions. No-op when Vivecraft isn't present.
     */
    public void setTeleportOverride(boolean override) {
        if (tpoBroken || tryWire() == Link.MISSING) return;
        try {
            if (!tpoTried) {
                tpoTried = true;
                Class<?> vrPlayerCls = Class.forName("org.vivecraft.client_vr.gameplay.VRPlayer");
                mSetTeleportOverride = vrPlayerCls.getMethod("setTeleportOverride", boolean.class);
                mUpdateTeleportKeys  = vrPlayerCls.getMethod("updateTeleportKeys");
                mIsTeleportEnabled   = vrPlayerCls.getMethod("isTeleportEnabled");
                if (mVrPlayerGet == null) {
                    mVrPlayerGet = vrPlayerCls.getMethod("get");
                }
                VmcDebugLog.event("VR", "teleport-override reflection wired OK");
            }
            Object vp = mVrPlayerGet.invoke(null);
            if (vp == null) {
                if (!Boolean.FALSE.equals(tpoLast)) VmcDebugLog.event("VR", "setTeleportOverride: VRPlayer.get() null");
                return;
            }
            mSetTeleportOverride.invoke(vp, override);
            mUpdateTeleportKeys.invoke(vp);
            if (tpoLast == null || tpoLast != override) {
                tpoLast = override;
                Object enabledNow = mIsTeleportEnabled.invoke(vp);
                VmcDebugLog.event("VR", "setTeleportOverride(" + override
                        + ") applied → isTeleportEnabled=" + enabledNow);
            }
        } catch (Throwable t) {
            tpoBroken = true;
            VmcDebugLog.event("VR", "setTeleportOverride FAILED: " + t);
        }
    }

    // -----------------------------------------------------------------------
    // Wire-up — idempotent after first successful connection
    // -----------------------------------------------------------------------

    private Link tryWire() {
        if (link != Link.UNTRIED) return link;

        // Try public API first (Vivecraft 1.3.x, preferred)
        Link pub = wirePublic();
        if (pub == Link.PUBLIC) { link = Link.PUBLIC; return link; }
        if (pub == Link.MISSING) {
            // Public classes absent → try legacy immediately
            link = wireLegacy();
            return link;
        }
        // pub == UNTRIED means instance() returned null → VR not initialized yet,
        // retry next call without permanently setting a state.
        return Link.UNTRIED;
    }

    // -----------------------------------------------------------------------
    // Public-API wiring (Vivecraft 1.3.x)
    // -----------------------------------------------------------------------

    private Link wirePublic() {
        try {
            Class<?> apiCls  = Class.forName("org.vivecraft.api.client.VRClientAPI");
            Class<?> poseCls = Class.forName("org.vivecraft.api.data.VRPose");
            Class<?> partCls = Class.forName("org.vivecraft.api.data.VRBodyPartData");

            Object inst = apiCls.getMethod("instance").invoke(null);
            if (inst == null) return Link.UNTRIED; // VR not initialized yet — retry next frame

            mPubIsVRActive = apiCls.getMethod("isVRActive");
            mPubGetPose    = apiCls.getMethod("getWorldRenderPose");
            mPubGetMain    = poseCls.getMethod("getMainHand");
            mPubGetOff     = poseCls.getMethod("getOffHand");
            mPubGetHead    = poseCls.getMethod("getHead");
            mPubGetPos     = partCls.getMethod("getPos");
            pubApi         = inst;

            System.out.println("[ViveMonkeCraft] Vivecraft public API connected (1.3.x)");
            return Link.PUBLIC;

        } catch (ClassNotFoundException e) {
            // Not Vivecraft 1.3.x — fall through to legacy
            return Link.MISSING;
        } catch (ReflectiveOperationException e) {
            System.out.println("[ViveMonkeCraft] Vivecraft public API present but unusable: " + e);
            return Link.MISSING;
        }
    }

    // -----------------------------------------------------------------------
    // Legacy wiring (Vivecraft 1.2.x / QuestCraft / QCXR)
    // -----------------------------------------------------------------------

    private Link wireLegacy() {
        try {
            Class<?> vrStateType      = Class.forName("org.vivecraft.client_vr.VRState");
            Class<?> vrPlayerType     = Class.forName("org.vivecraft.client_vr.gameplay.VRPlayer");
            Class<?> vrDataType       = Class.forName("org.vivecraft.client_vr.VRData");
            Class<?> vrDevicePoseType = Class.forName("org.vivecraft.client_vr.VRData$VRDevicePose");

            fVrRunning    = vrStateType.getField("VR_RUNNING");
            mVrPlayerGet  = vrPlayerType.getMethod("get");
            fVrDataRender = vrPlayerType.getField("vrdata_world_render");
            fHmd          = vrDataType.getField("hmd");
            fC0           = vrDataType.getField("c0");
            fC1           = vrDataType.getField("c1");
            mGetPosition  = vrDevicePoseType.getMethod("getPosition");

            System.out.println("[ViveMonkeCraft] Vivecraft legacy API connected (1.2.x / QCXR)");
            return Link.LEGACY;

        } catch (ClassNotFoundException e) {
            System.out.println("[ViveMonkeCraft] Vivecraft not found — VR features unavailable.");
            return Link.MISSING;
        } catch (ReflectiveOperationException e) {
            System.out.println("[ViveMonkeCraft] Vivecraft API present but unusable: " + e);
            return Link.MISSING;
        }
    }

    // -----------------------------------------------------------------------
    // Public-API data accessors
    // -----------------------------------------------------------------------

    private boolean pubIsVRActive() {
        try {
            return Boolean.TRUE.equals(mPubIsVRActive.invoke(pubApi));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private Vec3 pubPosition(Method partGetter) {
        try {
            Object pose = mPubGetPose.invoke(pubApi);
            if (pose == null) return null;
            Object part = partGetter.invoke(pose);
            if (part == null) return null;
            Object pos  = mPubGetPos.invoke(part);
            return (pos instanceof Vec3) ? (Vec3) pos : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Legacy data accessors
    // -----------------------------------------------------------------------

    private boolean legacyIsVRActive() {
        try {
            return fVrRunning.getBoolean(null); // static field
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private Vec3 legacyPosition(Field devicePoseField) {
        try {
            Object vrPlayer   = mVrPlayerGet.invoke(null);
            if (vrPlayer == null) return null;
            Object vrData     = fVrDataRender.get(vrPlayer);
            if (vrData == null) return null;
            Object devicePose = devicePoseField.get(vrData);
            if (devicePose == null) return null;
            Object pos        = mGetPosition.invoke(devicePose);
            return (pos instanceof Vec3) ? (Vec3) pos : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
