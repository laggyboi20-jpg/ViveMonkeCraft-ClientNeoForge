package laggyboi.vivemonkecraft.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

// =====================================================================
// KEYBINDS                                               [NEOFORGE BODY]
// =====================================================================
//
// The KeyMapping itself is constructed identically on every loader (it's vanilla);
// only how it gets REGISTERED differs, which is what init() hides.
//
// On NeoForge registration may only happen inside RegisterKeyMappingsEvent on the
// MOD event bus, so the actual registration lives in VmcBootstrap and init() is a
// no-op here. TOGGLE is still created eagerly so the shared client code can hold a
// reference to it before that event fires.
//
// UNBOUND by default (no key out of the box). To toggle from Vivecraft's radial
// menu you must first bind it to a real keyboard key in Options -> Controls ->
// Miscellaneous, then assign that key to a radial slot — Vivecraft can only put
// KEYBOARD keys on radial slots. See the "How-to" page in the config screen.
//
// The keybind lands in the same vanilla Options.keyMappings array on every loader,
// which is what Vivecraft's radial menu reads — so the radial-slot workflow is
// unchanged from Fabric.
// =====================================================================
public final class VmcKeybinds {

    private VmcKeybinds() {}

    // Shared on all branches — do not diverge, the translation key is the identity
    // Vivecraft's radial menu shows.
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.vivemonkecraft.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            // 1.21.9 replaced the String category constants (CATEGORY_MISC) with
            // KeyMapping.Category record objects.
            KeyMapping.Category.MISC);

    // Cycle-locomotion-mode keybind — also UNBOUND by default. Bind it to a keyboard
    // key (and optionally a Vivecraft radial slot) to switch movement systems
    // (Speed Swing -> GT Anchor -> Charge Jump -> ...) live in-headset. Same radial
    // workflow as TOGGLE; registered by VmcBootstrap in RegisterKeyMappingsEvent.
    public static final KeyMapping CYCLE_MODE = new KeyMapping(
            "key.vivemonkecraft.cyclemode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            KeyMapping.Category.MISC);

    /** No-op on NeoForge — VmcBootstrap registers the keybinds in RegisterKeyMappingsEvent. */
    public static void init() {
    }
}
