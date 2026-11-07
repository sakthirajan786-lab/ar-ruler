# AR Ruler (Android)

A simple, fast AR tape measure built on Google ARCore.

## Features
- Aim the centre circle at a surface (turns green when locked on), tap **+** for the start point, tap **+** again for the end point.
- Live dashed "rubber band" reading while you move to the end point, with jitter smoothing.
- Multiple measurements on screen at once, each with its own label.
- Units: cm, m, in, ft-in (tap the unit button; your choice is remembered).
- Undo last point, clear all.
- Works on horizontal **and** vertical surfaces (floors, tables, walls). It uses the ARCore Depth API on devices that support it for better hits on uneven surfaces.
- Points are anchored, so they stay in place as you walk around.
- Clear guidance messages (too dark, moving too fast, need more texture, etc.).

## Performance design
- Only the camera feed is drawn with OpenGL. Lines and labels are drawn in a lightweight 2D overlay, which keeps them crisp on every GPU.
- No AndroidX/Material libraries. The only dependency is ARCore, so the APK is small (≈ 3–5 MB) and starts fast.
- The UI is updated only when text actually changes, and the anchor count is capped to keep tracking cheap.
- R8 minification and resource shrinking are on for release builds.

## Get the APK (no Android Studio needed)
1. Create a new **empty** repository on GitHub. It can be private.
2. Upload this whole folder to it, keeping the `.github` folder. You can drag and drop the files on GitHub's "uploading an existing file" page, or use git:
   ```
   git init && git add . && git commit -m "AR Ruler"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the **Actions** tab. The "Build APK" workflow runs automatically and takes about 4–6 minutes.
4. Open **Releases** (right side of the repo page) and download **ARRuler.apk**.
5. On your phone, open the APK and allow "Install unknown apps" for your browser or file manager when asked.

## Build with Android Studio instead
Open the folder in Android Studio (Ladybug or newer), then use **Build → Generate App Bundles or APKs → Build APK(s)**.
Command line: `./gradlew assembleRelease` gives you `app/build/outputs/apk/release/app-release.apk`.

## Requirements
- An ARCore-supported Android phone running Android 7.0 or newer (see Google's "ARCore supported devices" list).
- "Google Play Services for AR". The app offers to install it if it's missing.

## Notes
- Typical accuracy is about ±1–2 cm at arm's length on well-lit, textured surfaces. Accuracy drops on plain white walls, shiny or dark surfaces, and over long distances.
- The release build is signed with the bundled `app/release.keystore` (password `arruler123`), so every build installs as an update over the previous one. **Replace it with your own private key before publishing on Google Play.**
