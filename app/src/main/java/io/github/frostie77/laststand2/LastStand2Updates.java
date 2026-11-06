package io.github.frostie77.laststand2;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The public APK only checks a verified stable-release feed and opens GitHub.
 * No APK downloading, silent installation or access to the user's SWF/saves.
 * All calls to check() must be made off the Android UI thread.
 */
final class LastStand2Updates {
    static final String REPOSITORY = "Frostie77/The-Last-Stand-2";
    static final String PACKAGE = "io.github.frostie77.laststand2";
    static final String FEED_URL =
        "https://raw.githubusercontent.com/" + REPOSITORY + "/main/updates/android-stable.json";
    static final String RELEASE_BASE = "https://github.com/" + REPOSITORY + "/releases/tag/";
    private static final String RELEASE_API =
        "https://api.github.com/repos/" + REPOSITORY + "/releases/tags/";
    private static final int FEED_LIMIT = 16 * 1024;
    private static final int RELEASE_LIMIT = 192 * 1024;
    private static boolean noticeShownThisProcess;

    static synchronized boolean claimNotice() {
        if (noticeShownThisProcess) return false;
        noticeShownThisProcess = true;
        return true;
    }

    static final class Result {
        final String status;
        final String latest;
        final String releaseUrl;
        final boolean available;
        final boolean verified;
        Result(String status, String latest, String releaseUrl,
               boolean available, boolean verified) {
            this.status = status;
            this.latest = latest;
            this.releaseUrl = releaseUrl;
            this.available = available;
            this.verified = verified;
        }
    }

    private LastStand2Updates() {}

    static String installedVersion(Context context) {
        try {
            PackageInfo installed = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return installed.versionName == null ? "Unknown" : installed.versionName;
        } catch (Exception error) {
            return "Unknown";
        }
    }

    static long installedCode(Context context) {
        try {
            PackageInfo installed = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() :
                installed.versionCode;
        } catch (Exception ignored) {
            return -1;
        }
    }

    static boolean isSignedRelease(Context context) {
        return PACKAGE.equals(context.getPackageName()) &&
            (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0;
    }

    static Result check(Context context) {
        if (!isSignedRelease(context))
            return new Result("Stable updates require the signed release APK.",
                "Release builds only", null, false, false);
        try {
            final PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES :
                    PackageManager.GET_SIGNATURES);
            final String installedCert = certificateSha256(installed);
            if (installedCert.isEmpty()) throw new IOException("Release signing identity unavailable");

            JSONObject feed = new JSONObject(readHttps(FEED_URL, FEED_LIMIT));
            require(feed.getInt("schema_version") == 1, "Unsupported update feed");
            require("stable".equals(feed.getString("channel")), "Unexpected release channel");
            require(PACKAGE.equals(feed.getString("package")), "Wrong Android package");
            if (feed.isNull("latest"))
                return new Result("No public Android release has been announced yet.",
                    "Not published", null, false, true);

            JSONObject latest = feed.getJSONObject("latest");
            long newCode = integer(latest, "version_code");
            String version = latest.getString("version_name");
            require(version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"),
                "Invalid release version");
            String tag = latest.getString("release_tag");
            require(tag.equals("v" + version), "Release tag mismatch");
            require(latest.getString("source_commit").matches("[a-f0-9]{40}"),
                "Missing verified release source");
            require(PACKAGE.equals(latest.getString("package")), "Different Android package");
            require(newCode > 0 && newCode <= 2100000000L, "Invalid Android version code");
            require(installedCert.equalsIgnoreCase(latest.getString("certificate_sha256")),
                "Different Android signing certificate");
            int minSdk = (int) integer(latest, "min_sdk");
            require(minSdk >= 26 && Build.VERSION.SDK_INT >= minSdk,
                "Release requires a newer Android version");
            String apkName = latest.getString("apk_asset");
            require(apkName.equals("The-Last-Stand-2-v" + version + "-Android.apk"), "Unexpected APK name");
            long apkSize = integer(latest, "apk_size");
            require(apkSize > 0 && apkSize <= Integer.MAX_VALUE, "Invalid APK size");
            String apkHash = latest.getString("apk_sha256");
            require(apkHash.matches("[a-f0-9]{64}"), "Invalid APK SHA-256");

            final long installedCode = Build.VERSION.SDK_INT >= 28 ?
                installed.getLongVersionCode() : installed.versionCode;
            if (newCode <= installedCode)
                return new Result("You have the latest published Android release.",
                    "v" + version, RELEASE_BASE + tag, false, true);

            // Never offer a version based only on a changed repository file.
            // It must match one published, non-prerelease GitHub APK asset.
            JSONObject release = new JSONObject(readHttps(RELEASE_API + tag, RELEASE_LIMIT));
            require(tag.equals(release.getString("tag_name")), "GitHub release tag mismatch");
            require(!release.getBoolean("draft") && !release.getBoolean("prerelease") &&
                !release.isNull("published_at") &&
                !release.getString("published_at").isEmpty(), "Release is not public stable");
            require((RELEASE_BASE + tag).equals(release.getString("html_url")),
                "Unexpected release destination");
            JSONArray assets = release.getJSONArray("assets");
            require(assets.length() <= 100, "Too many release assets");
            int matches = 0;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!apkName.equals(asset.optString("name"))) continue;
                matches++;
                require("uploaded".equals(asset.getString("state")), "APK upload incomplete");
                require(apkSize == integer(asset, "size"), "APK size mismatch");
                require(("sha256:" + apkHash).equals(asset.getString("digest")),
                    "APK SHA-256 mismatch");
                require(("https://github.com/" + REPOSITORY + "/releases/download/" +
                    tag + "/" + apkName).equals(asset.getString("browser_download_url")),
                    "Unexpected APK download location");
            }
            require(matches == 1, "Signed Android APK not found on release");
            return new Result("Update available: v" + version, "v" + version,
                RELEASE_BASE + tag, true, true);
        } catch (Exception error) {
            return new Result("Could not verify updates. Check internet or try again later.",
                "Unavailable", null, false, false);
        }
    }

    private static long integer(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        require(value instanceof Integer || value instanceof Long,
            "Expected integer: " + name);
        return ((Number) value).longValue();
    }

    private static void require(boolean condition, String error) throws JSONException {
        if (!condition) throw new JSONException(error);
    }

    private static String certificateSha256(PackageInfo packageInfo) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ?
            packageInfo.signingInfo.getApkContentsSigners() : packageInfo.signatures;
        if (signatures == null || signatures.length != 1) return "";
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(signatures[0].toByteArray());
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String readHttps(String address, int maxBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "LastStand2-Android-Updates");
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("HTTP " + connection.getResponseCode());
            if (connection.getContentLengthLong() > maxBytes)
                throw new IOException("Update metadata exceeds size limit");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > maxBytes)
                        throw new IOException("Update response exceeds limit");
                    bytes.write(buffer, 0, count);
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
