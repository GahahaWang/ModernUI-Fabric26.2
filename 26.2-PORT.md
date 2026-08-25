# Modern UI — 26.2 Port

This source tree ports the upstream `BloCamLimb/ModernUI-MC` master snapshot to Minecraft 26.2 while retaining `dev.icyllis:modernui-core:3.13.0` unchanged. All three loader targets — Fabric, Forge and NeoForge — build against 26.2.

## Build

Requirements: JDK 25 and network access for Gradle dependencies.

```powershell
.\gradlew.bat build
```

To configure and build a single loader without resolving the other loaders' coordinates:

```powershell
.\gradlew.bat -PfabricOnly :ModernUI-Fabric:build
.\gradlew.bat :ModernUI-Forge:build
.\gradlew.bat :ModernUI-NeoForge:build
```

The installable artifact is the `*-universal.jar` under `<loader>/build/libs/`.

`libs/ModernUI-Fonts-5.0.jar` carries the emoji images and the bundled fonts. It is merged
into the mod's own resources by `processResources`, so a development client (`runClient`)
has them as well; hanging it off the `shadow` configuration alone only ever reached the
universal jar, which is why a development client used to log `No Emoji font was found`.

## Target toolchain

- Minecraft 26.2
- Java 25
- Gradle 9.7.0
- Neo Loom 1.17.x (resolved as 1.17.9 during verification)
- Fabric Loader 0.19.3+
- Fabric API 0.152.1+ (built against 0.158.0+26.2)
- Forge 65.0.0+ (built against 65.1.2)
- NeoForge 26.2.0.0+ (built against 26.2.0.67)
- Forge Config API Port 26.2.0+ (built against 26.2.1)
- Mod Menu 20.0.1 (optional runtime integration)

## Porting scope

- Migrated Minecraft 26.1 GUI, texture, render-pipeline, font, text extraction, window and buffer APIs to their 26.2 equivalents.
- Updated mixins, access widener targets and text shaders for the 26.2 rendering model.
- Rebuilt world (3D) text rendering on top of `Font.prepareText` / `PreparedText.visit`, which
  replaced the removed `Font.drawInBatch`; see `ModernWorldPreparedText` and
  `MixinTextFeatureRenderer`. The glowing outline is again a single SDF stroke pass rather than
  vanilla's eight offset copies.
- Corrected the world text render pipelines in `TextRenderType`. Nothing exercised them before
  the step above, so two 26.2 changes had gone unnoticed: the bind group layout must match
  `RenderPipelines.WORLD_TEXT_SNIPPET` (`GLOBALS, MATRICES_PROJECTION, SAMPLER0, FOG, SAMPLER2`)
  rather than the GUI layout, and 26.2 switched to a reversed-Z depth buffer, so the depth test
  is `GREATER_THAN_OR_EQUAL` with a positive polygon offset. The GUI pipelines keep their own
  layout, since they are fed by `TextureSetup` instead of `RenderSetup`.
- Added `-PfabricOnly` so the Fabric target can be configured and built without resolving the Forge/NeoForge coordinates.
- Kept VulkanMod integration compile-only; it is not bundled or required at runtime.
- Did not modify the standalone `ModernUI` core repository or change the `modernui-core` dependency version/public API.

The Minecraft integration migration was cross-checked against the public 26.2 work in `Chino081/ModernUI-MC` (commits `249c928`, `8c34833`); NeoForge-only commit `86601a4` was excluded.

## Verification

- `gradlew build`: passed for `:common`, `:ModernUI-Fabric`, `:ModernUI-Forge` and `:ModernUI-NeoForge`, including tests and access-widener validation.
- A Fabric development client loads Minecraft 26.2 and reaches an in-game world with no
  class-load or Mixin errors.
- Sign text was confirmed rendering through the vanilla polygon-offset pipeline
  (`useTextShadersInWorld = false`), which is what established that the layout produced by
  `ModernWorldPreparedText` is correct. **The SDF world path is not yet confirmed in-game**:
  the bind group and reversed-Z depth fixes above were the reason it drew nothing, and they
  have not been re-tested with `useTextShadersInWorld = true`.
- The rest of the world text path (name tags, text displays, glowing outline) has not been
  verified in-game.
- Emoji and bundled fonts are present in `<loader>/build/resources/main` and in the universal
  jar (3583 images, byte-for-byte the same set as the source jar, no duplicates). A client run
  confirming a non-zero emoji map is still pending.
- Forge and NeoForge have only been verified to build; neither has been launched.
