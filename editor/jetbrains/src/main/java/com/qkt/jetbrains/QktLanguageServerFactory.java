package com.qkt.jetbrains;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import java.nio.file.Path;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/** Starts {@code qkt lsp}. The IDE's own Java is the launcher's last resort, so no JDK setup is needed. */
public final class QktLanguageServerFactory implements LanguageServerFactory {
    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        Map<String, String> env = System.getenv();
        boolean windows = SystemInfo.isWindows;
        Path recorded = QktCommand.recordedPathFile(env, Path.of(System.getProperty("user.home")), windows);
        Path qkt = QktCommand.find(env, recorded, windows);
        if (qkt == null) {
            throw new IllegalStateException("qkt was not found. Install qkt, then run: qkt editor install intellij");
        }
        GeneralCommandLine command =
                new GeneralCommandLine(qkt.toString(), "lsp")
                        .withEnvironment("QKT_FALLBACK_JAVA_HOME", System.getProperty("java.home"));
        return new OSProcessStreamConnectionProvider(command) {};
    }
}
