# Run on the first Java 21 or newer, so an older JAVA_HOME or a Java on PATH does not stop qkt. Each
# candidate's version comes from its `release` file, which costs no JVM start.
qkt_java_ok() {
    [ -r "$1/release" ] || return 1
    qkt_major=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release")
    [ -n "$qkt_major" ] && [ "$qkt_major" -ge 21 ]
}
qkt_find_java() {
    for qkt_home in "$QKT_JAVA_HOME" "$APP_HOME/runtime" "$JAVA_HOME"; do
        if qkt_java_ok "$qkt_home"; then echo "$qkt_home"; return 0; fi
    done
    if qkt_java=$(command -v java); then
        qkt_java=$(readlink -f "$qkt_java" 2>/dev/null || echo "$qkt_java")
        qkt_home=$(dirname "$(dirname "$qkt_java")")
        if qkt_java_ok "$qkt_home"; then echo "$qkt_home"; return 0; fi
    fi
    if [ -x /usr/libexec/java_home ]; then
        qkt_home=$(/usr/libexec/java_home -v 21+ 2>/dev/null)
        if qkt_java_ok "$qkt_home"; then echo "$qkt_home"; return 0; fi
    fi
    for qkt_home in "$HOME"/.jdks/* "$HOME"/.sdkman/candidates/java/* "$HOME"/.gradle/jdks/* /usr/lib/jvm/* \
        /Library/Java/JavaVirtualMachines/*/Contents/Home "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
        "$QKT_FALLBACK_JAVA_HOME"; do
        if qkt_java_ok "$qkt_home"; then echo "$qkt_home"; return 0; fi
    done
    return 1
}
if ! JAVA_HOME=$(qkt_find_java); then
    echo "qkt: needs Java 21 or newer, and found none. Install one from https://adoptium.net or use a release" >&2
    echo "     build, which bundles Java: https://docs.tryqkt.com/get-started/install/" >&2
    exit 1
fi

