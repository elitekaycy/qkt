import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/** Short git sha of the repository HEAD, or `unknown` when git or the repo is unavailable. */
abstract class GitShaSource : ValueSource<String, GitShaSource.Params> {
    /** Where to run `git rev-parse`. */
    interface Params : ValueSourceParameters {
        val repoRoot: DirectoryProperty
    }

    @get:Inject
    abstract val execOps: ExecOperations

    override fun obtain(): String {
        val out = ByteArrayOutputStream()
        return try {
            val result =
                execOps.exec {
                    workingDir = parameters.repoRoot.get().asFile
                    commandLine("git", "rev-parse", "--short", "HEAD")
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            if (result.exitValue == 0) out.toString().trim() else "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
