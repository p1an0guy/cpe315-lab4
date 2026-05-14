#!/usr/bin/env bash
set -u

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$ROOT_DIR/test_results"

mkdir -p "$RESULTS_DIR"

cd "$ROOT_DIR" || exit 1

echo "Compiling Java sources..."
if command -v javac >/dev/null 2>&1; then
    javac *.java || exit 1
elif [ -x /opt/jdk-16/bin/javac ]; then
    /opt/jdk-16/bin/javac *.java || exit 1
else
    echo "Could not find javac on PATH or at /opt/jdk-16/bin/javac" >&2
    exit 1
fi

normalize_output() {
    sed -E \
        -e 's/^[[:space:]]+//' \
        -e 's/[[:space:]]+$//' \
        -e 's/[[:space:]]+/ /g' \
        "$1"
}

failures=0

for asm_file in "$ROOT_DIR"/tests/*.asm; do
    test_name="$(basename "$asm_file" .asm)"
    script_file="$ROOT_DIR/scripts/$test_name.script"
    expected_file="$ROOT_DIR/outputs/$test_name.output"
    actual_file="$RESULTS_DIR/$test_name.actual"
    actual_normalized="$RESULTS_DIR/$test_name.actual.normalized"
    expected_normalized="$RESULTS_DIR/$test_name.expected.normalized"
    diff_file="$RESULTS_DIR/$test_name.diff"

    if [ ! -f "$script_file" ] || [ ! -f "$expected_file" ]; then
        echo "SKIP $test_name: missing script or expected output"
        continue
    fi

    echo "Running $test_name..."
    java lab4 "$asm_file" "$script_file" > "$actual_file"

    normalize_output "$actual_file" > "$actual_normalized"
    normalize_output "$expected_file" > "$expected_normalized"

    if diff -u "$expected_normalized" "$actual_normalized" > "$diff_file"; then
        echo "PASS $test_name"
        rm -f "$diff_file"
    else
        echo "FAIL $test_name"
        echo "  raw actual: $actual_file"
        echo "  diff:       $diff_file"
        failures=$((failures + 1))
    fi
done

if [ "$failures" -ne 0 ]; then
    echo "$failures test(s) failed"
    exit 1
fi

echo "All tests passed"
