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

/* 2. native plugins --------------------------------------------------------- */
const plugins = ["CellSignalPlugin", "NativePrintPlugin", "NativeFilePlugin"];
for (const p of plugins) {
  fs.copyFileSync(`android-src/${p}.java`, path.join(javaDir, `${p}.java`));
}

/* 3. MainActivity --------------------------------------------------------- */
fs.writeFileSync(path.join(javaDir, "MainActivity.java"), `package ${pkg};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
${plugins.map(p => `        registerPlugin(${p}.class);`).join("\n")}
        super.onCreate(savedInstanceState);
    }
}
`);

/* 3b. FileProvider paths (for opening the exported .docx) ----------------- */
const fpPath = "android/app/src/main/res/xml/file_paths.xml";
if (fs.existsSync(fpPath)) {
  fs.writeFileSync(fpPath, `<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="cache" path="." />
    <external-cache-path name="external_cache" path="." />
    <external-files-path name="external_files" path="." />
    <external-path name="external" path="." />
    <files-path name="files" path="." />
</paths>
`);
}

/* 4. version name / code -------------------------------------------------- */
const version = fs.readFileSync("VERSION", "utf8").trim();       // e.g. 0.1.1
const code = parseInt(process.env.GITHUB_RUN_NUMBER || "1", 10);
const gradlePath = "android/app/build.gradle";
let gradle = fs.readFileSync(gradlePath, "utf8");
gradle = gradle
  .replace(/versionName\s+"[^"]*"/, `versionName "${version}"`)
  .replace(/versionCode\s+\d+/, `versionCode ${code}`);
fs.writeFileSync(gradlePath, gradle);

console.log(`patched: permissions + ${plugins.join(", ")} + MainActivity + version ${version} (code ${code})`);
