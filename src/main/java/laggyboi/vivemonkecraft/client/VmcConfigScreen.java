package laggyboi.vivemonkecraft.client;



import java.util.List;
import java.util.Map;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// =====================================================================
// MOD MENU CONFIG SCREEN
// =====================================================================
//
// This makes a settings screen show up in the "Mods" list (Mod Menu): find
// ViveMonkeCraft, click the little gear/config button, and you get sliders and
// toggles for everything — no need to edit the .properties file by hand.
//
// It's OPTIONAL: it only does anything if Mod Menu + Cloth Config are installed
// (they're listed under "suggests" in fabric.mod.json). The .properties file
// still works exactly the same either way.
//
// RESET BUTTONS FOLLOW THE ACTIVE PRESET:
//   Every row's little reset arrow reverts to the value of the CURRENTLY ACTIVE
//   preset (MovementConfig.activePreset), not a fixed factory default. Pick
//   "Speed Run" + Save and the reset arrows now revert to Speed Run's values;
//   pick "Default" and they revert to the Default preset. The active preset is
//   shown at the top of the Presets page so it's never a mystery.
// =====================================================================

public final class VmcConfigScreen {

    // The active preset's value for every field, captured at screen-build time.
    // Each entry's setDefaultValue (= what its reset arrow reverts to) reads from here.
    private Map<String, Object> presetVals;

    // Reset-target getters — pull the active preset's value for a field by name.
    private double pD(String key) {
        Object v = presetVals.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }
    private boolean pB(String key) {
        Object v = presetVals.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    // -----------------------------------------------------------------------
    // Loader-neutral entry points
    // -----------------------------------------------------------------------
    //
    // Each loader has its own way of advertising a config screen (Mod Menu on
    // Fabric, IConfigScreenFactory on NeoForge/Forge). Those entries are tiny
    // separate classes; everything below this point — the whole screen, all the
    // pages and all the presets — is shared verbatim across every loader branch.

    // NOTE: the "is Cloth installed?" check deliberately does NOT live here — it is
    // in VmcClothConfig. Touching any static member of THIS class forces the JVM to
    // verify it, which resolves the Cloth types in the signatures below and throws
    // NoClassDefFoundError when Cloth is absent. Callers must gate on
    // VmcClothConfig.present() before referencing this class at all.

    /** Build the config screen. "parent" is the screen to return to on Save/Cancel. */
    public static Screen create(Screen parent) {
        return new VmcConfigScreen().buildScreen(parent);
    }

    private Screen buildScreen(Screen parent) {
        // Sync fields from the file first, so the sliders show the latest values.
        MovementConfig.load();

        // RESET-BUTTON TARGETS: compute the active preset's value for every field
        // WITHOUT disturbing the player's real values. Snapshot current -> apply the
        // active preset -> snapshot those values (presetVals) -> restore current. The
        // entries below read their setDefaultValue from presetVals, so each reset arrow
        // reverts to the active preset. try/finally guarantees we always restore.
        Map<String, Object> current = MovementConfig.snapshot();
        try {
            MovementConfig.applyPreset(MovementConfig.activePreset);
            presetVals = MovementConfig.snapshot();
        } finally {
            MovementConfig.restore(current);
        }

        // Has the player tweaked away from the active preset? (drives the "(customized)"
        // tag so they know reset won't reproduce their current values exactly).
        boolean customized = false;
        for (Map.Entry<String, Object> e : presetVals.entrySet()) {
            Object a = current.get(e.getKey());
            Object b = e.getValue();
            if (a instanceof Number && b instanceof Number) {
                if (Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) > 1e-9) {
                    customized = true;
                    break;
                }
            } else if (!java.util.Objects.equals(a, b)) {
                customized = true;
                break;
            }
        }
        final String activeLabel = MovementConfig.activePreset + (customized ? " §7(customized)" : "");

        // PRESET FIX: Cloth Config calls every entry's save consumer IN REGISTRATION
        // ORDER before it calls setSavingRunnable. Because the preset dropdown is
        // registered first, its save consumer would run first — but then EVERY
        // slider's save consumer fires next and overwrites the preset values with the
        // stale on-screen values.
        //
        // Fix: the dropdown only stores the chosen name here. The actual applyPreset()
        // call is deferred to setSavingRunnable, which runs AFTER all slider consumers
        // have already written their values — so the preset wins.
        final String[] pendingPreset = {"— None —"};

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("ViveMonkeCraft — Gorilla Locomotion"));

        // Runs last when you press "Save" (after all entry save consumers).
        // Apply the chosen preset HERE so it overwrites whatever the sliders wrote,
        // and record it as the new active preset (so the reset arrows follow it next
        // time the screen opens).
        builder.setSavingRunnable(() -> {
            if (!pendingPreset[0].equals("— None —")) {
                MovementConfig.applyPreset(pendingPreset[0]);
                MovementConfig.activePreset = pendingPreset[0];
            }
            MovementConfig.save();
        });

