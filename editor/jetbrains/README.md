# qkt for JetBrains IDEs

Highlighting (TextMate), completion, hover and live errors (`qkt lsp` through
[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)) for `.qkt` files, in IDEs from 2024.2 on.

## Install

```bash
qkt editor install intellij
```

That places this plugin and LSP4IJ in every JetBrains IDE qkt finds. Restart the IDE afterwards.

## Build

```bash
./gradlew -p editor/jetbrains buildPlugin                                      # downloads the IDE to compile against
./gradlew -p editor/jetbrains buildPlugin "-PlocalIde=/path/to/installed/ide"  # uses one you have
```

The zip lands in `build/distributions/`. The release moves it to `editor/jetbrains/` before packaging, so
it ships in `share/editor/jetbrains/`, where `qkt editor install intellij` picks it up.
