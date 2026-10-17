package laggyboi.vivemonkecraft.client.platform;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// =====================================================================
// MOD ITEMS                                              [NEOFORGE BODY]
// =====================================================================
//
// HOVERBOARD — hold it in either hand (hotbar or off-hand slot) and it works
// like a joystick: tilt the board and it pushes you in the direction its
// underside thrusts. All the riding logic lives in HoverboardHandler; the item
// itself is a plain, unstackable marker item.
//
// Registered on the client, which runs in the same JVM as the integrated
// server (singleplayer / LAN host), so that server knows it too. Dedicated
// servers need the companion server mod to register the same id.
//
// The Fabric branch registers it directly; shared code only calls hoverboard().
// =====================================================================
public final class VmcItems {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("vivemonkecraft");

    private static final DeferredItem<Item> HOVERBOARD =
            ITEMS.registerItem("hoverboard", props -> new Item(props.stacksTo(1)));

    /** Called from VmcBootstrap with the mod event bus. */
    public static void init(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(HOVERBOARD.get());
            }
        });
    }

    public static Item hoverboard() {
        return HOVERBOARD.get();
    }

    private VmcItems() {}
}
