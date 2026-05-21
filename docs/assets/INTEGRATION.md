# qkt brand assets — integration guide

## Files

| File | Use |
|---|---|
| `qkt-logo-dark.svg` | Wordmark for dark backgrounds (GitHub dark mode, terminals) |
| `qkt-logo-light.svg` | Wordmark for light backgrounds (GitHub light mode, light Dokka theme) |
| `qkt-mark-dark.svg` | Square `[k]` mark, dark variant — Dokka header, icons |
| `qkt-mark-light.svg` | Square `[k]` mark, light variant |
| `favicon.svg` | Adaptive favicon — auto-switches via `prefers-color-scheme` |
| `favicon.ico` | Multi-resolution `.ico` fallback (16×16 + 32×32) for older browsers |
| `favicon-16.png`, `favicon-32.png` | Raw PNG favicons if you'd rather link them explicitly |
| `apple-touch-icon.png` | 180×180 iOS home-screen icon |
| `og-image.png` | 1280×640 social preview card for GitHub, Twitter, OG metadata |
| `og-image.svg` | Vector source for `og-image.png` if you want to regenerate |

## Suggested repo layout

```
qkt/
├── docs/
│   └── assets/
│       ├── qkt-logo-dark.svg
│       ├── qkt-logo-light.svg
│       ├── qkt-mark-dark.svg
│       ├── qkt-mark-light.svg
│       ├── logo-icon.svg          ← rename qkt-mark-dark.svg to this for Dokka
│       ├── favicon.svg
│       ├── favicon.ico
│       ├── apple-touch-icon.png
│       └── og-image.png
└── README.md
```

`logo-icon.svg` is the specific filename Dokka looks for when replacing the
default logo. Use a copy or a symlink.

## 1 · README hero

Drop this at the very top of `README.md`, above the badges:

```markdown
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/qkt-logo-dark.svg">
    <img alt="qkt" src="docs/assets/qkt-logo-light.svg" width="240">
  </picture>
</p>
```

GitHub honors the `<picture>` tag — dark variant renders for users in dark mode,
light otherwise. `width="240"` gives an 80px-tall mark; bump to 280–320 for a
larger hero.

## 2 · Dokka — replace the default logo

For Dokka 1.x (current stable as of writing). In your `build.gradle.kts`:

```kotlin
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration

plugins {
    id("org.jetbrains.dokka") version "1.9.20"
}

buildscript {
    dependencies {
        classpath("org.jetbrains.dokka:dokka-base:1.9.20")
    }
}

tasks.dokkaHtml.configure {
    pluginConfiguration<DokkaBase, DokkaBaseConfiguration> {
        customAssets = listOf(
            rootProject.file("docs/assets/logo-icon.svg"),
        )
        customStyleSheets = listOf(
            rootProject.file("docs/assets/dokka-extras.css"),
        )
        footerMessage = "© qkt · Apache 2.0"
    }
}
```

Dokka treats any file named `logo-icon.svg` in `customAssets` as the replacement
for its built-in logo (the top-left mark in the header). The square `[k]` mark
fits this slot best — Dokka's logo container is square-ish.

If you want a favicon on the Dokka site too, add a small CSS file as
`dokka-extras.css`:

```css
/* docs/assets/dokka-extras.css */
.logo-title .library-name {
    font-family: ui-monospace, "JetBrains Mono", "SF Mono", monospace;
    letter-spacing: 0.05em;
}
```

(Dokka injects the favicon via its own mechanism if you also include
`favicon.svg` in `customAssets`. Some Dokka versions require you to override the
HTML template — easier path: post-process the generated HTML in your `docs.yml`
GitHub Action to inject a `<link rel="icon">` tag.)

## 3 · Docs site `<head>` (if your docs.yml has a wrapper HTML)

```html
<link rel="icon" type="image/svg+xml" href="/assets/favicon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/assets/favicon-32.png">
<link rel="icon" type="image/png" sizes="16x16" href="/assets/favicon-16.png">
<link rel="apple-touch-icon" sizes="180x180" href="/assets/apple-touch-icon.png">

<meta property="og:type" content="website">
<meta property="og:title" content="qkt — event-driven trading engine in Kotlin">
<meta property="og:description" content="Backtest replay, parameter sweeps, attribution-aware risk, SQL-like DSL.">
<meta property="og:image" content="https://elitekaycy.github.io/qkt/assets/og-image.png">
<meta property="og:url" content="https://elitekaycy.github.io/qkt/">

<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:image" content="https://elitekaycy.github.io/qkt/assets/og-image.png">
```

## 4 · GitHub social preview

Go to **Settings → Social preview → Upload an image** in your `qkt` repo and
upload `og-image.png`. This becomes the card that appears whenever the repo URL
is shared on Twitter, Slack, Discord, etc.

## 5 · Brand spec (for your own reference)

| Token | Value |
|---|---|
| Bracket color (dark bg) | `#a78bfa` |
| Bracket color (light bg) | `#7c3aed` |
| Letter color (dark bg) | `#e5e7eb` |
| Letter color (light bg) | `#0d1117` |
| Background (dark) | `#0d1117` |
| Background (light) | `#f6f8fa` |
| Cap height | 100 units |
| Stroke width | 14 units (14% of cap height) |
| Bracket serif length | 22 units |
| Type voice | Geometric monoline, lowercase, no fonts |

The wordmark is hand-drawn paths — no font file is required and rendering is
identical on every machine, browser, and screen size.
