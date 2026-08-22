# Modern UI — Fabric 26.2 Port

This source tree ports the upstream `BloCamLimb/ModernUI-MC` master snapshot to Minecraft 26.2 / Fabric while retaining `dev.icyllis:modernui-core:3.13.0` unchanged.

## Build

Requirements: JDK 25 and network access for Gradle dependencies.

```powershell
.\gradlew.bat -PfabricOnly :ModernUI-Fabric:build
```

The installable artifact is the `*-universal.jar` under `fabric/build/libs/`.

## Target toolchain

- Minecraft 26.2
- Java 25
- Gradle 9.5.1
- Neo Loom 1.17.x (resolved as 1.17.9 during verification)
- Fabric Loader 0.19.3+
- Fabric API 0.152.1+ (built against 0.158.0+26.2)
- Forge Config API Port 26.2.0+ (built against 26.2.1)
- Mod Menu 20.0.0-beta.2 (optional runtime integration)

## Porting scope

- Migrated Minecraft 26.1 GUI, texture, render-pipeline, font, text extraction, window and buffer APIs to their 26.2 equivalents.
- Updated mixins, access widener targets and text shaders for the 26.2 rendering model.
- Added `-PfabricOnly` so the Fabric target can be configured and built without resolving unrelated Forge/NeoForge coordinates.
- Kept VulkanMod integration compile-only; it is not bundled or required at runtime.
- Did not modify the standalone `ModernUI` core repository or change the `modernui-core` dependency version/public API.

The Minecraft integration migration was cross-checked against the public 26.2 work in `Chino081/ModernUI-MC` (commits `249c928`, `8c34833`); NeoForge-only commit `86601a4` was excluded.

## Verification

- `:common:compileJava`: passed.
- `:ModernUI-Fabric:build`: passed, including tests and access-widener validation (14 tasks).
- A development-client smoke test reached the official asset-download phase without class-load or Mixin errors, but was intentionally stopped after the first-time asset download remained pending; reaching the main menu was not verified in this environment.

