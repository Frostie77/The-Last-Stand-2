#!/usr/bin/env python3
"""Exercise the production update verifier with Android API stubs and fake HTTPS."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JSON_SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
JSON_URL = 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar'
STUBS = {
    'android/content/Context.java': '''package android.content;
import android.content.pm.*;
public abstract class Context {
 public abstract String getPackageName();
 public abstract PackageManager getPackageManager();
 public abstract ApplicationInfo getApplicationInfo();
}''',
    'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo { public static final int FLAG_DEBUGGABLE=2; public int flags; }''',
    'android/content/pm/PackageInfo.java': '''package android.content.pm;
public class PackageInfo {
 public int versionCode; public String versionName;
 public SigningInfo signingInfo; public Signature[] signatures;
 public long getLongVersionCode() { return versionCode; }
}''',
    'android/content/pm/PackageManager.java': '''package android.content.pm;
public abstract class PackageManager {
 public static final int GET_SIGNING_CERTIFICATES=0x08000000,GET_SIGNATURES=64;
 public abstract PackageInfo getPackageInfo(String name,int flags) throws Exception;
}''',
    'android/content/pm/Signature.java': '''package android.content.pm;
public class Signature {
 private final byte[] bytes;
 public Signature(byte[] bytes) { this.bytes=bytes; }
 public byte[] toByteArray() { return bytes; }
}''',
    'android/content/pm/SigningInfo.java': '''package android.content.pm;
public class SigningInfo {
 private final Signature[] signatures;
 public SigningInfo(Signature[] signatures) { this.signatures=signatures; }
 public Signature[] getApkContentsSigners() { return signatures; }
}''',
    'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT=35; } }''',
}
DRIVER = r'''package io.github.frostie77.laststand2;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class UpdateVerifierTest {
 static final String PKG="io.github.frostie77.laststand2";
 static final String BASE="https://github.com/Frostie77/The-Last-Stand-2";
 static final byte[] CERT="fixture-release-certificate".getBytes(StandardCharsets.UTF_8);
 static final Map<String,String> responses=new HashMap<>();
 static int httpStatus=200,requests,passed;
 static JSONObject feed,release;
 static final class FakeContext extends Context {
  final ApplicationInfo app=new ApplicationInfo();
  final PackageInfo info=new PackageInfo();
  String name=PKG;
  FakeContext() {
   info.versionCode=2;info.versionName="1.0.0";
   info.signatures=new Signature[]{new Signature(CERT)};
   info.signingInfo=new SigningInfo(info.signatures);
  }
  public String getPackageName(){return name;}
  public ApplicationInfo getApplicationInfo(){return app;}
  public PackageManager getPackageManager(){return new PackageManager(){
   public PackageInfo getPackageInfo(String name,int flags){return info;}
  };}
 }
 static class FakeConnection extends HttpURLConnection {
  final byte[] bytes;
  FakeConnection(URL url){super(url);requests++;bytes=responses.getOrDefault(url.toString(),"").getBytes(StandardCharsets.UTF_8);}
  public int getResponseCode(){return httpStatus;}
  public long getContentLengthLong(){return bytes.length;}
  public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
  public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
 }
 static String certHash() throws Exception {
  StringBuilder out=new StringBuilder();
  for(byte b:MessageDigest.getInstance("SHA-256").digest(CERT))out.append(String.format("%02x",b&255));
  return out.toString();
 }
 static JSONObject latest(String version,int code) throws Exception {
  return new JSONObject().put("package",PKG).put("version_name",version).put("version_code",code)
   .put("release_tag","v"+version).put("source_commit","c".repeat(40))
   .put("certificate_sha256",certHash()).put("min_sdk",26)
   .put("apk_asset","The-Last-Stand-2-v"+version+"-Android.apk").put("apk_size",12345)
   .put("apk_sha256","a".repeat(64));
 }
 static JSONObject asset() throws Exception {
  return new JSONObject().put("name","The-Last-Stand-2-v1.0.1-Android.apk").put("state","uploaded")
   .put("size",12345).put("digest","sha256:"+"a".repeat(64))
   .put("browser_download_url",BASE+"/releases/download/v1.0.1/The-Last-Stand-2-v1.0.1-Android.apk");
 }
 static void reset() throws Exception {
  responses.clear();requests=0;httpStatus=200;Build.VERSION.SDK_INT=35;
  feed=new JSONObject().put("schema_version",1).put("channel","stable").put("package",PKG).put("latest",latest("1.0.1",3));
  release=new JSONObject().put("tag_name","v1.0.1").put("draft",false).put("prerelease",false)
   .put("published_at","2026-11-05T00:00:00Z").put("html_url",BASE+"/releases/tag/v1.0.1")
   .put("assets",new JSONArray().put(asset()));
 }
 static LastStand2Updates.Result check(FakeContext context) {
  responses.put(LastStand2Updates.FEED_URL,feed.toString());
  responses.put("https://api.github.com/repos/Frostie77/The-Last-Stand-2/releases/tags/v1.0.1",release.toString());
  return LastStand2Updates.check(context);
 }
 static void expect(String name,LastStand2Updates.Result result,boolean verified,boolean available) {
  if(result.verified!=verified||result.available!=available)
   throw new AssertionError(name+": "+result.status+" verified="+result.verified+" available="+result.available);
  passed++;System.out.println("PASS: "+name);
 }
 static void requests(int count) {if(requests!=count)throw new AssertionError("Expected "+count+" HTTPS requests, found "+requests);}
 public static void main(String[] args) throws Exception {
  URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){
   protected URLConnection openConnection(URL url){return new FakeConnection(url);}
  }:null);
  reset();expect("published newer release",check(new FakeContext()),true,true);requests(2);
  reset();feed.put("latest",latest("1.0.0",2));expect("installed current release",check(new FakeContext()),true,false);requests(1);
  reset();feed.put("latest",JSONObject.NULL);expect("no public release yet",check(new FakeContext()),true,false);requests(1);
  reset();FakeContext debug=new FakeContext();debug.app.flags=ApplicationInfo.FLAG_DEBUGGABLE;
  expect("Debug APK does not offer stable updates",check(debug),false,false);requests(0);
  reset();FakeContext other=new FakeContext();other.name="different.package";
  expect("different installed package",check(other),false,false);requests(0);
  reset();feed.put("package","different.package");expect("wrong feed package",check(new FakeContext()),false,false);
  reset();feed.getJSONObject("latest").put("certificate_sha256","b".repeat(64));expect("incompatible signing identity",check(new FakeContext()),false,false);
  reset();release.put("draft",true);expect("draft release rejected",check(new FakeContext()),false,false);
  reset();release.put("prerelease",true);expect("prerelease rejected",check(new FakeContext()),false,false);
  reset();release.getJSONArray("assets").getJSONObject(0).put("digest","sha256:"+"b".repeat(64));expect("wrong APK digest rejected",check(new FakeContext()),false,false);
  reset();release.getJSONArray("assets").getJSONObject(0).put("size",12346);expect("wrong APK size rejected",check(new FakeContext()),false,false);
  reset();release.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.com/other.apk");expect("wrong download location rejected",check(new FakeContext()),false,false);
  reset();release.getJSONArray("assets").put(asset());expect("duplicate APK asset rejected",check(new FakeContext()),false,false);
  reset();feed.getJSONObject("latest").put("min_sdk",36);expect("unsupported Android version rejected",check(new FakeContext()),false,false);
  reset();feed.getJSONObject("latest").put("version_code",3.5);expect("fractional version code rejected",check(new FakeContext()),false,false);
  reset();feed.getJSONObject("latest").put("release_tag","v2.0.0");expect("mismatched version tag rejected",check(new FakeContext()),false,false);
  reset();httpStatus=503;expect("offline service fails without offering update",check(new FakeContext()),false,false);
  reset();httpStatus=302;expect("redirect is not followed",check(new FakeContext()),false,false);requests(1);
  reset();FakeContext missing=new FakeContext();missing.info.signingInfo=null;expect("missing signing identity",check(missing),false,false);
  reset();Build.VERSION.SDK_INT=26;expect("Android 8 signing API fallback",check(new FakeContext()),true,true);requests(2);
  System.out.println("PASS: "+passed+" production update-verifier scenarios.");
 }
}'''

def main():
    cache = Path(tempfile.gettempdir()) / 'laststand2-update-verifier'
    cache.mkdir(exist_ok=True)
    jar = Path(os.environ.get('TLS2_JSON_JAR', str(cache / 'json-20240303.jar')))
    if not jar.exists():
        with urllib.request.urlopen(JSON_URL, timeout=30) as response:
            data = response.read(512_000)
        if hashlib.sha256(data).hexdigest() != JSON_SHA256:
            raise RuntimeError('JSON test dependency checksum mismatch')
        jar.write_bytes(data)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != JSON_SHA256:
        raise RuntimeError('JSON test dependency checksum mismatch')
    java = shutil.which('java')
    if not java:
        raise RuntimeError('JDK 17 java is required')
    with tempfile.TemporaryDirectory(prefix='laststand2-update-tests-') as temporary:
        work = Path(temporary)
        for name, source in STUBS.items():
            file = work / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(source, encoding='utf-8')
        package = work / 'io/github/frostie77/laststand2'
        package.mkdir(parents=True)
        shutil.copyfile(ROOT / 'app/src/main/java/io/github/frostie77/laststand2/LastStand2Updates.java', package / 'LastStand2Updates.java')
        (package / 'UpdateVerifierTest.java').write_text(DRIVER, encoding='utf-8')
        classes = work / 'classes'
        subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str,work.rglob('*.java'))], check=True)
        subprocess.run([java, '-cp', os.pathsep.join((str(classes),str(jar))), 'io.github.frostie77.laststand2.UpdateVerifierTest'], check=True)

if __name__ == '__main__':
    main()
