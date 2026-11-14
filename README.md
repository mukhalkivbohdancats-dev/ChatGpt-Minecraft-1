# BlockWorld

**BlockWorld** is a native Android voxel sandbox prototype written in Kotlin with OpenGL ES 2.0. It is an original project and does not include Minecraft code, textures, or assets.

## Current gameplay
- A procedurally generated 32 × 32 block landscape
- First-person camera and touch-look
- On-screen movement pad
- Break and place blocks
- A small tree landmark
- World edits saved locally on the device
- GitHub Actions workflow that builds and uploads a debug APK

## Touch controls
- Drag the virtual pad in the lower-left corner to move.
- Drag on the right side of the screen to look around.
- Tap **BREAK** to remove the block in front of you.
- Tap **PLACE** to place a dirt block next to the targeted block.

## Build an APK
1. Open the **Actions** tab in this repository.
2. Select **Android APK**.
3. Press **Run workflow** (or push a commit to `main`).
4. Open the completed workflow run and download the `BlockWorld-debug` artifact.
5. Extract the ZIP and install the APK on an Android device.

The workflow uses JDK 17 and Gradle 8.9. The app targets Android 7.0 (API 24) and newer devices with OpenGL ES 2.0.

## Source layout
- `app/src/main/java/dev/mukhalkiv/blockworld/MainActivity.kt` — activity, touch input and HUD
- `app/src/main/java/dev/mukhalkiv/blockworld/WorldRenderer.kt` — world generation, rendering and block interactions
- `app/src/main/AndroidManifest.xml` — Android application configuration
- `.github/workflows/android.yml` — APK build workflow

This is an early playable foundation. Chunk streaming, inventory, crafting, mobs, sound and multiplayer are not implemented yet.
