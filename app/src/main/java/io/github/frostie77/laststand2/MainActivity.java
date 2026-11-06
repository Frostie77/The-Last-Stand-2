package io.github.frostie77.laststand2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import io.github.frostfrontend.FrostFrontendView;
import io.github.frostfrontend.FrostButtonMapper;
import io.github.frostfrontend.FrostControlProfile;
import io.github.frostfrontend.FrostSpeakerTest;

/** The Last Stand 2 Android host for Ruffle and the shared FrostFrontend library. */
public final class MainActivity extends Activity {
    private static final int IMPORT_REQUEST = 7;
    private static final String SWF_NAME = "the-last-stand-2.swf";
    private FrameLayout root;
    private WebView webView;
    private boolean stickUp, stickDown, stickLeft, stickRight;
    // The Odin D-pad may arrive as key events or as joystick hat axes.
    private boolean hatUp, hatDown, hatLeft, hatRight;
    // Auto touch mode follows the last active input, not merely controller presence.
    // Odin's integrated gamepad is always present even when playing via touch.
    private boolean lastInputWasTouch = true;
    private final Set<Integer> pressedGamepadButtons = new HashSet<>();
    private SharedPreferences preferences;
    private FrostFrontendView frontend;
    private FrostButtonMapper mapper;
    private final FrostSpeakerTest speakerTest = new FrostSpeakerTest();
    private Button menuButton;
    private LastStand2TouchControls touchControls;
    private boolean showingButtonMap;
    private boolean showingMovementSettings;
    private boolean updateChecking, updateCheckedThisLaunch, updateNoticeShown;
    private String updateStatusText = "Not checked yet.";
    private String updateLatestText = "Not checked";
    private String updateReleaseUrl;
    private AimController aimController;
    private boolean aimInFlight, aimPending;
    private float pendingAimX, pendingAimY;
    private int aimGeneration;
    private static final FrostControlProfile CONTROL_PROFILE = FrostControlProfile.of("the-last-stand-2",
        new FrostButtonMapper.Action("fire", "Fire (hold)", KeyEvent.KEYCODE_BUTTON_R2),
        new FrostButtonMapper.Action("select", "Pointer Click / Fire", KeyEvent.KEYCODE_BUTTON_A),
        new FrostButtonMapper.Action("reload", "Reload", KeyEvent.KEYCODE_BUTTON_X),
        new FrostButtonMapper.Action("swap", "Switch Weapons", KeyEvent.KEYCODE_BUTTON_Y),
        new FrostButtonMapper.Action("pause", "Game Options", KeyEvent.KEYCODE_BUTTON_THUMBR)
    );

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        preferences = getSharedPreferences("laststand2_settings", MODE_PRIVATE);
        mapper = CONTROL_PROFILE.load(preferences);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        if (gameFile().isFile()) startGame(); else showImport();
    }

    private File gameFile() { return new File(getFilesDir(), SWF_NAME); }

    private void showImport() {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("The Last Stand 2 — Android");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView details = new TextView(this);
        details.setText("Import your original The Last Stand 2 SWF to start. The file is stored privately by this app.");
        details.setTextColor(0xFFD1C9D1);
        details.setTextSize(15);
        details.setGravity(Gravity.CENTER);
        panel.addView(details);
        Button pick = new Button(this);
        pick.setText("SELECT SWF FILE");
        pick.setOnClickListener(v -> pickFile());
        panel.addView(pick);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        File tmp = new File(getFilesDir(), SWF_NAME + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(data.getData());
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("File could not be opened");
            byte[] buf = new byte[65536];
            int count;
            long total = 0;
            while ((count = in.read(buf)) > 0) {
                total += count;
                if (total > 40_000_000L) throw new IOException("SWF too large");
                out.write(buf, 0, count);
            }
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            showError("Unable to import: " + e.getMessage());
            return;
        }
        try (FileInputStream in = new FileInputStream(tmp)) {
            byte[] head = new byte[4];
            if (in.read(head) != 4 || head[1] != 'W' || head[2] != 'S' ||
                    (head[0] != 'C' && head[0] != 'F' && head[0] != 'Z'))
                throw new IOException("This is not a Flash SWF file");
            File dst = gameFile();
            if (dst.exists() && !dst.delete()) throw new IOException("Can't replace old file");
            if (!tmp.renameTo(dst)) throw new IOException("Can't save imported file");
            startGame();
        } catch (Exception e) {
            tmp.delete();
            showError("Unable to import: " + e.getMessage());
        }
    }

    private void showError(String message) {
        showImport();
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private void startGame() {
        final byte[] gameBytes;
        try (FileInputStream in = new FileInputStream(gameFile())) {
            gameBytes = LastStand2SceneMarker.patch(in);
        } catch (IOException error) {
            showError("Unable to load game: " + error.getMessage());
            return;
        }
        root.removeAllViews();
        aimGeneration++;
        aimInFlight = aimPending = false;
        if (webView != null) { webView.destroy(); webView = null; }
        WebView.setWebContentsDebuggingEnabled(
            (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void reportPhase(String phase) {
                if (!"combat".equals(phase) && !"menu".equals(phase)) return;
                runOnUiThread(() -> {
                    if (touchControls != null) touchControls.setCombat("combat".equals(phase));
                });
            }
        }, "LastStand2Host");

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/game/", path -> {
                if (!SWF_NAME.equals(path.startsWith("/") ? path.substring(1) : path)) return null;
                return new WebResourceResponse("application/x-shockwave-flash", null,
                    new java.io.ByteArrayInputStream(gameBytes));
            }).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                applyDisplayMode();
                if (frontend != null && frontend.isOpen()) {
                    view.evaluateJavascript(
                        "window.tls2SetMenuPaused && window.tls2SetMenuPaused(true)", null);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"appassets.androidplatform.net".equals(u.getHost()) || !"https".equals(u.getScheme()))
                    return new WebResourceResponse("text/plain", "UTF-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                return loader.shouldInterceptRequest(u);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"appassets.androidplatform.net".equals(request.getUrl().getHost());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.d("LastStand2Ruffle", m.message());
                return true;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        addAimController();
        addFrontend();
        String quality = new String[]{"low", "medium", "high"}[Math.max(0,
            Math.min(2, preferences.getInt("antialiasing", 0)))];
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html?quality=" + quality);
        webView.requestFocus();
    }

    private void addAimController() {
        aimController = new AimController(webView, new AimController.Input() {
            @Override public void aim(float x, float y) {
                noteGamepadInput();
                if (touchControls != null) touchControls.setAimPosition(x, y);
                jsAim(x, y);
            }
            @Override public void button(int code, boolean down, String source) {
                if (down) noteGamepadInput();
                String key = keyFor(code);
                if (key != null) jsKey(key, down, source);
            }
        });
    }

    private void jsKey(String key, boolean down, String source) {
        if (webView == null) return;
        // All values are fixed strings from the input map below, not user input.
        // A button edge uses the newest cursor, even if movement was coalesced
        // while the renderer was busy. Never coalesce presses or releases.
        webView.evaluateJavascript(takePendingAim() + "window.tls2Key && window.tls2Key('" + key + "'," + down + ",'" + source + "')", null);
    }

    /** D-pad always moves; movement on the analogue stick is also universal. */
    private String keyFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default:
                for (FrostButtonMapper.Action action : mapper.actions())
                    if (code == mapper.buttonFor(action.id)) return action.id;
                return null;
        }
    }

    private void releaseGameInputs() {
        aimPending = false;
        if (aimController != null) aimController.release();
        pressedGamepadButtons.clear();
        stickUp = stickDown = stickLeft = stickRight = false;
        hatUp = hatDown = hatLeft = hatRight = false;
        if (touchControls != null) touchControls.releaseAll();
        if (webView != null) {
            webView.evaluateJavascript("window.tls2ClearKeys && window.tls2ClearKeys()", null);
        }
    }

    private void applyDisplayMode() {
        if (webView != null) {
            String mode = preferences.getString("display_mode", "original");
            webView.evaluateJavascript("window.tls2SetDisplayMode && window.tls2SetDisplayMode('" +
                ("stretch".equals(mode) ? "stretch" : "original") + "')", null);
            webView.evaluateJavascript("window.tls2SetRenderResolution && window.tls2SetRenderResolution(" +
                Math.max(0, Math.min(2, preferences.getInt("render_resolution", 0))) + ")", null);
        }
    }

    private FrostFrontendView.Item item(String id, String name, String value, String description) {
        return new FrostFrontendView.Item(id, name, value, description);
    }

    private int cycle(String setting, int count, int direction) {
        int current = preferences.getInt(setting, 0);
        int next = (current + direction + count) % count;
        preferences.edit().putInt(setting, next).apply();
        return next;
    }

    private void addFrontend() {
        touchControls = new LastStand2TouchControls(this, new LastStand2TouchControls.Input() {
            @Override public void key(String key, boolean down) { jsKey(key, down, "touch"); }
            @Override public void pointer(String kind, float x, float y) {
                if (webView == null) return;
                if (!"cancel".equals(kind) && aimController != null) aimController.setPosition(x, y);
                if ("move".equals(kind)) { jsAim(x, y); return; }
                aimPending = false;
                webView.evaluateJavascript("window.tls2TouchPointer && window.tls2TouchPointer('" +
                    kind + "'," + x + "," + y + ")", null);
            }
        });
        root.addView(touchControls, new FrameLayout.LayoutParams(-1, -1));
        updateTouchControls();

        menuButton = new Button(this);
        menuButton.setText("☰");
        menuButton.setTextSize(17);
        menuButton.setTextColor(Color.WHITE);
        menuButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xAA28212B));
        menuButton.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(44),
            Gravity.TOP | Gravity.RIGHT);
        params.topMargin = dp(10);
        params.rightMargin = dp(12);
        root.addView(menuButton, params);

        frontend = new FrostFrontendView(this);
        frontend.setVisibility(View.GONE);
        root.addView(frontend, new FrameLayout.LayoutParams(-1, -1));
        frontend.setTouchControls(touchControls);
        frontend.setTabs(Arrays.asList(
            new FrostFrontendView.Tab("graphics", "Graphics"),
            new FrostFrontendView.Tab("sound", "Sound"),
            new FrostFrontendView.Tab("controls", "Controls"),
            new FrostFrontendView.Tab("touch", "Touch Settings"),
            new FrostFrontendView.Tab("updates", "Updates")
        ));
        frontend.setUpdateInfo("The Last Stand 2 " + LastStand2Updates.installedVersion(this),
            updateLatestText, updateStatusText);
        frontend.setBackListener(() -> {
            if (showingMovementSettings) {
                showingMovementSettings = false;
                return true;
            }
            return false;
        });
        frontend.setAdapter(new FrostFrontendView.Adapter() {
            @Override public List<FrostFrontendView.Item> itemsForTab(String tabId) {
                switch (tabId) {
                    case "graphics":
                        return Arrays.asList(item("display", "Display Mode",
                            "stretch".equals(preferences.getString("display_mode", "original"))
                                ? "Stretch to Screen" : "Original (800×420)",
                            "Original keeps correct proportions; Stretch fills the display"),
                            item("render_resolution", "Render Resolution",
                                new String[]{"800×420", "1600×840", "Device"}[Math.max(0,
                                    Math.min(2, preferences.getInt("render_resolution", 0)))],
                                "Lower resolutions reduce rendering load; changes apply immediately"),
                            item("antialiasing", "Antialiasing",
                                new String[]{"Off", "2×", "4×"}[Math.max(0,
                                    Math.min(2, preferences.getInt("antialiasing", 0)))],
                                "Off reduces rendering load; changes apply next launch"));
                    case "sound":
                        return Collections.singletonList(item("speaker_test",
                            "Speaker Test: Left → Right → Both", "",
                            "Listen to each speaker in order; gameplay pauses during the test"));
                    case "controls":
                        if (showingButtonMap) {
                            List<FrostFrontendView.Item> rows = new ArrayList<>();
                            rows.add(item("map_back", "Back to Controls", "", ""));
                            for (FrostButtonMapper.Action action : mapper.actions()) {
                                int binding = mapper.buttonFor(action.id);
                                rows.add(new FrostFrontendView.Item("map:" + action.id,
                                    action.name, FrostButtonMapper.buttonName(binding),
                                    "Press A, then the controller button to assign",
                                    binding == FrostButtonMapper.UNMAPPED));
                            }
                            rows.add(item("map_reset", "Restore Default Controls", "",
                                "Fire: R2 · Click: A · Reload: X · Switch: Y · Options: R3"));
                            return rows;
                        }
                        if (showingMovementSettings) {
                            return Arrays.asList(
                                item("movement_back", "Back to Controls", "", ""),
                                item("directions", "Movement Directions",
                                    preferences.getInt("movement_mode", 0) == 0 ? "8-way" : "4-way",
                                    "How the left stick combines movement directions"),
                                item("deadzone", "Left Stick Deadzone",
                                    new String[]{"Low", "Normal", "High"}[Math.max(0,
                                        Math.min(2, preferences.getInt("movement_deadzone", 1)))],
                                    "How far the stick moves before the character responds"),
                                item("invert_x", "Invert Movement X",
                                    preferences.getBoolean("invert_x", false) ? "On" : "Off", ""),
                                item("invert_y", "Invert Movement Y",
                                    preferences.getBoolean("invert_y", false) ? "On" : "Off", ""),
                                item("aim_speed", "Aim Speed", settingLabel("aim_speed"),
                                    "Right-stick pointer speed"),
                                item("aim_deadzone", "Aim Deadzone", settingLabel("aim_deadzone"), ""),
                                item("aim_invert_x", "Invert Aim X",
                                    preferences.getBoolean("aim_invert_x", false) ? "On" : "Off", ""),
                                item("aim_invert_y", "Invert Aim Y",
                                    preferences.getBoolean("aim_invert_y", false) ? "On" : "Off", ""),
                                item("movement_reset", "Reset Movement and Aiming", "", "")
                            );
                        }
                        return Arrays.asList(
                            item("button_mapping", "Button Mapping", "",
                                "Remap fire, pointer click, reload, weapon switch and game options"),
                            item("movement_settings", "Movement Settings", "",
                                "Configure movement and right-stick cursor aiming")
                        );
                    case "touch":
                        return Arrays.asList(
                            item("touch_mode", "Touch Controls",
                                new String[]{"Auto", "On", "Off"}[Math.max(0,
                                    Math.min(2, preferences.getInt("touch_mode", 0)))],
                                "Auto follows the last input: touchscreen or controller"),
                            item("touch_size", "Control Size",
                                new String[]{"Small", "Medium", "Large"}[Math.max(0,
                                    Math.min(2, preferences.getInt("touch_size", 1)))], ""),
                            item("touch_opacity", "Control Opacity",
                                new String[]{"Low", "Medium", "High"}[Math.max(0,
                                    Math.min(2, preferences.getInt("touch_opacity", 1)))], ""),
                            item("touch_pad", "Movement Pad",
                                preferences.getInt("touch_pad", 0) == 0 ? "Joystick" : "D-pad", ""),
                            item("touch_aim", "Touch Aiming",
                                preferences.getBoolean("touch_relative_aim", true) ? "Relative" : "Direct",
                                "Relative: swipe to aim, hold to fire; menus use direct taps"),
                            item("touch_aim_speed", "Touch Aim Speed",
                                new String[]{"Slow", "Normal", "Fast"}[Math.max(0,
                                    Math.min(2, preferences.getInt("touch_aim_speed", 1)))], ""),
                            item("touch_sides", "Control Sides",
                                preferences.getBoolean("touch_sides", false) ? "Swapped" : "Standard", ""),
                            item("touch_position", "Control Position",
                                touchControls.hasCustomLayout() ? "Custom" :
                                    new String[]{"Edge", "Normal", "Inset"}[Math.max(0,
                                        Math.min(2, preferences.getInt("touch_position", 1)))],
                                "Choosing a preset replaces manual positions"),
                            item("touch_reset", "Reset Touch Controls", "", "")
                        );
                    case "updates":
                        List<FrostFrontendView.Item> updateRows = new ArrayList<>();
                        updateRows.add(item("version", "Installed Version",
                            LastStand2Updates.installedVersion(MainActivity.this),
                            "Current installed Android APK"));
                        updateRows.add(item("check", "Check for Updates",
                            updateChecking ? "Checking..." : updateLatestText,
                            updateStatusText));
                        updateRows.add(item("automatic", "Check Automatically",
                            preferences.getBoolean("updates_automatic", true) ? "On" : "Off",
                            "Check GitHub for new stable Android versions at startup"));
                        if (updateReleaseUrl != null)
                            updateRows.add(item("release", "View Available Release",
                                updateLatestText, "Open the verified GitHub release page"));
                        return updateRows;
                    default: return Collections.emptyList();
                }
            }

            @Override public void onItemSelected(String tabId, String itemId) {
                handleItem(itemId, 1);
            }

            @Override public void onItemAdjusted(String tabId, String itemId, int direction) {
                if (itemId.equals("display") || itemId.equals("render_resolution") || itemId.equals("antialiasing") || itemId.equals("automatic") ||
                    itemId.equals("directions") ||
                    itemId.equals("deadzone") || itemId.startsWith("aim_") || itemId.startsWith("invert_") ||
                    itemId.startsWith("touch_") && !itemId.equals("touch_reset"))
                    handleItem(itemId, direction);
            }

            @Override public void onVisibilityChanged(boolean visible) {
                releaseGameInputs();
                if (webView != null) {
                    webView.evaluateJavascript("window.tls2SetMenuPaused && window.tls2SetMenuPaused(" +
                        visible + ")", null);
                }
                if (!visible) {
                    speakerTest.stop();
                    showingButtonMap = false;
                    showingMovementSettings = false;
                    if (webView != null) webView.requestFocus();
                }
                if (menuButton != null) menuButton.setVisibility(visible ? View.GONE : View.VISIBLE);
                updateTouchControls();
            }
        });
        menuButton.setOnClickListener(v -> frontend.open());
        startUpdateCheck(false);
    }

    private void handleItem(String id, int direction) {
        if ("antialiasing".equals(id)) {
            cycle(id, 3, direction);
            return;
        }
        if ("speaker_test".equals(id)) {
            if (speakerTest.start())
                android.widget.Toast.makeText(this, "LEFT, then RIGHT, then BOTH",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if ("display".equals(id)) {
            String next = "stretch".equals(preferences.getString("display_mode", "original"))
                ? "original" : "stretch";
            preferences.edit().putString("display_mode", next).apply();
            applyDisplayMode();
            updateTouchControls();
            return;
        }
        if ("render_resolution".equals(id)) {
            cycle(id, 3, direction);
            applyDisplayMode();
            return;
        }
        if ("button_mapping".equals(id)) {
            showingButtonMap = true;
            showingMovementSettings = false;
            frontend.enterButtonMapping(mapper, () -> showingButtonMap = false);
            return;
        }
        if ("map_back".equals(id)) { frontend.requestMappingBack(); return; }
        if ("map_reset".equals(id)) {
            mapper.resetDefaults();
            mapper.save(preferences, CONTROL_PROFILE.preferencePrefix());
            return;
        }
        if (id.startsWith("map:")) {
            String actionId = id.substring(4);
            frontend.beginButtonCapture(actionId, new FrostFrontendView.ButtonCaptureListener() {
                @Override public void onCaptured(int code) {
                    FrostButtonMapper.Change change = mapper.assign(actionId, code);
                    mapper.save(preferences, CONTROL_PROFILE.preferencePrefix());
                    releaseGameInputs();
                    if (change.unmappedAction != null) {
                        frontend.showMappingNotice(change.unmappedAction + " is now Unmapped");
                    }
                    frontend.refresh();
                }
                @Override public void onCancelled() {}
            });
            return;
        }
        if ("movement_settings".equals(id)) {
            showingMovementSettings = true;
            return;
        }
        if ("movement_back".equals(id)) { showingMovementSettings = false; return; }
        if ("movement_reset".equals(id)) {
            preferences.edit().putInt("movement_mode", 0).putInt("movement_deadzone", 1)
                .putBoolean("invert_x", false).putBoolean("invert_y", false)
                .putInt("aim_speed", 1).putInt("aim_deadzone", 1)
                .putBoolean("aim_invert_x", false).putBoolean("aim_invert_y", false).apply();
            return;
        }
        if ("aim_speed".equals(id) || "aim_deadzone".equals(id)) {
            int v = preferences.getInt(id, 1);
            preferences.edit().putInt(id, (v + direction + 3) % 3).apply();
            return;
        }
        if (id.startsWith("aim_invert_")) {
            preferences.edit().putBoolean(id, !preferences.getBoolean(id, false)).apply();
            return;
        }
        if ("directions".equals(id)) { cycle("movement_mode", 2, direction); return; }
        if ("deadzone".equals(id)) {
            int v = preferences.getInt("movement_deadzone", 1);
            preferences.edit().putInt("movement_deadzone", (v + direction + 3) % 3).apply();
            return;
        }
        if ("invert_x".equals(id) || "invert_y".equals(id) || "touch_sides".equals(id)) {
            preferences.edit().putBoolean(id, !preferences.getBoolean(id, false)).apply();
            updateTouchControls();
            return;
        }
        if ("touch_mode".equals(id)) cycle("touch_mode", 3, direction);
        else if ("touch_size".equals(id)) {
            int v = preferences.getInt(id, 1);
            preferences.edit().putInt(id, (v + direction + 3) % 3).apply();
        } else if ("touch_opacity".equals(id)) {
            int v = preferences.getInt(id, 1);
            preferences.edit().putInt(id, (v + direction + 3) % 3).apply();
        } else if ("touch_pad".equals(id)) cycle(id, 2, direction);
        else if ("touch_aim".equals(id))
            preferences.edit().putBoolean("touch_relative_aim",
                !preferences.getBoolean("touch_relative_aim", true)).apply();
        else if ("touch_aim_speed".equals(id)) {
            int v = preferences.getInt(id, 1);
            preferences.edit().putInt(id, (v + direction + 3) % 3).apply();
        }
        else if ("touch_position".equals(id)) {
            int v = preferences.getInt(id, 1);
            preferences.edit().putInt(id, (v + direction + 3) % 3).apply();
            touchControls.resetLayout();
        } else if ("touch_reset".equals(id)) {
            preferences.edit().putInt("touch_mode", 0).putInt("touch_size", 1)
                .putInt("touch_opacity", 1).putInt("touch_pad", 0)
                .putBoolean("touch_sides", false).putInt("touch_position", 1)
                .putBoolean("touch_relative_aim", true).putInt("touch_aim_speed", 1).apply();
            touchControls.resetLayout();
        } else if ("check".equals(id)) {
            startUpdateCheck(true);
            return;
        } else if ("automatic".equals(id)) {
            boolean enabled = !preferences.getBoolean("updates_automatic", true);
            preferences.edit().putBoolean("updates_automatic", enabled).apply();
            if (enabled) startUpdateCheck(true);
            return;
        } else if ("release".equals(id)) {
            openUpdateRelease();
            return;
        }
        updateTouchControls();
    }



    /**
     * Manual and opt-in automatic GitHub checks happen outside the UI thread.
     * No APK is fetched or installed; the user must choose to open GitHub.
     */
    private void startUpdateCheck(boolean manual) {
        if (updateChecking) return;
        if (!manual) {
            if (!preferences.getBoolean("updates_automatic", true) ||
                updateCheckedThisLaunch) return;
            updateCheckedThisLaunch = true;
        }
        updateChecking = true;
        updateStatusText = "Checking published GitHub releases...";
        updateReleaseUrl = null;
        updateLatestText = "Checking...";
        refreshUpdateRows();
        new Thread(() -> {
            LastStand2Updates.Result result = LastStand2Updates.check(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateChecking = false;
                updateStatusText = result.status;
                updateLatestText = result.latest;
                updateReleaseUrl = result.releaseUrl;
                refreshUpdateRows();
                if (result.available && !updateNoticeShown) {
                    updateNoticeShown = true;
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("The Last Stand 2 update available")
                        .setMessage("A new stable Android version (" + result.latest +
                            ") is published on GitHub.")
                        .setPositiveButton("View Release", (dialog, which) -> openUpdateRelease())
                        .setNegativeButton("Later", null)
                        .show();
                }
            });
        }, "LastStand2-update-check").start();
    }

    private void refreshUpdateRows() {
        if (frontend != null) {
            frontend.setUpdateInfo("The Last Stand 2 " + LastStand2Updates.installedVersion(this),
                updateLatestText, updateStatusText);
            if (frontend.isOpen()) frontend.refresh();
        }
    }

    private void openUpdateRelease() {
        if (updateReleaseUrl == null ||
            !updateReleaseUrl.matches("https://github\\.com/Frostie77/The-Last-Stand-2/releases/tag/v[0-9]+\\.[0-9]+\\.[0-9]+"))
            return;
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updateReleaseUrl)));
    }

    /** Switch Auto controls only when the input method actually changes. */
    private void noteGamepadInput() {
        if (lastInputWasTouch) {
            lastInputWasTouch = false;
            updateTouchControls();
        }
        // Android buttons and the touch overlay can retain native focus after
        // settings or an input-method change. Restore the WebView before its
        // JS bridge focuses Ruffle; remapping cannot repair a focus loss.
        if (webView != null && !webView.hasFocus()) webView.requestFocus();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Re-enable touch controls on a touch gesture, even though the Odin's
        // physical gamepad always appears connected. The menu manages itself.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN &&
            webView != null && frontend != null && !frontend.isOpen() &&
            preferences != null && preferences.getInt("touch_mode", 0) == 0 &&
            !lastInputWasTouch) {
            lastInputWasTouch = true;
            updateTouchControls();
        }
        return super.dispatchTouchEvent(event);
    }

    private void updateTouchControls() {
        if (touchControls == null) return;
        int mode = preferences.getInt("touch_mode", 0);
        boolean show = webView != null && (frontend != null && frontend.isEditingTouchLayout() ||
            (frontend == null || !frontend.isOpen()) && (mode == 1 || mode == 0 && lastInputWasTouch));
        if (!show) touchControls.releaseAll();
        touchControls.setVisibility(show ? View.VISIBLE : View.GONE);
        touchControls.configure(preferences.getInt("touch_size", 1),
            preferences.getInt("touch_opacity", 1),
            preferences.getInt("touch_pad", 0),
            preferences.getBoolean("touch_sides", false),
            preferences.getInt("touch_position", 1));
        touchControls.configureAim(preferences.getBoolean("touch_relative_aim", true),
            preferences.getInt("touch_aim_speed", 1),
            "stretch".equals(preferences.getString("display_mode", "original")));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (frontend != null && frontend.isOpen()) return frontend.handleKeyEvent(event);
        if (frontend != null && code == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                frontend.open();
            return true;
        }
        String key = keyFor(code);
        if (key != null && webView != null &&
                (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                noteGamepadInput();
                if (!pressedGamepadButtons.add(code)) return true;
            } else {
                if (!pressedGamepadButtons.remove(code)) return true;
            }
            jsKey(key, event.getAction() == KeyEvent.ACTION_DOWN, "button" + code);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (frontend != null && frontend.handleGenericMotionEvent(e)) return true;
        if (webView != null && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                e.getAction() == MotionEvent.ACTION_MOVE) {
            if (frontend != null && frontend.isOpen()) return true;
            aimController.configure(preferences.getInt("aim_speed", 1),
                preferences.getInt("aim_deadzone", 1),
                preferences.getBoolean("aim_invert_x", false),
                preferences.getBoolean("aim_invert_y", false));
            if (aimController.accept(e)) noteGamepadInput();
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            // Don't hide virtual controls for neutral background joystick events.
            if (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f ||
                Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f)
                noteGamepadInput();
            // A physical D-pad can report HAT_X/HAT_Y rather than DPAD keycodes.
            // Use a separate JS input source so it works simultaneously with
            // the left stick, keycode D-pad and touch movement.
            boolean hl = hx < -0.5f, hr = hx > 0.5f;
            boolean hu = hy < -0.5f, hd = hy > 0.5f;
            if (hl != hatLeft) { jsKey("left", hl, "hat"); hatLeft = hl; }
            if (hr != hatRight) { jsKey("right", hr, "hat"); hatRight = hr; }
            if (hu != hatUp) { jsKey("up", hu, "hat"); hatUp = hu; }
            if (hd != hatDown) { jsKey("down", hd, "hat"); hatDown = hd; }

            if (preferences.getBoolean("invert_x", false)) x = -x;
            if (preferences.getBoolean("invert_y", false)) y = -y;
            float zone = new float[]{0.25f, 0.35f, 0.48f}[Math.max(0,
                Math.min(2, preferences.getInt("movement_deadzone", 1)))];
            if (preferences.getInt("movement_mode", 0) == 1) {
                if (Math.abs(x) >= Math.abs(y)) y = 0f; else x = 0f;
            }
            boolean l = x < -zone, r = x > zone, u = y < -zone, d = y > zone;
            if (l != stickLeft) { jsKey("left", l, "stick"); stickLeft = l; }
            if (r != stickRight) { jsKey("right", r, "stick"); stickRight = r; }
            if (u != stickUp) { jsKey("up", u, "stick"); stickUp = u; }
            if (d != stickDown) { jsKey("down", d, "stick"); stickDown = d; }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override protected void onPause() {
        if (webView != null) {
            releaseGameInputs();
            webView.evaluateJavascript("window.tls2SetBackgroundPaused && window.tls2SetBackgroundPaused(true)", null);
            webView.onPause();
        }
        speakerTest.stop();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.tls2SetBackgroundPaused && window.tls2SetBackgroundPaused(false)", null);
        }
        updateTouchControls();
    }

    @Override protected void onDestroy() {
        speakerTest.stop();
        releaseGameInputs();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (frontend != null && frontend.isOpen()) frontend.back();
        else super.onBackPressed();
    }

    private String settingLabel(String name) {
        int value = Math.max(0, Math.min(2, preferences.getInt(name, 1)));
        return ("aim_speed".equals(name) ? new String[]{"Slow", "Normal", "Fast"}
            : new String[]{"Low", "Normal", "High"})[value];
    }

    private void jsAim(float x, float y) {
        if (webView == null) return;
        pendingAimX = x; pendingAimY = y; aimPending = true;
        dispatchPendingAim();
    }

    private String takePendingAim() {
        if (!aimPending) return "";
        aimPending = false;
        return "window.tls2Aim && window.tls2Aim(" + pendingAimX + "," + pendingAimY + ");";
    }

    private void dispatchPendingAim() {
        if (webView == null || aimInFlight || !aimPending) return;
        WebView target = webView;
        int generation = aimGeneration;
        String script = takePendingAim();
        aimInFlight = true;
        target.evaluateJavascript(script, ignored -> {
            if (target != webView || generation != aimGeneration) return;
            aimInFlight = false;
            dispatchPendingAim();
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) releaseGameInputs();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
