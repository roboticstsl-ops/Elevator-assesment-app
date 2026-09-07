// Adds the permissions this survey app needs into the Capacitor-generated
// AndroidManifest.xml. Runs in CI after `npx cap add android`.
const fs = require("fs");

const path = "android/app/src/main/AndroidManifest.xml";
let xml = fs.readFileSync(path, "utf8");

const lines = [
  '<uses-permission android:name="android.permission.INTERNET" />',
  '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
  '<uses-permission android:name="android.permission.CAMERA" />',
  '<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />',
  '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />',
  '<uses-feature android:name="android.hardware.camera" android:required="false" />',
];

const added = [];
for (const line of lines) {
  const name = line.match(/android:name="([^"]+)"/)[1];
  if (xml.includes(name)) continue;
  xml = xml.replace(/(\n[ \t]*)<application\b/, `$1${line}$1<application`);
  added.push(name);
}

fs.writeFileSync(path, xml);
console.log(added.length ? "Added: " + added.join(", ") : "Nothing to add (already present)");
