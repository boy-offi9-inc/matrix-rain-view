# matrix-rain-view

A configurable "digital rain" `View` for Android — the falling-glyph effect
from *The Matrix*, as a real, drop-in library. Not a tutorial demo, not a
one-off wallpaper app: a proper custom `View` with a real attribute API,
correct animation lifecycle (starts on attach, stops on detach — no leaks,
no battery drain off-screen), and delta-time-based motion so it doesn't
speed up or slow down depending on device refresh rate.

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
    implementation("com.github.boy-offi9-inc:matrix-rain-view:1.0.0")
}
```

## Usage

### As a full-screen effect

```xml
<com.boyoffi9.matrixrainview.MatrixRainView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:rainColor="#00FF41"
    app:rainSpeed="1.2"
    app:rainDensity="1.0"
    app:rainGlow="true" />
```

### As a background behind real content

`LinearLayout` arranges children edge-to-edge and can't stack them, so if
you want the rain running *behind* other UI, wrap both in a `FrameLayout`
(or `ConstraintLayout`) instead, with the rain view as the first/bottom
child:

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

## Attribute reference

| Attribute            | Type    | Default    | Description                                                        |
|-----------------------|---------|-----------|----------------------------------------------------------------------|
| `rainColor`           | color   | `#00FF41` | Glyph color                                                          |
| `rainSpeed`           | float   | `1.0`     | Fall speed multiplier                                                |
| `rainDensity`         | float   | `1.0`     | Column density multiplier (0.1–3.0)                                  |
| `rainTextSize`        | dimension | `16sp`  | Glyph size                                                            |
| `rainGlow`            | boolean | `true`    | Glow on the head glyph of each column                                |
| `rainFadeStrength`    | integer | `32`      | Trail fade strength, 4–255. Lower = longer-lingering trails          |
| `rainCharSet`         | enum    | `katakana`| `katakana`, `binary`, `alnum`, or `custom`                           |
| `rainCustomChars`     | string  | —         | Glyph pool used when `rainCharSet="custom"`                          |

## How it works

Each frame:
1. A translucent black rectangle is drawn over the previous frame instead of
   clearing it — this produces the fading trail (this is the classic trick
   behind every Matrix-rain implementation, web or native).
2. Each column's head position advances by `delta_time * baseRate * speed * columnVariance`,
   so motion is smooth and consistent across devices/refresh rates.
3. Glyphs occasionally mutate mid-column for the flicker look real Matrix
   rain has.
4. Columns that fall past the bottom reset to a random position above the
   view with a new speed/trail length, so the rain never looks obviously
   looped.

## License

MIT — see [LICENSE](./LICENSE).