        ConfigEntryBuilder eb = builder.entryBuilder();

        // ====================================================================
        // PRESETS — choose a preset from the dropdown and hit Save to apply.
        // After saving, close and re-open the config screen to see the updated
        // slider values (Cloth Config reads field values at screen-open time).
        // ====================================================================
        ConfigCategory presets = builder.getOrCreateCategory(Component.literal("Presets"));

        // PRESET SHOWER — tells the player which preset is active and what "reset" does.
        presets.addEntry(eb.startTextDescription(
            Component.literal("§7Active preset: §a" + activeLabel
                + "\n§7The §freset arrows§7 next to each setting revert it to §athis preset§7. "
                + "Pick a different preset below and press §fSave§7 to switch — the reset "
                + "arrows will then follow the new one."))
            .build());

        presets.addEntry(eb.startTextDescription(
            Component.literal("§7Pick a preset and press §fSave§7. Close and re-open this screen to see the updated values you can scroll this menu while presets are open look right there is a height slider."))
            .build());

        presets.addEntry(
            eb.startStringDropdownMenu(Component.literal("Apply Preset"), "— None —",
                    s -> Component.literal(s))
                .setSelections(List.of(
                    "— None —",
                    "tutorial",
                    "Default",
                    "Long Arms",
                    "Zero Gravity",
                    "Speed Run"
                ))
                .setDefaultValue("— None —")
                .setSuggestionMode(false)
                .setTooltip(
                    Component.literal("tutorial    — Use this if you find it hard to move with default."),
                    Component.literal("Default     — Reset all settings to factory defaults."),
                    Component.literal("Gorilla Tag — Authentic GT feel: stronger throws, less air drag."),
                    Component.literal("Zero Gravity— Float after letting go; space-like movement."),
                    Component.literal("Speed Run   — Very powerful launches, low air drag.")
                )
                // Just STORE the name — applyPreset() is called in setSavingRunnable
                // (which runs after ALL slider save consumers) so the preset wins.
                .setSaveConsumer(p -> pendingPreset[0] = p)
                .build()
        );


        // ====================================================================
        // MOVEMENT
        // ====================================================================
        ConfigCategory movement = builder.getOrCreateCategory(Component.literal("Movement"));

        movement.addEntry(eb.startBooleanToggle(Component.literal("Step assist teleports"), MovementConfig.stepTeleport)
            .setDefaultValue(pB("stepTeleport"))
            .setTooltip(
                Component.literal("ON = step assist places you directly on top of the ledge (instant)."),
                Component.literal("OFF = old behaviour: an upward velocity boost arcs you over it."))
            .setSaveConsumer(v -> MovementConfig.stepTeleport = v).build());

