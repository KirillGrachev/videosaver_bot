#!/usr/bin/env sh
# Saver Bot launcher for Linux/macOS.
# If Java is missing, downloads a Temurin 21 JRE into runtime/ and runs with it.
# If Java exists but is old, the launcher upgrades the runtime by itself.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
LAUNCHER="$HERE/saver-launcher.jar"

if [ ! -f "$LAUNCHER" ]; then
    echo "[start] ERROR: saver-launcher.jar was not found next to start.sh"
    echo "[start] Looked here: $LAUNCHER"
    exit 1
fi

# The JDK's own startup notices are noise in the bot log: JDK 22+ reports the native
# library load of sqlite-jdbc as a restricted System::load call, JDK 23+ reports the
# terminally deprecated sun.misc.Unsafe methods reached through Guava. Both flags
# silence those warnings; older JVMs reject unknown flags and would not start, so a
# flag is added only where the running JVM knows it.
jvm_flags() {
    FEATURE="$("$1" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
    case "$FEATURE" in
        ''|*[!0-9]*) FEATURE=0 ;;
    esac
    FLAGS=""
    if [ "$FEATURE" -ge 22 ]; then
        FLAGS="--enable-native-access=ALL-UNNAMED"
    fi
    if [ "$FEATURE" -ge 23 ]; then
        FLAGS="$FLAGS --sun-misc-unsafe-memory-access=allow"
    fi
    # shellcheck disable=SC2086 # the flags are ours: word splitting is intended
    echo $FLAGS
}

run() {
    JAVAEXE="$1"
    shift
    # shellcheck disable=SC2086 # the flags are ours: word splitting is intended
    exec "$JAVAEXE" $(jvm_flags "$JAVAEXE") -jar "$LAUNCHER" "$@"
}

if [ -f "$HERE/runtime/java.txt" ]; then
    run "$(cat "$HERE/runtime/java.txt")" "$@"
fi

if command -v java >/dev/null 2>&1; then
    run java "$@"
fi

echo "[start] Java was not found. Downloading Temurin 21 JRE into runtime/ (about 45 MB)..."
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$OS" in
    darwin) OS=mac ;;
    *) OS=linux ;;
esac
ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64) ARCH=aarch64 ;;
    *) ARCH=x64 ;;
esac
mkdir -p "$HERE/runtime"
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jre/hotspot/normal/eclipse" \
    -o "$HERE/runtime/jre21.tar.gz"
tar -xzf "$HERE/runtime/jre21.tar.gz" -C "$HERE/runtime"
rm -f "$HERE/runtime/jre21.tar.gz"
JAVAEXE="$(find "$HERE/runtime" -type f -path '*/bin/java' | head -1)"
echo "$JAVAEXE" > "$HERE/runtime/java.txt"
run "$JAVAEXE" "$@"

