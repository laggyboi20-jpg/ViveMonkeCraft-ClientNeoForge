# ViveMonkeCraft (NeoForge)

**Gorilla Tag style VR locomotion for Minecraft 26.1.2.** Grab the world with your
hands and swing, climb, and fling yourself through it. No joystick required.

> This is the **NeoForge** build for **Minecraft 26.1.2**.
> Other loaders and Minecraft versions live on their own branches and repos:
> [Fabric](https://github.com/laggyboi20-jpg/ViveMonkeCraft-ClientFabric) ·
> [NeoForge](https://github.com/laggyboi20-jpg/ViveMonkeCraft-ClientNeoForge) ·
> [Forge](https://github.com/laggyboi20-jpg/ViveMonkeCraft-ClientForge)

Client-side for singleplayer & LAN; an optional server companion unlocks it (and lets
admins cap it) on dedicated servers.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Loader | NeoForge 26.1.2.94+ |
| VR | Vivecraft (PCVR) for this Minecraft version — desktop Java only |
| Optional | Cloth Config (26.1.154, NeoForge flavour) — enables the in-game settings screen |
| Optional | [**ViveMonke server mod**](https://github.com/laggyboi20-jpg/ViveMonkeCraft-Server) — only for **dedicated** servers |

The mod is client-only (`Dist.CLIENT`). It does nothing without Vivecraft active
except render other players without legs. Vivecraft itself is **not** bundled — it is
reached at runtime by reflection, so the mod loads fine with or without it.

---

## Configuring

Configure it three ways: the **Config** button next to ViveMonkeCraft in the **Mods** list opens the Cloth Config screen (needs Cloth Config installed); the **`/vmc`** client command (`/vmc`, `/vmc reload`, `/vmc set <setting> <value>`); or by editing **`config/vivemonkecraft.properties`** directly.

---

## Multiplayer / server mod

On a **dedicated** server ViveMonkeCraft stays **off** until the server runs the companion mod [ViveMonkeCraft-Server](https://github.com/laggyboi20-jpg/ViveMonkeCraft-Server). This is intentional (server-side opt-in); the server mod also lets admins cap or disable it. Singleplayer and LAN worlds need nothing.

- The server mod is currently **Fabric-only**. There is no NeoForge server build yet, so on dedicated servers this NeoForge build stays disabled. [Server releases](https://github.com/laggyboi20-jpg/ViveMonkeCraft-Server/releases).

---

## Server Control

The companion server mod lets admins fully **disable** the mod, set **hard speed caps**
(enforced server-side), choose which **op level** bypasses limits, and **raise the allowed
limits** for everyone (push strength, reach, gravity, etc.). Players without cheats /
operator access are otherwise clamped to safe defaults, so editing the client config can't
gain an unfair edge.

---

## Locomotion

- **Hand-grab swinging**: when a hand touches a block it anchors there; moving that
  hand drags your body the opposite way (1:1, no joystick). Two hands average so you
  don't double-move.
- **Throw / jump**: let go while swinging fast and you launch, scaled by *jump power*
  and hard-capped so a tracking glitch can't rocket you.
- **Reach extension**: "gorilla arms" let you touch the ground from standing height
  without physically reaching; your real swing is still measured 1:1 so movement
  never feels twitchy.
- **Climbing & wall behaviour**: cling and climb walls, or set them to slide; a
  slide ramps in over ~½ second so quick pushes keep their momentum but you can't
  hover one-handed.
- **Floor / ground friction & air drag**: fully tunable, from sticky to ice-rink.
- **Gravity multiplier**: from normal all the way to zero-G floating.
- **Step assist**: clear ledges while moving: either an upward boost or an instant
  **step-teleport** onto the block (configurable).
- **Two physics modes:**
  - **Speed-based (default, stable)**: body velocity follows your swing speed.
  - **GT-Physics (Beta)**: a faithful port of the official GorillaLocomotion
    `Player.cs` anchor algorithm: hands plant in world space and your body is dragged
    to keep them there, with configurable drag gain, push strength, unstick distance,
    and ice slip.

---

## Ice

- Ice is slippery on **every** face: push off ice walls for momentum, but you can't
  cling or climb them.
- Tunable ice speed multiplier kept conservative.
- **Experimental:** make ice *floors* behave like ice *walls* (pure push-off, no glue).

---

## Gorilla body ("Real Monke" & Monke Model)

- **Real Monke**: shrinks your collision box to half height (0.5 blocks) so you fit
  through **1-block tunnels**. Collision-only: your model, width, reach and camera
  scale stay normal. Enforced on both client and server so you don't get rubber-banded.
- **Monke Model**: render players **without legs** and with a shortened, tunable
  torso (offset / pitch / scale, plus arm & head offset/rotation, full ±360°). Synced
  so every player with the mod sees every other one legless.
- **Camera height offset**: optionally lower the VR view so your hand hitboxes line
  up with your real hands (authentic GT feel).
- **Hand-model clamping**: your hand models plant on the block surface instead of
  sinking inside it, like GT's hand followers (purely visual; physics uses your real
  hand).

---

## Comfort & visuals

- **Camera stabilization**: a vignette that narrows your view while moving fast to
  reduce motion sickness (strength configurable).
- **Grip smoothing**: a low-pass filter that removes VR controller jitter from
  locomotion without muting intentional swings.
- **Hand markers**: green/red wireframe cubes + arm lines showing exactly where your
  grab hitboxes are (great for learning; toggle off any time).

---

## Quality-of-life

- **Auto-enable on join**, with a short grace window so server rules apply first.
- **GUI guard**: opening any screen (inventory, chat, settings…) fully suspends
  locomotion so you never drift while in a menu.
- **Robust teleport handling**: server teleports and dimension changes no longer
  leave you stuck, sliding, or wrong-sized.
- **Barrier blocks are ignored**: admins can fence off areas the mod can't bypass.

---

## License & Credits

This project's own code is **MIT**. See [LICENSE](LICENSE). Full upstream license
texts are in the [`third party license/`](third%20party%20license) folder.

**Credits for the open-sourced code and the idea (MIT and LGPLv3):**

| Source | License |
|---|---|
| Kerestell Smith, [Another-Axiom/GorillaLocomotion](https://github.com/Another-Axiom/GorillaLocomotion) | MIT |
| unbaswastaken, [gorillalocomotion-VR-MINECRAFT](https://github.com/unbaswastaken/gorillalocomotion-VR-MINECRAFT) | MIT |
| Vivecraft team, [VivecraftMod](https://github.com/Vivecraft/VivecraftMod) | LGPLv3 |
| LaggyBoi | |
| Claude Code | |

**Vivecraft** is **LGPLv3** and is accessed at runtime via reflection only. It is
**not bundled or modified** by this mod; players supply their own Vivecraft.

*Not affiliated with Another Axiom or Gorilla Tag. "Gorilla Tag" is referenced only to
describe the movement style.*
