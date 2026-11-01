@rem Run on the first Java 21 or newer, so an older JAVA_HOME or a Java on PATH does not stop qkt. Each
@rem candidate's version comes from its release file, which costs no JVM start.
call :qktFindJava
if defined QKT_JAVA goto qktJavaFound
echo qkt: needs Java 21 or newer, and found none. Install one from https://adoptium.net or use a release 1>&2
echo      build, which bundles Java: https://docs.tryqkt.com/get-started/install/ 1>&2
goto fail
:qktJavaFound
set "JAVA_HOME=%QKT_JAVA%"

