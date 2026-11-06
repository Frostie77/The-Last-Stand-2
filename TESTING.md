# Verification and remaining device checks

The original, unmodified Not Doppler SWF is v1.31, 8,824,265 bytes, SHA-256 `db326f3ce2f331d3320cd442c88f17152198a5da4cca3d0fb9c1509afb430ea6`. It is excluded from Git and APKs. A desktop Ruffle 0.7.1 smoke test at the Android asset origin reached the preloader, menu, Day 1 journal and Night 1 with all external network requests blocked. The optional CPMStar advertisement was the only external request observed. This is not a complete playthrough or an Android device result.

The HTML bridge merges held movement/fire from touch, stick, hat, buttons and analogue triggers. Pressing A and R2 together must not release fire until both are released. Aim and fire target the same internal Ruffle canvas and use the same untransformed local coordinates, including when the display is stretched. The controller cursor stays where it is when the right stick returns to neutral.

The touch view owns fingers by stable pointer ID, allowing the movement pad, aiming and Reload/Switch simultaneously. Automated Android tests cover relative aim without snapping, lifting/re-touching, Direct preference, sensitivity, menu transitions, edge clamping, stationary input suppression, pointer reordering, cancellation and saved layouts. FrostFrontend supplies the shared editor and persistence.

For the exact tested Not Doppler v1.31 file, a host-only patch adds phase notifications to the game's showCrosshairs/hideCrosshairs functions in memory. The imported file stays unchanged. Unknown SWF copies are served unchanged and retain Direct aiming. The HTML ignores unrelated FSCommands and duplicate phases. Render Resolution limits Ruffle's physical canvas pixels independently of display density, then scales the finished image; it does not change simulation speed or zombie counts. Performance under heavy fire and chainsaw use still requires an Odin retest.

Activity-level gamepad regression tests cover native focus restoration, first analogue-trigger press, remapped buttons, Auto touch hiding, and movement/aim after closing settings. Scene changes cancel the touch view's own inputs without clearing held controller sources; the HTML tests verify that stick movement and trigger fire remain held until their physical releases.

The HTML bridge also recovers Ruffle's internal keyboard focus when an input event is ignored despite unchanged DOM focus. It re-enters the stage after pointer leave/cancel and encounter changes, and selects the currently visible canvas instead of retaining a hidden connected canvas. These cases are covered by the bridge tests. A separate runtime check with pinned Ruffle 0.7.1 and an independently generated Flash input fixture verifies `Key.isDown`, frame-driven movement and `onPress` firing after focus/leave transitions; this is an engine regression check, not an Odin gameplay result.

Run that optional engine check with `node tests/test_ruffle_recovery.cjs` after preparing the pinned runtime and installing Playwright with Chromium. `TLS2_RUFFLE_PATH` and `TLS2_CHROMIUM_PATH` can select existing local test resources. The fixture is generated in a temporary directory and contains no original game content. `TLS2_BASELINE_HTML` can point to an older wrapper to verify that the original input loss is reproduced.

The Odin tester reports improved controls with the focus recovery, but heavy stutter remains. The next performance changes default antialiasing to Off (Ruffle quality `low`) instead of the previous 4× (`high`), keeping the same render resolution. The optional runtime test also accepts `TLS2_QUALITY=high` to check both configurations and records WebGL multisample allocations. This verifies rendering configuration and input behavior, not an Odin frame-rate improvement.

Native aiming now permits only one movement evaluation in flight, retaining the latest pending position while WebView is busy. Button edges flush that latest position before delivery; presses/releases are never coalesced. Touch down/up/cancel remain ordered, and pause clears pending movement. Activity tests simulate a blocked renderer with 90 aim updates and verify the bounded delivery, latest position, button ordering and pause cleanup. The existing cursor ticker and motion calculation are unchanged.

## Odin checks before release

The tester still reports heavy stutter with running zombies and heavy shooting after the rendering/queue changes. No additional performance fix is claimed. With the installed Debug game open in the affected encounter, run `powershell -NoProfile -ExecutionPolicy Bypass -File .\capture-controller-input.ps1 -Performance`. The 12-second capture records page animation-frame gaps, long tasks, synchronous aim/key costs, canvas/configuration/renderer information, CDP main-thread duration metrics and a sampled CPU profile. Page frame intervals are not the Flash game's actual simulation frame rate, and self-sample percentages are not GPU utilization. Profiling itself adds overhead. The script restores temporary input wrappers, observers and profiling on completion or error; it does not reload the game or change its settings or saves. Paste the summary; full `.cpuprofile` and summary files are retained in ignored `.diagnostics/` for deeper analysis.

Set `TLS2_PROFILE=1` when running the optional Ruffle engine check to verify the real Chromium profiler/metrics and temporary probe against the independent fixture. Windows CI exercises the production PowerShell transport, CPU summary, large fragmented profile responses, saved files and cleanup after a simulated profiler error.

1. First launch, import, menu cursor/taps and starting a new game.
2. Movement on stick, D-pad, hat axes and touch pad; aiming while moving; held R2 fire; tap fire; reload and switch weapons.
3. Right-stick aim speed, independent inversion/deadzone settings, menu cursor accuracy and original/stretch alignment.
4. Button remapping, analogue triggers after remapping, restore defaults and unmapped-action warnings.
5. Touch Auto/On/Off, joystick/D-pad, simultaneous fingers, sides, sizes, opacity, dragging every control, Save/restart, Cancel and Reset.
6. Opening settings or editing while firing must release inputs and pause gameplay/audio. Background/resume must preserve an open frontend and avoid stuck movement/fire.
7. Game sound in both speakers, speaker test and long-session performance.
8. Survive Night 1, use the daytime search/repair/travel screens, change survivor weapons, and confirm saved progress after closing and reopening.
9. Choose and install final launcher artwork. Prepare the permanent signing key and verify a signed release before publishing/activating stable updates.

The full playthrough and device performance checks remain pending.
