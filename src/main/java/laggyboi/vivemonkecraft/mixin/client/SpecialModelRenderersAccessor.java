package laggyboi.vivemonkecraft.mixin.client;

import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Opens SpecialModelRenderers.ID_MAPPER (private in vanilla) so HoverboardModel
// can register its "vivemonkecraft:hoverboard" special item model type. A mixin
// rather than a loader event, so the same code works on Fabric, NeoForge and
// Forge. Raw type: the mapper's generics differ between versions.
@Mixin(SpecialModelRenderers.class)
public interface SpecialModelRenderersAccessor {

    @SuppressWarnings("rawtypes")
    @Accessor("ID_MAPPER")
    static ExtraCodecs.LateBoundIdMapper vmc$idMapper() {
        throw new AssertionError();
    }
}
