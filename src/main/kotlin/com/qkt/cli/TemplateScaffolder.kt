package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies a packaged project template from the classpath to a target directory.
 *
 * Templates live under `templates/<kind>/` on the classpath. Each template has a
 * `MANIFEST` file listing every other resource (one relative path per line, blank
 * lines and `#` comments ignored). Files whose manifest entry ends with `.tmpl`
 * are written with the `.tmpl` suffix stripped and `{{TOKEN}}` placeholders
 * substituted from the supplied [tokens] map; non-`.tmpl` files copy verbatim.
 *
 * Token substitution uses simple text replace (`{{KEY}}` → value). A token that
 * appears in a template but has no entry in [tokens] passes through unchanged —
 * callers can use `qkt create` output to spot stray placeholders if they care.
 */
class TemplateScaffolder(
    private val classLoader: ClassLoader = TemplateScaffolder::class.java.classLoader,
) {
    sealed interface Result {
        data class Created(
            val filesWritten: List<Path>,
        ) : Result

        data class Failed(
            val reason: String,
        ) : Result
    }

    fun scaffold(
        kind: String,
        target: Path,
        tokens: Map<String, String> = emptyMap(),
    ): Result {
        val manifestResource = "templates/$kind/MANIFEST"
        val manifestStream =
            classLoader.getResourceAsStream(manifestResource)
                ?: return Result.Failed("unknown template kind '$kind' (no MANIFEST at $manifestResource)")
        val entries =
            manifestStream
                .bufferedReader()
                .use { it.readText() }
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

        if (Files.exists(target)) {
            val anyChild = Files.newDirectoryStream(target).use { it.iterator().hasNext() }
            if (anyChild) {
                return Result.Failed("target $target is not empty — refusing to overwrite")
            }
        } else {
            Files.createDirectories(target)
        }

        val written = mutableListOf<Path>()
        for (entry in entries) {
            val resourcePath = "templates/$kind/$entry"
            val stream =
                classLoader.getResourceAsStream(resourcePath)
                    ?: return Result.Failed("template entry missing from classpath: $resourcePath")
            val bytes = stream.use { it.readBytes() }
            val out =
                if (entry.endsWith(".tmpl")) {
                    val rendered = substitute(String(bytes, Charsets.UTF_8), tokens)
                    rendered.toByteArray(Charsets.UTF_8) to target.resolve(entry.removeSuffix(".tmpl"))
                } else {
                    bytes to target.resolve(entry)
                }
            val (data, dest) = out
            dest.parent?.let { Files.createDirectories(it) }
            Files.write(dest, data)
            written.add(dest)
        }
        return Result.Created(written)
    }

    private fun substitute(
        text: String,
        tokens: Map<String, String>,
    ): String {
        if (tokens.isEmpty()) return text
        var out = text
        for ((key, value) in tokens) {
            out = out.replace("{{$key}}", value)
        }
        return out
    }
}
