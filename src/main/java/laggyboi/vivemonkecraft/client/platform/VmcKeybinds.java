package laggyboi.vivemonkecraft.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

// =====================================================================
// KEYBINDS                                               [NEOFORGE BODY]
// =====================================================================
//
// The KeyMapping itself is constructed identically on every loader (it's vanilla);
// only how it gets REGISTERED differs, which is what init() hides.
//
// On NeoForge registration may only happen inside RegisterKeyMappingsEvent on the
// MOD event bus, so the actual registration lives in VmcBootstrap and init() only
// does the stale-key migration. The mappings are still created eagerly so the
// shared client code can hold a reference before that event fires.
//
// Bound to real keys by default so the Vivecraft radial menu can assign them
// immediately (Vivecraft can only put KEYBOARD keys on radial slots). The user
// can rebind in Options -> Controls -> Miscellaneous.
// =====================================================================
public final class VmcKeybinds {

    private VmcKeybinds() {}

    // Default keys: V = toggle, B = cycle preset. Shared on all branches — the
    // translation key is the identity Vivecraft's radial menu shows.
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.vivemonkecraft.toggle",
            InputConstants.KEY_V,
            KeyMapping.Category.MISC);

    public static final KeyMapping CYCLE_MODE = new KeyMapping(
            "key.vivemonkecraft.cyclemode",
            InputConstants.KEY_B,
            KeyMapping.Category.MISC);

    /** Registration happens in VmcBootstrap (RegisterKeyMappingsEvent). */
    public static void init() {
        //? if >=26.3 {
        // Migrate stale GLFW_KEY_UNKNOWN (-1) saved in options.txt from pre-26.3;
        // -1 is not a valid SDL scancode and crashes Vivecraft's radial menu.
        InputConstants.Key staleUnbound = InputConstants.Type.KEYBOARD.getOrCreate(-1);
        if (TOGGLE.matches(staleUnbound)) {
            TOGGLE.setKey(InputConstants.UNKNOWN);
        }
        if (CYCLE_MODE.matches(staleUnbound)) {
            CYCLE_MODE.setKey(InputConstants.UNKNOWN);
        }
        //?}
    }
}
