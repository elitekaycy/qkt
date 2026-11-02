package com.qkt.jetbrains;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds the qkt launcher: {@code $QKT_HOME}, then {@code PATH}, then the {@code qkt-path} file that
 * {@code qkt editor install} writes, which covers a qkt that is on no path at all.
 */
final class QktCommand {
    private QktCommand() {}

    static Path find(Map<String, String> env, Path recordedPathFile, boolean windows) {
        List<Path> dirs = new ArrayList<>();
        String home = env.get("QKT_HOME");
        if (home != null && !home.isBlank()) {
            dirs.add(Path.of(home, "bin"));
            dirs.add(Path.of(home));
        }
        String path = env.get("PATH");
        if (path != null) {
            for (String dir : path.split(windows ? ";" : ":")) {
                if (dir.isBlank()) continue;
                try {
                    dirs.add(Path.of(dir.replace("\"", "")));
                } catch (InvalidPathException ignored) {
                    // A malformed PATH entry names nothing to search.
                }
            }
        }
        List<String> names = windows ? List.of("qkt.exe", "qkt.bat", "qkt.cmd") : List.of("qkt");
        for (Path dir : dirs) {
            for (String name : names) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        try {
            if (Files.isRegularFile(recordedPathFile)) {
                Path recorded = Path.of(Files.readString(recordedPathFile).trim());
                if (Files.isRegularFile(recorded)) return recorded;
            }
        } catch (IOException | InvalidPathException ignored) {
            // An unreadable record is the same as none.
        }
        return null;
    }

    /** Mirrors qkt's own config directory, where {@code qkt editor install} writes {@code qkt-path}. */
    static Path recordedPathFile(Map<String, String> env, Path userHome, boolean windows) {
        String appData = env.get("APPDATA");
        String xdg = env.get("XDG_CONFIG_HOME");
        Path config;
        if (windows && appData != null && !appData.isBlank()) {
            config = Path.of(appData, "qkt");
        } else if (xdg != null && !xdg.isBlank()) {
            config = Path.of(xdg, "qkt");
        } else {
            config = userHome.resolve(".config").resolve("qkt");
        }
        return config.resolve("qkt-path");
    }
}