        movement.addEntry(eb.startBooleanToggle(Component.literal("Step assist"), MovementConfig.stepAssist)
                .setDefaultValue(pB("stepAssist"))
                .setTooltip(Component.literal("Raise step height so legs stop snagging on block edges."))
                .setSaveConsumer(v -> MovementConfig.stepAssist = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Push speed"), MovementConfig.pullStrength)
            .setDefaultValue(pD("pullStrength")).setMin(0.0).setMax(8.0)
            .setTooltip(Component.literal("How strongly your swing moves you. 1.0 = 1:1, higher = faster. (Ignored in anchor mode — that is always 1:1.)"))
            .setSaveConsumer(v -> MovementConfig.pullStrength = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Ground friction (anti-slide)"), MovementConfig.groundFriction)
            .setDefaultValue(pD("groundFriction")).setMin(0.0).setMax(1.0)
            .setTooltip(Component.literal("1.0 = slide forever, 0.5 = stops fast, 0.2 = near-instant."))
            .setSaveConsumer(v -> MovementConfig.groundFriction = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Air friction"), MovementConfig.airFriction)
            .setDefaultValue(pD("airFriction")).setMin(0.0).setMax(1.0)
            .setTooltip(
                Component.literal("How much horizontal speed bleeds off while airborne and NOT gripping."),
                Component.literal("1.0 = normal Minecraft drag  (velocity × 0.91 each tick)"),
                Component.literal("0.5 = half drag  (carries momentum further after a throw)"),
                Component.literal("0.0 = no drag  (throws carry forever — useful in Zero-G)")
            )
            .setSaveConsumer(v -> MovementConfig.airFriction = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Wall stickiness"), MovementConfig.wallStickiness)
            .setDefaultValue(pD("wallStickiness")).setMin(0.0).setMax(1.0)
            .setTooltip(Component.literal("How hard hands cling to walls while gripping. 1.0 = hang forever, 0.5 = slowly slide down, 0.0 = no clinging (gravity pulls you off)."))
            .setSaveConsumer(v -> MovementConfig.wallStickiness = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Floor stickiness"), MovementConfig.floorStickiness)
            .setDefaultValue(pD("floorStickiness")).setMin(0.0).setMax(1.0)
            .setTooltip(Component.literal("How much you slide across the floor while a hand is planted. 1.0 = stop dead (sticks to spot), 0.5 = some glide, 0.0 = ice-like (slides forever)."))
            .setSaveConsumer(v -> MovementConfig.floorStickiness = v).build());

        movement.addEntry(eb.startDoubleField(Component.literal("Jitter filter (minImpulse)"), MovementConfig.minImpulse)
            .setDefaultValue(pD("minImpulse")).setMin(0.0).setMax(0.1)
            .setTooltip(Component.literal("Movements smaller than this are ignored so a resting hand stays still."))
            .setSaveConsumer(v -> MovementConfig.minImpulse = v).build());

        // ====================================================================
        // REACH & BODY
        // ====================================================================
        ConfigCategory body = builder.getOrCreateCategory(Component.literal("Reach & Body"));

        body.addEntry(eb.startDoubleField(Component.literal("Gorilla arm length"), MovementConfig.handReachMultiplier)
            .setDefaultValue(pD("handReachMultiplier")).setMin(1.0).setMax(6.0)
            .setTooltip(Component.literal("Longer arms = reach the ground with less real-arm reach. 2.5 default, raise to 3.0+"))
            .setSaveConsumer(v -> MovementConfig.handReachMultiplier = v).build());

        body.addEntry(eb.startDoubleField(Component.literal("Max arm length (blocks)"), MovementConfig.maxArmLength)
            .setDefaultValue(pD("maxArmLength")).setMin(1.5).setMax(8.0)
            .setTooltip(Component.literal("Hard limit on how far a hand can be from your head."))
            .setSaveConsumer(v -> MovementConfig.maxArmLength = v).build());

        body.addEntry(eb.startDoubleField(Component.literal("Hand grab size"), MovementConfig.handRadius)
            .setDefaultValue(pD("handRadius")).setMin(0.02).setMax(0.5)
            .setTooltip(Component.literal("Radius of the hand touch sphere. Bigger = easier to grab."))
            .setSaveConsumer(v -> MovementConfig.handRadius = v).build());

        body.addEntry(eb.startDoubleField(Component.literal("Hitbox height (legs)"), MovementConfig.hitboxHeightScale)
            .setDefaultValue(pD("hitboxHeightScale")).setMin(0.2).setMax(1.0)
            .setTooltip(Component.literal("Shrinks your collision box height to climb onto blocks. 1.0 = normal, 0.25 = compact (default)."))
            .setSaveConsumer(v -> MovementConfig.hitboxHeightScale = v).build());

        body.addEntry(eb.startDoubleField(Component.literal("Step height"), MovementConfig.stepHeight)
            .setDefaultValue(pD("stepHeight")).setMin(0.5).setMax(2.0)
            .setTooltip(Component.literal("Ledge height you can step over. 0.6 = vanilla, 1.5 = 1.5 blocks (default)."))
            .setSaveConsumer(v -> MovementConfig.stepHeight = v).build());

        // ====================================================================
        // JUMP & GRAVITY
        // ====================================================================
        ConfigCategory jump = builder.getOrCreateCategory(Component.literal("Jump & Gravity"));

        jump.addEntry(eb.startDoubleField(Component.literal("Jump threshold"), MovementConfig.velocityLimit)
            .setDefaultValue(pD("velocityLimit")).setMin(0.0).setMax(1.0)
            .setTooltip(Component.literal("You only launch on release if you were moving faster than this."))
            .setSaveConsumer(v -> MovementConfig.velocityLimit = v).build());

        jump.addEntry(eb.startDoubleField(Component.literal("Jump power"), MovementConfig.jumpMultiplier)
            .setDefaultValue(pD("jumpMultiplier")).setMin(0.0).setMax(5.0)
            .setTooltip(Component.literal("How much your speed is multiplied into the launch. 1.0 = release speed, 1.4 = boost."))
            .setSaveConsumer(v -> MovementConfig.jumpMultiplier = v).build());

        jump.addEntry(eb.startDoubleField(Component.literal("Max launch speed"), MovementConfig.maxJumpSpeed)
            .setDefaultValue(pD("maxJumpSpeed")).setMin(0.1).setMax(5.0)
            .setTooltip(Component.literal("Hard cap on launch speed (blocks/tick). 1.0 = 20 blocks/sec."))
            .setSaveConsumer(v -> MovementConfig.maxJumpSpeed = v).build());

        jump.addEntry(eb.startDoubleField(Component.literal("Jump smoothing (ticks)"), MovementConfig.velocityHistorySize)
            .setDefaultValue(pD("velocityHistorySize")).setMin(1.0).setMax(20.0)
            .setTooltip(Component.literal("How many ticks of movement are averaged for the launch."))
            .setSaveConsumer(v -> MovementConfig.velocityHistorySize = v).build());

        jump.addEntry(eb.startDoubleField(Component.literal("Gravity"), MovementConfig.gravityMultiplier)
            .setDefaultValue(pD("gravityMultiplier")).setMin(0.0).setMax(1.0)
            .setTooltip(
                Component.literal("Scales gravity while airborne and NOT gripping."),
                Component.literal("1.0 = normal Minecraft gravity  (default)"),
                Component.literal("0.5 = half gravity  (slow fall, floaty)"),
                Component.literal("0.0 = zero gravity  (float in place after releasing)"),
                Component.literal("Can also be changed in-game with /vmc gravity <0-1> (op required).")
            )
            .setSaveConsumer(v -> MovementConfig.gravityMultiplier = v).build());

        // ====================================================================
        // VISUAL
        // ====================================================================
        ConfigCategory visual = builder.getOrCreateCategory(Component.literal("Visual"));

        visual.addEntry(eb.startBooleanToggle(Component.literal("Clamp hand models to surfaces"), MovementConfig.clampHandModels)
            .setDefaultValue(pB("clampHandModels"))
            .setTooltip(
                Component.literal("While gripping, the Vivecraft hand model is drawn ON the block face"),
                Component.literal("instead of sinking inside it (like Gorilla Tag's hand followers)."),
                Component.literal("Purely visual — physics always uses your real hand."))
            .setSaveConsumer(v -> MovementConfig.clampHandModels = v).build());

        visual.addEntry(eb.startBooleanToggle(Component.literal("Hand surface collision (free hands)"), MovementConfig.handSurfaceCollision)
            .setDefaultValue(pB("handSurfaceCollision"))
            .setTooltip(
                Component.literal("Also keeps the hand model out of blocks when NOT gripping, so the"),
                Component.literal("non-pushing hand rests ON the ground instead of sinking into it."),
                Component.literal("Swept last->current so fast swings/falls don't bury a hand."),
                Component.literal("Needs 'Clamp hand models'. Purely visual."))
            .setSaveConsumer(v -> MovementConfig.handSurfaceCollision = v).build());

        visual.addEntry(eb.startBooleanToggle(Component.literal("Real Monke (gorilla size)"), MovementConfig.realMonke)
            .setDefaultValue(pB("realMonke"))
            .setTooltip(
                Component.literal("Caps your collision box HEIGHT at 0.5 blocks on both client and"),
                Component.literal("server so you fit through 1-block tunnels. Height only — width,"),
                Component.literal("model, camera scale and reach stay normal. Does NOT drop the view."))
            .setSaveConsumer(v -> MovementConfig.realMonke = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Camera height offset"), MovementConfig.cameraHeightOffset)
            .setDefaultValue(pD("cameraHeightOffset")).setMin(0.0).setMax(1.5)
            .setTooltip(
                Component.literal("Blocks to LOWER the VR view + hands. 0 = off."),
                Component.literal("Trade-off: makes your OWN body model look squashed toward the"),
                Component.literal("floor (it stays anchored to your feet). Real Monke does not use it."))
            .setSaveConsumer(v -> MovementConfig.cameraHeightOffset = v).build());

        visual.addEntry(eb.startBooleanToggle(Component.literal("Monke model (no legs)"), MovementConfig.monkeModel)
            .setDefaultValue(pB("monkeModel"))
            .setTooltip(
                Component.literal("The Gorilla Tag body: legs removed, torso shortened."),
                Component.literal("Synced via monke-server — every player with the mod sees every"),
                Component.literal("other opted-in player legless. Tune the body parts below."))
            .setSaveConsumer(v -> MovementConfig.monkeModel = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke torso offset Y"), MovementConfig.modelTorsoOffsetY)
            .setDefaultValue(pD("modelTorsoOffsetY")).setMin(-12.0).setMax(12.0)
            .setTooltip(Component.literal("Torso up/down in model pixels (1 px = 1/16 block, +down)."))
            .setSaveConsumer(v -> MovementConfig.modelTorsoOffsetY = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke torso pitch (deg)"), MovementConfig.modelTorsoPitch)
            .setDefaultValue(pD("modelTorsoPitch")).setMin(-360.0).setMax(360.0)
            .setTooltip(Component.literal("Torso rotation in degrees — full -360..360 allowed. -120 = GT lean (default)."))
            .setSaveConsumer(v -> MovementConfig.modelTorsoPitch = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke torso height scale"), MovementConfig.modelTorsoScaleY)
            .setDefaultValue(pD("modelTorsoScaleY")).setMin(0.1).setMax(2.0)
            .setTooltip(Component.literal("Torso height multiplier. 0.75 = a bit shorter than the arms."))
            .setSaveConsumer(v -> MovementConfig.modelTorsoScaleY = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke arms offset Y"), MovementConfig.modelArmsOffsetY)
            .setDefaultValue(pD("modelArmsOffsetY")).setMin(-12.0).setMax(12.0)
            .setTooltip(Component.literal("Arms up/down in model pixels (+down)."))
            .setSaveConsumer(v -> MovementConfig.modelArmsOffsetY = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke arms pitch (deg)"), MovementConfig.modelArmsPitch)
            .setDefaultValue(pD("modelArmsPitch")).setMin(-360.0).setMax(360.0)
            .setTooltip(Component.literal("Extra arm rotation in degrees (added on top of the swing animation)."))
            .setSaveConsumer(v -> MovementConfig.modelArmsPitch = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke head offset Y"), MovementConfig.modelHeadOffsetY)
            .setDefaultValue(pD("modelHeadOffsetY")).setMin(-12.0).setMax(12.0)
            .setTooltip(Component.literal("Head up/down in model pixels (+down)."))
            .setSaveConsumer(v -> MovementConfig.modelHeadOffsetY = v).build());

        visual.addEntry(eb.startDoubleField(Component.literal("Monke head pitch (deg)"), MovementConfig.modelHeadPitch)
            .setDefaultValue(pD("modelHeadPitch")).setMin(-360.0).setMax(360.0)
            .setTooltip(Component.literal("Extra head rotation in degrees (added on top of look direction)."))
            .setSaveConsumer(v -> MovementConfig.modelHeadPitch = v).build());

        //Block_interactions page
        ConfigCategory BlockInteractions = builder.getOrCreateCategory(Component.literal("BlockInteractions"));

        BlockInteractions.addEntry(eb.startBooleanToggle(Component.literal("Punch mining (experimental)"), MovementConfig.punchMining)
                .setDefaultValue(pB("punchMining"))
                .setTooltip(
                        Component.literal("Break the block your hand touches — but only while holding the"),
                        Component.literal("tool MEANT for it (pickaxe on stone, etc.) AND only when you PUNCH it"),
                        Component.literal("(hand speed into the block). Gentle grabs never mine, so climbing"),
                        Component.literal("with the tool in hand stays safe. Off by default."))
                .setSaveConsumer(v -> MovementConfig.punchMining = v).build());

        BlockInteractions.addEntry(eb.startDoubleField(Component.literal("Block_Breaking Threshold"),
                        MovementConfig.punchMiningThreshold)
                .setDefaultValue(pD("punchMiningThreshold")).setMin(0.0).setMax(1)
                .setTooltip(
                        Component.literal("how easily you break blocks"),
                        Component.literal("0 make block damage just by touching"))
                .setSaveConsumer(V -> MovementConfig.punchMiningThreshold = V).build());

        BlockInteractions.addEntry(eb.startBooleanToggle(Component.literal("Punch mining: no tool needed"), MovementConfig.punchMiningNoTool)
                .setDefaultValue(pB("punchMiningNoTool"))
                .setTooltip(
                        Component.literal("Let punch mining break blocks with ANY item (even bare hands) —"),
                        Component.literal("hand speed alone decides. Tool-required blocks still won't drop"),
                        Component.literal("items without the right tool, like vanilla. Off by default."))
                .setSaveConsumer(v -> MovementConfig.punchMiningNoTool = v).build());

        BlockInteractions.addEntry(eb.startBooleanToggle(Component.literal("Magma block sides hurt"), MovementConfig.magmaTouchDamage)
                .setDefaultValue(pB("magmaTouchDamage"))
                .setTooltip(
                        Component.literal("Grabbing a magma block on ANY face (not just standing on top)"),
                        Component.literal("deals the same hot-floor damage. Fire resistance negates it."))
                .setSaveConsumer(v -> MovementConfig.magmaTouchDamage = v).build());

        // ====================================================================
        // GORILLA TAG — the locomotion-mode picker and the tuning of mode 1, the
        // Player.cs clone (GtCloneLocomotion). Everything is in Gorilla Tag's own
        // units: 1 block = 1 metre, speeds in m/s.
        // ====================================================================
        ConfigCategory gtPage = builder.getOrCreateCategory(Component.literal("Gorilla Tag"));

        gtPage.addEntry(eb.startIntSlider(Component.literal("Locomotion mode"),
                        (int) MovementConfig.locomotionMode, 0, GorillaLocomotionHandler.MODE_COUNT - 1)
                .setDefaultValue((int) pD("locomotionMode"))
                .setTextGetter(v -> Component.literal(GorillaLocomotionHandler.modeName(v)))
                .setTooltip(
                        Component.literal("Speed Swing = the classic ViveMonkeCraft movement."),
                        Component.literal("Gorilla Tag = a clone of Gorilla Tag's own Player.cs: hands stick"),
                        Component.literal("where they touch, the body moves 1:1, real 9.81 gravity, GT's throw."),
                        Component.literal("Charge Jump = hold a hand on a surface to charge a launch."))
                .setSaveConsumer(v -> MovementConfig.locomotionMode = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Jump multiplier"), MovementConfig.gtJumpMultiplier)
                .setDefaultValue(pD("gtJumpMultiplier")).setMin(0.0).setMax(10.0)
                .setTooltip(Component.literal("Throw = average body speed × this. Gorilla Tag: 1.1"))
                .setSaveConsumer(v -> MovementConfig.gtJumpMultiplier = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Max jump speed (m/s)"), MovementConfig.gtMaxJumpSpeed)
                .setDefaultValue(pD("gtMaxJumpSpeed")).setMin(0.0).setMax(60.0)
                .setTooltip(Component.literal("Throw speed cap. Gorilla Tag: 6.5"))
                .setSaveConsumer(v -> MovementConfig.gtMaxJumpSpeed = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Velocity limit (m/s)"), MovementConfig.gtVelocityLimit)
                .setDefaultValue(pD("gtVelocityLimit")).setMin(0.0).setMax(10.0)
                .setTooltip(Component.literal("Moving slower than this never throws you. Gorilla Tag: 0.3"))
                .setSaveConsumer(v -> MovementConfig.gtVelocityLimit = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Velocity history (ticks)"), MovementConfig.gtVelocityHistory)
                .setDefaultValue(pD("gtVelocityHistory")).setMin(1).setMax(20)
                .setTooltip(Component.literal("Ticks averaged for the throw. 2 ≈ Gorilla Tag's 3 frames."))
                .setSaveConsumer(v -> MovementConfig.gtVelocityHistory = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Arm length (m)"), MovementConfig.gtArmLength)
                .setDefaultValue(pD("gtArmLength")).setMin(0.2).setMax(5.0)
                .setTooltip(Component.literal("Hands are held within this distance of your head. Gorilla Tag: 1.5"))
                .setSaveConsumer(v -> MovementConfig.gtArmLength = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Hand radius (m)"), MovementConfig.gtHandRadius)
                .setDefaultValue(pD("gtHandRadius")).setMin(0.01).setMax(0.5)
                .setTooltip(Component.literal("Size of each hand's collision sphere. Gorilla Tag: 0.05"))
                .setSaveConsumer(v -> MovementConfig.gtHandRadius = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Gravity (m/s²)"), MovementConfig.gtGravity)
                .setDefaultValue(pD("gtGravity")).setMin(0.0).setMax(40.0)
                .setTooltip(Component.literal("Gorilla Tag uses real gravity, 9.81 (Minecraft's is ~32)."))
                .setSaveConsumer(v -> MovementConfig.gtGravity = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Ground friction"), MovementConfig.gtGroundFriction)
                .setDefaultValue(pD("gtGroundFriction")).setMin(0.0).setMax(1.0)
                .setTooltip(Component.literal("Speed kept per tick while standing. 1 = no friction, 0 = instant stop."))
                .setSaveConsumer(v -> MovementConfig.gtGroundFriction = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Unstick distance (m)"), MovementConfig.gtUnstickDistance)
                .setDefaultValue(pD("gtUnstickDistance")).setMin(0.2).setMax(3.0)
                .setTooltip(Component.literal("A stuck hand lets go once it is this far from where it touched. Gorilla Tag: 1.0"))
                .setSaveConsumer(v -> MovementConfig.gtUnstickDistance = v).build());

        gtPage.addEntry(eb.startDoubleField(Component.literal("Ice slip"), MovementConfig.gtIceSlip)
                .setDefaultValue(pD("gtIceSlip")).setMin(0.0).setMax(1.0)
                .setTooltip(Component.literal("How much a hand slides along ice. 0 = grips like stone, 1 = frictionless."))
                .setSaveConsumer(v -> MovementConfig.gtIceSlip = v).build());

        //Experimental page For Experimental stuff
        ConfigCategory Experimental = builder.getOrCreateCategory(Component.literal("Experimental"));

        Experimental.addEntry(eb.startBooleanToggle(Component.literal("Allow Vivecraft teleport"), MovementConfig.allowTeleport)
                .setDefaultValue(pB("allowTeleport"))
                .setTooltip(
                        Component.literal("OFF (default): teleport is disabled while gorilla locomotion is on,"),
                        Component.literal("because teleporting desyncs the room origin and breaks hand physics."),
                        Component.literal("ON: keep teleport usable anyway (you accept the post-teleport jank)."),
                        Component.literal("Teleport always works when the mod is OFF."))
                .setSaveConsumer(v -> MovementConfig.allowTeleport = v).build());

        Experimental.addEntry(eb.startBooleanToggle(Component.literal("Ice floor = ice wall (experimental)"), MovementConfig.iceFloorWallLogic)
                .setDefaultValue(pB("iceFloorWallLogic"))
                .setTooltip(
                        Component.literal("Treat grabbing an ICE FLOOR exactly like an ice WALL: gravity on,"),
                        Component.literal("no anchor glue, pure push-off momentum. Legacy physics only."),
                        Component.literal("Experimental — for testing the feel."),
                        Component.literal("leave comment which one is better this or default"))
                .setSaveConsumer(v -> MovementConfig.iceFloorWallLogic = v).build());

        Experimental.addEntry(eb.startBooleanToggle(Component.literal("Vanilla ice friction"), MovementConfig.vanillaIceFriction)
                .setDefaultValue(pB("vanillaIceFriction"))
                .setTooltip(
                        Component.literal("Ice acts on your hands/feet like vanilla legs do: you SKATE with"),
                        Component.literal("the block's real friction (×0.91 inertia) and no speed cap, instead"),
                        Component.literal("of the mod's frictionless-but-capped ice. Floor/feet only — the"),
                        Component.literal("ice-wall push-off is unchanged."))
                .setSaveConsumer(v -> MovementConfig.vanillaIceFriction = v).build());



        //Debug Options
        ConfigCategory Debugs = builder.getOrCreateCategory(Component.literal("Debug Options"));
        Debugs.addEntry(eb.startBooleanToggle(Component.literal("Debug logging"), MovementConfig.debugLogging)
                .setDefaultValue(pB("debugLogging"))
                .setTooltip(
                        Component.literal("Write a focused ViveMonkeCraft-interaction trace"),
                        Component.literal("to logs/vivemonkecraft-debug.log."),
                        Component.literal("Turn on, reproduce the issue in VR, then share that file."),
                        Component.literal("this Is performance heavy Not recommended for quest"))
                .setSaveConsumer(v -> MovementConfig.debugLogging = v).build());

        Debugs.addEntry(eb.startBooleanToggle(Component.literal("Show hand markers"), MovementConfig.showHandMarkers)
                .setDefaultValue(pB("showHandMarkers"))
                .setTooltip(Component.literal("Render split arm lines at your hands. Green = touching a block, red = not."))
                .setSaveConsumer(v -> MovementConfig.showHandMarkers = v).build());

        // -----------------------------------------------------------------
        // GAMEPLAY — fall handling + hand/grip smoothing (1.0.0.3)
        // -----------------------------------------------------------------
        ConfigCategory gameplay = builder.getOrCreateCategory(Component.literal("Gameplay"));

        gameplay.addEntry(eb.startBooleanToggle(Component.literal("Survive fatal falls"), MovementConfig.surviveFatalFall)
                .setDefaultValue(pB("surviveFatalFall"))
                .setTooltip(
                        Component.literal("A last stand: a fall that would KILL you leaves you at 1 HP (half a"),
                        Component.literal("heart, minus 3 food) instead — but only if you were at FULL health."),
                        Component.literal("Landing near-death means it can't chain until you heal back up."),
                        Component.literal("Singleplayer / LAN host only from the client."))
                .setSaveConsumer(v -> MovementConfig.surviveFatalFall = v).build());

        gameplay.addEntry(eb.startBooleanToggle(Component.literal("Disable fall damage"), MovementConfig.disableFallDamage)
                .setDefaultValue(pB("disableFallDamage"))
                .setTooltip(
                        Component.literal("Turn off fall damage entirely while the mod is on."),
                        Component.literal("Overrides 'Survive fatal falls'. Singleplayer / LAN host only."))
                .setSaveConsumer(v -> MovementConfig.disableFallDamage = v).build());

        gameplay.addEntry(eb.startBooleanToggle(Component.literal("Single floor grip (smoother walking)"), MovementConfig.singleFloorGrip)
                .setDefaultValue(pB("singleFloorGrip"))
                .setTooltip(
                        Component.literal("Only one hand can grip the FLOOR at a time, so the trailing hand of"),
                        Component.literal("your gorilla walk can't accidentally anchor and stop you dead."),
                        Component.literal("Walls/ceilings are unaffected — two-handed climbing still works."))
                .setSaveConsumer(v -> MovementConfig.singleFloorGrip = v).build());

        gameplay.addEntry(eb.startBooleanToggle(Component.literal("Grounded grip release"), MovementConfig.groundedGripRelease)
                .setDefaultValue(pB("groundedGripRelease"))
                .setTooltip(
                        Component.literal("Auto-releases a resting floor grip once you're standing on the ground,"),
                        Component.literal("so you're not wedged after jumping straight up with your hand held in"),
                        Component.literal("place — no need to pull your hand back before you can walk."))
                .setSaveConsumer(v -> MovementConfig.groundedGripRelease = v).build());

        // -----------------------------------------------------------------
        // HOVERBOARD — the tilt-joystick board item
        // -----------------------------------------------------------------
        ConfigCategory hover = builder.getOrCreateCategory(Component.literal("Hoverboard"));

        hover.addEntry(eb.startBooleanToggle(Component.literal("Hoverboard enabled"), MovementConfig.hoverboardEnabled)
                .setDefaultValue(true)
                .setTooltip(
                        Component.literal("Hold the Hoverboard item in either hand and tilt it like a joystick:"),
                        Component.literal("it pushes out of its underside, so 45° forward = full speed ahead."),
                        Component.literal("Speed Swing locomotion mode only."))
                .setSaveConsumer(v -> MovementConfig.hoverboardEnabled = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Hover height"), MovementConfig.hoverHeight)
                .setDefaultValue(0.4).setMin(0.0).setMax(0.5)
                .setTooltip(Component.literal("Gap between your feet and the ground (blocks). Max 0.5 — higher looks like flying to servers."))
                .setSaveConsumer(v -> MovementConfig.hoverHeight = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Acceleration"), MovementConfig.hoverAccel)
                .setDefaultValue(0.035).setMin(0.0).setMax(0.2)
                .setTooltip(Component.literal("Push per tick at full tilt. Higher = snappier."))
                .setSaveConsumer(v -> MovementConfig.hoverAccel = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Top speed"), MovementConfig.hoverMaxSpeed)
                .setDefaultValue(0.7).setMin(0.0).setMax(2.0)
                .setTooltip(Component.literal("Blocks/tick. 0.7 = 14 blocks/sec."))
                .setSaveConsumer(v -> MovementConfig.hoverMaxSpeed = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Glide (drag)"), MovementConfig.hoverDrag)
                .setDefaultValue(0.98).setMin(0.5).setMax(1.0)
                .setTooltip(Component.literal("Speed kept each tick. 1.0 = glide forever, 0.9 = stops quickly."))
                .setSaveConsumer(v -> MovementConfig.hoverDrag = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Tilt deadzone"), MovementConfig.hoverDeadzone)
                .setDefaultValue(0.12).setMin(0.0).setMax(0.6)
                .setTooltip(Component.literal("Tilt ignored around level. 0.12 ≈ 7°."))
                .setSaveConsumer(v -> MovementConfig.hoverDeadzone = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Wall ride speed"), MovementConfig.hoverWallRideSpeed)
                .setDefaultValue(0.45).setMin(0.0).setMax(2.0)
                .setTooltip(
                        Component.literal("Speed needed to ride a wall (blocks/tick). Turn the board's"),
                        Component.literal("underside toward the wall while going this fast."))
                .setSaveConsumer(v -> MovementConfig.hoverWallRideSpeed = v).build());

        hover.addEntry(eb.startDoubleField(Component.literal("Tilt offset (°)"), MovementConfig.hoverTiltOffset)
                .setDefaultValue(0.0).setMin(-90.0).setMax(90.0)
                .setTooltip(
                        Component.literal("Shifts what counts as 'level' to match how you hold the controller."),
                        Component.literal("Drifting forward when holding it level? Lower this. Backward? Raise it."))
                .setSaveConsumer(v -> MovementConfig.hoverTiltOffset = v).build());

        // -----------------------------------------------------------------
        // COSMETICS — free 3D wearables, one toggle each
        // -----------------------------------------------------------------
        ConfigCategory cosmeticsPage = builder.getOrCreateCategory(Component.literal("Cosmetics"));

        cosmeticsPage.addEntry(eb.startTextDescription(Component.literal(
                "§7Free cosmetics, all unlocked. Other players with ViveMonkeCraft see what you wear."))
                .build());

        for (laggyboi.vivemonkecraft.client.cosmetics.Cosmetic c
                : laggyboi.vivemonkecraft.client.cosmetics.Cosmetics.all()) {
            cosmeticsPage.addEntry(eb.startBooleanToggle(Component.literal(c.name),
                            laggyboi.vivemonkecraft.client.cosmetics.Cosmetics.isEquipped(c.id))
                    .setDefaultValue(false)
                    .setSaveConsumer(v -> laggyboi.vivemonkecraft.client.cosmetics.Cosmetics.setEquipped(c.id, v))
                    .build());
        }

        // -----------------------------------------------------------------
        // HOW-TO — text tutorials (no settings, just guidance)
        // -----------------------------------------------------------------
        ConfigCategory howto = builder.getOrCreateCategory(Component.literal("How-to"));

        howto.addEntry(eb.startTextDescription(Component.literal(
                "§e§lToggle Monke movement from Vivecraft's radial menu§r\n"
              + "§7ViveMonkeCraft adds a keybind called §f\"ViveMonkeCraft: Toggle\"§7 "
              + "(id §8key.vivemonkecraft.toggle§7). It is §funbound§7 by default.\n\n"
              + "§71) First give it a keyboard key: §fOptions → Controls → Key Binds§7, "
              + "find §f\"ViveMonkeCraft: Toggle\"§7 under §fMiscellaneous§7 and bind it to any "
              + "free key (e.g. §fG§7). Vivecraft can only put a KEYBOARD key on a radial slot.\n\n"
              + "§72) Open §fVivecraft Settings → Controls → Radial Menu§7 (or hold the radial-menu "
              + "button and pick §fEdit§7).\n\n"
              + "§73) Choose an empty slot, select §fKeyboard§7, and press the same key you bound in "
              + "step 1. Name the slot §fMonke§7 if you like.\n\n"
              + "§74) Save. Now opening the Vivecraft radial menu and picking that slot toggles "
              + "gorilla locomotion on/off. §8(You can also toggle with §7/vmc§8 in chat.)"))
              .build());

        return builder.build();
    }

}
