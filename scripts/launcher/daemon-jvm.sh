# `qkt daemon` runs for weeks with every strategy in one JVM. Without a heap setting the JVM takes 25% of the
# container's memory limit and, when that runs out, keeps a thrashing process alive whose strategies halt one by one
# (#1645). So the daemon sizes its heap from the limit, writes one heap dump into the state directory (a fixed name:
# the JVM never overwrites it, so a restart loop cannot fill the disk) and exits on the first OutOfMemoryError for its
# supervisor to restart it. JAVA_OPTS and QKT_OPTS come later on the command line, so either overrides any of these,
# e.g. QKT_OPTS="-XX:MaxRAMPercentage=50" or "-XX:-ExitOnOutOfMemoryError".
if [ "$1" = daemon ]; then
    qkt_state_dir=${QKT_STATE_DIR:-$HOME/.local/state/qkt}
    DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS \"-XX:MaxRAMPercentage=70\" \"-XX:+HeapDumpOnOutOfMemoryError\" \"-XX:HeapDumpPath=$qkt_state_dir/daemon-oom.hprof\" \"-XX:+ExitOnOutOfMemoryError\""
fi
