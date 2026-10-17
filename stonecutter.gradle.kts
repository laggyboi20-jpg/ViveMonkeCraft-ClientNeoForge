plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.147" apply false
}

// The version the source files currently show. Switch with the
// "Set active project to ..." Gradle tasks; commit with it back on 26.3.
stonecutter active "26.3"

// One-to-one renames between versions. The source uses the right-hand (newer)
// name; Stonecutter swaps in the left-hand one for versions where the condition
// is false. Anything that isn't a plain rename is a //? block in the code
// (mostly in client/compat/VmcCompat).
stonecutter parameters {
    replacements {
        // 1.21.11 renamed ResourceLocation to Identifier, moved PlayerModel into
        // model.player and split RenderType's factories out into RenderTypes.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
            replace("net.minecraft.client.model.PlayerModel", "net.minecraft.client.model.player.PlayerModel")
            replace("net.minecraft.client.renderer.RenderType;", "net.minecraft.client.renderer.rendertype.RenderTypes;")
            replace("RenderType.entityCutoutNoCull(", "RenderTypes.entityCutout(")
        }

        // 1.21.9 renamed the player renderer + render state to Avatar* and moved
        // keybind categories to an enum.
        string(current.parsed >= "1.21.9") {
            replace("PlayerRenderState", "AvatarRenderState")
            replace("PlayerRenderer", "AvatarRenderer")
            replace("KeyMapping.CATEGORY_MISC", "KeyMapping.Category.MISC")
        }
    }
}
