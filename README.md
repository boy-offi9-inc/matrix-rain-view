# matrix-rain-view

Matrix-style digital rain for Android, drawn live on a `Canvas`. No GIFs, no Lottie, no video. Works in XML layouts and Jetpack Compose.

[![JitPack](https://jitpack.io/v/boy-offi9-inc/matrix-rain-view.svg?style=flat-square)](https://jitpack.io/#boy-offi9-inc/matrix-rain-view)
[![Build](https://img.shields.io/github/actions/workflow/status/boy-offi9-inc/matrix-rain-view/build-aar.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=build)](https://github.com/boy-offi9-inc/matrix-rain-view/actions/workflows/build-aar.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow?style=flat-square)](./LICENSE)
![minSdk 21](https://img.shields.io/badge/minSdk-21-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-supported-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Last commit](https://img.shields.io/github/last-commit/boy-offi9-inc/matrix-rain-view?style=flat-square)

<p align="center">
  <img src="https://raw.githubusercontent.com/boy-offi9-inc/matrix-rain-demo/main/assets/demo.gif" width="280" alt="MatrixRainView running behind real UI, with live settings">
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/boy-offi9-inc/matrix-rain-demo/main/assets/scene_2.jpg" width="30%" alt="Green katakana rain with the settings panel open">
  <img src="https://raw.githubusercontent.com/boy-offi9-inc/matrix-rain-demo/main/assets/scene_1.jpg" width="30%" alt="Red rain with the settings panel open">
  <img src="https://raw.githubusercontent.com/boy-offi9-inc/matrix-rain-demo/main/assets/scene_3.jpg" width="30%" alt="Cyan binary rain with the settings panel open">
</p>

The sample app runs the rain behind real UI and wires every property to a live control, in both XML and Compose: [matrix-rain-demo](https://github.com/boy-offi9-inc/matrix-rain-demo) ([screen recording](https://github.com/boy-offi9-inc/matrix-rain-demo/blob/main/assets/video.mp4)).

## Features

- Configurable color, speed, density, glyph size, trail fade and head glow
- Glyph sets: katakana, binary, alphanumeric, or your own characters
- Delta-time motion, so speed is the same on 60 Hz and 120 Hz screens
- Lifecycle-aware: starts on attach, stops on detach, plus `pause()` and `resume()`
- A standard `View` for XML, and a `MatrixRain` composable for Compose
- No asset files, no reflection, no ProGuard rules needed

## Why not a GIF or Lottie?

|                                 | GIF / video | Lottie | matrix-rain-view |
|---------------------------------|:-----------:|:------:|:----------------:|
| Ships no asset files            |      No     |   No   |        Yes       |
| Fits any screen size            |      No     | Partly |        Yes       |
| Change color/speed at runtime   |      No     | Partly |        Yes       |
| Custom glyph sets               |      No     |   No   |        Yes       |
| Stops itself when off-screen    |    Partly   |   Yes  |        Yes       |

## Requirements

- Android `minSdk` 21+
- AndroidX
- Kotlin (Java consumers can still use the view, since it's a standard `View` subclass with no Kotlin-only API surface)
- For the Compose wrapper: a Compose app (the library is built against Compose BOM `2024.06.00`)

## Install

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.boy-offi9-inc:matrix-rain-view:1.1.2")
}
```

## Usage

### Full-screen effect

```xml
<com.boyoffi9.matrixrainview.MatrixRainView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:rainColor="#00FF41"
    app:rainSpeed="1.2"
    app:rainDensity="1.0"
    app:rainGlow="true" />
```

### Background behind real content

A `LinearLayout` arranges children edge-to-edge and can't stack them. To run
the rain behind other UI, wrap both in a `FrameLayout` (or `ConstraintLayout`)
with the rain view as the first, bottom child:

```xml
<FrameLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.boyoffi9.matrixrainview.MatrixRainView
        android:id="@+id/matrixRain"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical">
        <!-- your real UI -->
    </LinearLayout>

</FrameLayout>
```

### Programmatic control

```kotlin
val rain = findViewById<MatrixRainView>(R.id.matrixRain)
rain.rainColor = Color.parseColor("#00FFAA")
rain.speed = 1.5f
rain.density = 0.8f
rain.charSet = MatrixRainView.CharSet.BINARY
rain.glowEnabled = false

rain.pause()   // stop animating without detaching the view
rain.resume()  // resume
```

### Jetpack Compose

```kotlin
import com.boyoffi9.matrixrainview.compose.MatrixRain

Box(Modifier.fillMaxSize()) {
    MatrixRain(
        modifier = Modifier.fillMaxSize(),
        rainColor = Color(0xFF00FF41),
        speed = 1.2f,
        density = 1.0f,
        glowEnabled = true,
    )
    // your real UI on top
}
```

Parameters are applied to the underlying view in place, and a setter ignores
a value that hasn't changed. Recomposition doesn't restart the animation, and
changing one parameter (say, the color) doesn't reshuffle the rain.

## Reference

### XML attributes

| Attribute          | Type      | Default    | Description                                                        |
|--------------------|-----------|------------|--------------------------------------------------------------------|
| `rainColor`        | color     | `#00FF41`  | Glyph color                                                        |
| `rainSpeed`        | float     | `1.0`      | Fall speed multiplier                                              |
| `rainDensity`      | float     | `1.0`      | Column density multiplier (0.1 to 3.0), spread across the full width |
| `rainTextSize`     | dimension | `16sp`     | Glyph size                                                         |
| `rainGlow`         | boolean   | `true`     | Glow on the head glyph of each column                              |
| `rainFadeStrength` | integer   | `32`       | Trail fade strength, 4 to 255. Lower means longer-lingering trails |
| `rainCharSet`      | enum      | `katakana` | `katakana`, `binary`, `alnum`, or `custom`                         |
| `rainCustomChars`  | string    | none       | Glyph pool used when `rainCharSet="custom"`                        |

### Compose parameters

| Parameter      | Type       | Default         | XML attribute      |
|----------------|------------|-----------------|--------------------|
| `modifier`     | `Modifier` | `fillMaxSize()` | none               |
| `rainColor`    | `Color`    | `#00FF41`       | `rainColor`        |
| `speed`        | `Float`    | `1f`            | `rainSpeed`        |
| `density`      | `Float`    | `1f`            | `rainDensity`      |
| `glowEnabled`  | `Boolean`  | `true`          | `rainGlow`         |
| `fadeStrength` | `Int`      | `32`            | `rainFadeStrength` |
| `textSizePx`   | `Float?`   | `null` (16sp)   | `rainTextSize`     |
| `charSet`      | `CharSet`  | `KATAKANA`      | `rainCharSet`      |
| `customChars`  | `String?`  | `null`          | `rainCustomChars`  |

## How it works

Each frame:

1. A translucent black rectangle is drawn over the previous frame instead of
   clearing it, which produces the fading trail (the classic trick behind
   every Matrix-rain implementation, web or native). It's drawn onto an
   offscreen `Bitmap`/`Canvas` the view owns and persists across frames, then
   blitted to the real canvas. A plain `View`'s `onDraw` canvas isn't
   guaranteed to retain its previous contents under hardware acceleration, so
   the trail needs its own backing buffer.
2. Each column's head position advances by `delta_time * baseRate * speed * columnVariance`,
   so motion is smooth and consistent across devices and refresh rates.
3. Glyphs occasionally mutate mid-column for the flicker look real Matrix rain has.
4. Columns that fall past the bottom reset to a random position above the
   view with a new speed and trail length, so the rain never looks obviously looped.

The trail buffer is released when the view detaches and rebuilt when it
re-attaches, so the view keeps working inside recycled lists, pagers and
fragment back stacks.

## Notes

- The rain paints its own dark backdrop (that's how the trails fade), so it's
  designed as a full-bleed background rather than a transparent overlay.
- The Compose wrapper ships in the same artifact, so Compose libraries come
  along as dependencies even for XML-only apps.

## Contributing

Issues and PRs are welcome. Please open an issue first for anything bigger
than a small fix, so we can agree on the approach.

## License

[MIT](./LICENSE) © 2026 Boy Offi9
