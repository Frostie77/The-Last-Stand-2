# The Last Stand 2 — Android

An Android port of **The Last Stand 2**, powered by [Ruffle](https://ruffle.rs/) and [FrostFrontend](https://github.com/Frostie77/FrostFrontend). Designed for touchscreens and Android handhelds such as the AYN Odin 2 Portal. Android 8.0 or newer required.

This is an unofficial fan project. The original game and its assets belong to their respective creators. The SWF is not included in this repository or the APK.

## Game file

Download [the original SWF hosted by Not Doppler](https://i.notdoppler.com/files/thelaststand2.swf), then select it in the app on first launch. [Not Doppler's game page](https://www.notdoppler.com/thelaststand2.php) credits Con Artists Productions and Armor Games and states that the game is used with permission.

The verified file is **v1.31**, **8,824,265 bytes**, SHA-256:

```text
db326f3ce2f331d3320cd442c88f17152198a5da4cca3d0fb9c1509afb430ea6
```

The Armor Games website-specific copy has a site lock when loaded by this Android wrapper. Use the Not Doppler file linked above. The imported game and saves are stored privately by the app.

## Controls

| Controller | Action |
| --- | --- |
| Left stick / D-pad | Move (W/A/S/D) |
| Right stick | Aim / move the menu cursor |
| R2 | Hold to fire / pointer click |
| A | Pointer click / fire |
| X | Reload (R) |
| Y | Switch weapons (Space) |
| R3 | Original game options (Escape) |
| Start | Open FrostFrontend and pause gameplay |

Controller actions are remappable in FrostFrontend. Movement and aim have independent deadzones and inversion settings; cursor speed is adjustable.

Touch controls provide a joystick or D-pad, Reload and Switch buttons. **Touch Settings → Touch Aiming** defaults to **Relative**, like Arcane: touch and hold to fire at the existing crosshair, then swipe to move it. Lifting and touching again keeps the aim position. **Direct** aims at the finger instead. Game menus automatically use direct taps with the tested v1.31 SWF. Touch aim speed is adjustable, and movement, aiming and buttons work simultaneously.

Open **Touch Settings → Edit Touch Layout** to move the pad and each button. **Save** persists the layout; **Cancel** discards changes. **Reset**, followed by Save, restores defaults. The game stays paused while editing. Size, opacity, side swapping and layout presets are available. Auto mode follows the last input used, including on handhelds with integrated gamepads.

## Settings and updates

FrostFrontend includes Original (800×420) or Stretch to Screen display, a stereo speaker test, controller mapping, touch settings and update checks. **Graphics → Render Resolution** defaults to **800×420** to reduce rendering load during crowded fights; **1600×840** and **Device** are available. Resolution changes apply without restarting the game. **Antialiasing** defaults to **Off** for lower rendering cost; **2×** and **4×** are available and apply on the next app launch. Game execution and sound pause when settings are open or the app is in the background.

Development build: **0.1.0-dev**, package `io.github.frostie77.laststand2`. There is no signed public release yet. The stable feed remains inactive until a signed APK has been tested and published. A temporary launcher icon is used during development.

Build instructions: [BUILDING.md](BUILDING.md). Verification scope and device checks: [TESTING.md](TESTING.md).

## Credits

- **Con Artists Productions / Con Artist Games** — original game and development. [Creator's Newgrounds release](https://www.newgrounds.com/portal/view/439275).
- **Armor Games** — original publisher/sponsor. [Original game page](https://armorgames.com/play/1443/the-last-stand-2).
- **Evil Lives** — music, credited in the original game.
- **Ruffle contributors** — Flash emulation; Apache-2.0 / MIT.
- **Frostie77** — Android integration, controls and FrostFrontend.

The port source uses the [MIT license](LICENSE). This does not grant rights to the original game, artwork or music. FrostFrontend and Ruffle retain their own licenses.
