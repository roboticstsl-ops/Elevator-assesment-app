# RF Survey Sheet - APK build

No computer needed. GitHub builds the APK.

## One-time setup
1. github.com -> New repository -> name it, set **Public** -> Create.
2. Upload every file/folder from this folder, keeping the structure:
   - www/index.html
   - package.json
   - capacitor.config.json
   - .github/workflows/android.yml
3. Commit to the **main** branch.

## Get the APK
- Repo -> **Actions** tab -> "Build APK" run -> wait ~5 min for the green check.
- Open the run -> **Artifacts** -> download **RF-Survey-Sheet-apk** (a zip).
- Unzip -> `app-debug.apk`.

## Install on Android
- Move `app-debug.apk` to the phone -> tap it -> allow "install unknown apps" -> Install.

## Rebuild after a change
- Edit `www/index.html` in the repo (or re-upload it) and commit,
  OR Actions tab -> "Build APK" -> "Run workflow".
- Download the new APK from the new run.
