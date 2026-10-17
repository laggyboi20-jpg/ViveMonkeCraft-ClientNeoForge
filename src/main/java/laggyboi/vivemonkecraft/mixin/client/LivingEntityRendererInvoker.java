package laggyboi.vivemonkecraft.mixin.client;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Opens LivingEntityRenderer#addLayer (protected) so RendererHooksMixin can
// add the cosmetic layer to the player renderer. A mixin rather than a loader
// event, so the same code works on Fabric, NeoForge and Forge.
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {

    @Invoker("addLayer")
    boolean vmc$addLayer(RenderLayer<?, ?> layer);
}
