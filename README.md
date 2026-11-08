# The Last Stand 2 — Android

An Android port of **The Last Stand 2**, powered by [Ruffle](https://ruffle.rs/) and [FrostFrontend](https://github.com/Frostie77/FrostFrontend). Designed for touchscreens and Android handhelds such as the AYN Odin 2 Portal. Android 8.0 or newer required.

This is an unofficial fan project. The original game and its assets belong to their respective creators. The SWF is not included in this repository or the APK.

## Download and install

1. Install the **v0.1.0** Android APK. [GitHub release](https://github.com/Frostie77/The-Last-Stand-2/releases/tag/v0.1.0)
2. Download `thelaststand2.swf` from Not Doppler. [Original SWF](https://i.notdoppler.com/files/thelaststand2.swf)
3. Open the app and select the SWF when prompted.

The app package is `io.github.frostie77.laststand2`. The verified SWF is **v1.31** and **8,824,265 bytes**. Its SHA-256 is shown below.

```text
db326f3ce2f331d3320cd442c88f17152198a5da4cca3d0fb9c1509afb430ea6
```

The Armor Games website-specific copy has a site lock when loaded by this Android wrapper. The imported game and saves are stored privately by the app.

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

Use **Updates → Check for Updates** in FrostFrontend to check for a newer stable release.

## Tested on the AYN Odin 2 Portal

The full campaign was completed on the Odin 2 Portal using the verified v1.31 SWF. The in-app update check also worked on the signed v0.1.0 APK.

See [build instructions](BUILDING.md), [release preparation](RELEASING.md), and [testing notes](TESTING.md).

## Credits

- **Con Artists Productions / Con Artist Games** — original game and development. [Creator's Newgrounds release](https://www.newgrounds.com/portal/view/439275).
- **Armor Games** — original publisher/sponsor. [Original game page](https://armorgames.com/play/1443/the-last-stand-2).
- **Not Doppler** hosts the verified SWF and credits the original creators. [Game page](https://www.notdoppler.com/thelaststand2.php)
- **Evil Lives** — music, credited in the original game.
- **Ruffle contributors** — Flash emulation; Apache-2.0 / MIT.
- **Frostie77** — Android integration, controls and FrostFrontend.

The port source uses the [MIT license](LICENSE). This does not grant rights to the original game, artwork or music. FrostFrontend and Ruffle retain their own licenses.
