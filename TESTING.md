# Verification and remaining device checks

The original, unmodified Not Doppler SWF is v1.31, 8,824,265 bytes, SHA-256 `db326f3ce2f331d3320cd442c88f17152198a5da4cca3d0fb9c1509afb430ea6`. It is excluded from Git and APKs. A desktop Ruffle 0.7.1 smoke test at the Android asset origin reached the preloader, menu, Day 1 journal and Night 1 with all external network requests blocked. The optional CPMStar advertisement was the only external request observed. This is not a complete playthrough or an Android device result.

The HTML bridge merges held movement/fire from touch, stick, hat, buttons and analogue triggers. Pressing A and R2 together must not release fire until both are released. Aim and fire target the same internal Ruffle canvas and use the same untransformed local coordinates, including when the display is stretched. The controller cursor stays where it is when the right stick returns to neutral.

The touch view owns fingers by stable pointer ID, allowing the movement pad, aiming and Reload/Switch simultaneously. Automated Android tests cover relative aim without snapping, lifting/re-touching, Direct preference, sensitivity, menu transitions, edge clamping, stationary input suppression, pointer reordering, cancellation and saved layouts. FrostFrontend supplies the shared editor and persistence.

For the exact tested Not Doppler v1.31 file, a host-only patch adds phase notifications to the game's showCrosshairs/hideCrosshairs functions in memory. The imported file stays unchanged. Unknown SWF copies are served unchanged and retain Direct aiming. The HTML ignores unrelated FSCommands and duplicate phases. Render Resolution limits Ruffle's physical canvas pixels independently of display density, then scales the finished image; it does not change simulation speed or zombie counts. Performance under heavy fire and chainsaw use still requires an Odin retest.

## Odin checks before release

1. First launch, import, menu cursor/taps and starting a new game.
2. Movement on stick, D-pad, hat axes and touch pad; aiming while moving; held R2 fire; tap fire; reload and switch weapons.
3. Right-stick aim speed, independent inversion/deadzone settings, menu cursor accuracy and original/stretch alignment.
4. Button remapping, analogue triggers after remapping, restore defaults and unmapped-action warnings.
5. Touch Auto/On/Off, joystick/D-pad, simultaneous fingers, sides, sizes, opacity, dragging every control, Save/restart, Cancel and Reset.
6. Opening settings or editing while firing must release inputs and pause gameplay/audio. Background/resume must preserve an open frontend and avoid stuck movement/fire.
7. Game sound in both speakers, speaker test and long-session performance.
8. Survive Night 1, use the daytime search/repair/travel screens, change survivor weapons, and confirm saved progress after closing and reopening.
9. Choose and install final launcher artwork. Prepare the permanent signing key and verify a signed release before publishing/activating stable updates.

No Android device gameplay or full-game compatibility is claimed yet.
