// Runs in CI after `npx cap add android`, before `npx cap sync`.
// 1. adds the permissions this survey app needs
// 2. drops in the native CellSignalPlugin
// 3. rewrites MainActivity to register it
const fs = require("fs");
const path = require("path");

const cfg = JSON.parse(fs.readFileSync("capacitor.config.json", "utf8"));
const pkg = cfg.appId;                       // e.g. com.tsl.rfsurvey
const javaDir = path.join("android/app/src/main/java", pkg.replace(/\./g, "/"));

/* 1. permissions ------------------------------------------------------------ */
const manifestPath = "android/app/src/main/AndroidManifest.xml";
let xml = fs.readFileSync(manifestPath, "utf8");

const perms = [
  "android.permission.INTERNET",
  "android.permission.ACCESS_NETWORK_STATE",
  "android.permission.CAMERA",
  "android.permission.READ_MEDIA_IMAGES",
  "android.permission.READ_PHONE_STATE",
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.ACCESS_COARSE_LOCATION",
];
const inject = line => {
  xml = xml.replace(/(\n[ \t]*)<application\b/, `$1${line}$1<application`);
};
for (const p of perms) {
  if (!xml.includes(`"${p}"`)) inject(`<uses-permission android:name="${p}" />`);
}
if (!xml.includes("READ_EXTERNAL_STORAGE")) {
  inject('<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />');
}
if (!xml.includes('android:name="android.hardware.camera"')) {
  inject('<uses-feature android:name="android.hardware.camera" android:required="false" />');
}
fs.writeFileSync(manifestPath, xml);

/* 2. native plugin ------------------------------------------------------------ */
fs.copyFileSync("android-src/CellSignalPlugin.java", path.join(javaDir, "CellSignalPlugin.java"));

/* 3. MainActivity ----------------------------------------------------------- */
fs.writeFileSync(path.join(javaDir, "MainActivity.java"), `package ${pkg};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CellSignalPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

console.log("patched: permissions + CellSignalPlugin + MainActivity");
