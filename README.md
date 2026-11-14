# BlockWorld

BlockWorld is an original Android voxel sandbox prototype built with Kotlin and OpenGL ES 2.0. It does not contain Minecraft source code or assets.

## Current version: 0.2.0

- Procedural 48 × 48 block terrain
- First-person camera with multi-touch look controls
- Virtual movement pad
- Jump, break and place controls
- Grass, dirt, stone, wood and leaf blocks
- Simple tree landmark and a cleared spawn area
- Local world edits saved between app launches
- GitHub Actions build for an installable debug APK

## Touch controls

- **Lower-left area:** drag to walk in the chosen direction.
- **Right side:** drag to look around.
- **JUMP:** tap the left-most round button on the right.
- **BREAK:** tap the middle round button to remove the block under the crosshair.
- **PLACE:** tap the right-most round button to place a dirt block in the adjacent empty cell.

## Build an APK

1. Open the repository's **Actions** tab.
2. Select **Android APK**.
3. Press **Run workflow**, or push a commit to `main`.
4. Open the completed run and download **BlockWorld-debug-v0.2.0** from Artifacts.
5. Extract the ZIP and install `app-debug.apk` on your Android device.

Requirements: Android 7.0 (API 24) or newer and OpenGL ES 2.0 support.

## Important

This is still an early sandbox foundation. It does not yet include chunk streaming, an inventory/crafting system, mobs, audio, multiplayer, or a full survival mode.
