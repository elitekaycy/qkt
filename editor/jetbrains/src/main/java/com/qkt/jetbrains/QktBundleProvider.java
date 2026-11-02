package com.qkt.jetbrains;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;

/** Serves the TextMate bundle packaged inside this plugin, so no Settings step is needed. */
public final class QktBundleProvider implements TextMateBundleProvider {
    @Override
    public List<PluginBundle> getBundles() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("com.qkt.jetbrains"));
        if (plugin == null) return List.of();
        Path bundle = plugin.getPluginPath().resolve("bundle");
        return List.of(new PluginBundle("qkt", bundle));
    }
}
