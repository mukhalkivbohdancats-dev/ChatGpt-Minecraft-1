# BlockWorld Android

BlockWorld is an original Android voxel sandbox prototype. It uses Kotlin (the Android/JVM ecosystem), native Android Views for interactive menus and touch buttons, and OpenGL ES for the current voxel renderer. It does not use Minecraft source code, textures, sounds, or branding.

## Version 0.5.0

### What is implemented

- Main menu with Continue, New World, Settings and controls help.
- Actual Android clickable controls, not labels painted into a non-interactive HUD.
- Separate touch areas for movement and camera look, plus real Jump, Break, Place, Inventory and Pause buttons.
- Multi-touch pointer tracking.
- Seed-based terrain generation in 16 × 16 horizontal chunks.
- Chunks stream around the player as they move; the world is not limited to the old 48 × 48 test map.
- Biome variation: plains, forest and desert.
- Surface terrain, water, trees and cave pockets.
- Underground coal, copper, iron, gold, redstone, lapis, diamond and emerald deposits.
- A small inventory with block counts, selectable items, placing and mining.
- Local persistence for world edits, inventory and world seed.
- Render-distance and camera-sensitivity settings.
- GitHub Actions builds a renamed APK artifact: `BlockWorld-Android.apk`.

### Touch controls

- **Lower-left joystick:** move.
- **Right side of the screen:** drag to look around.
- **JUMP:** jump.
- **BREAK:** mine the block beneath the crosshair and collect it.
- **PLACE:** place the selected block if one is available.
- **Hotbar:** choose a quick block.
- **INVENTORY:** view collected blocks and select one.
- **Pause button:** resume, save or return to the main menu.

### Build an APK

1. Open this repository's **Actions** tab.
2. Select **Android APK**.
3. Press **Run workflow**, or push a commit to `main`.
4. Open the successful workflow run.
5. Download the `BlockWorld-Android-v0.5.0` artifact ZIP.
6. Extract it; the APK inside is named `BlockWorld-Android.apk`.

The CI build uses JDK 17 and Gradle 8.9 with Android Gradle Plugin 8.7.3.

## Technical decision

The project stays in Kotlin/Android for now. That gives the app direct access to Android lifecycle, Views and pointer events without adding a Rust-to-Android bridge. Rust can still be evaluated later for isolated CPU-heavy systems if profiling justifies it.

The current renderer remains OpenGL ES 2.0 as a compatibility-first prototype. Android now recommends Vulkan for new high-performance game renderers, while OpenGL ES remains supported; a Vulkan renderer is a separate future migration, not something this version claims to have.

## Known limits

This is not a finished Minecraft replacement. It currently has no multiplayer, mobs, crafting recipes, survival needs, audio, animated water, texture atlas or full dynamic lighting. The world generator and touch controls must still be tested on the physical BPad 11; a successful CI build proves compilation and APK packaging, not that every interaction has been verified on the tablet.
