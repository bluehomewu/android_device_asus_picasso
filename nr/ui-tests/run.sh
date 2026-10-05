#!/bin/sh
set -eu
ui_test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ui_test_out=$(mktemp -d "${TMPDIR:-/tmp}/picasso-nr-ui-test.XXXXXX")
trap 'rm -r -- "$ui_test_out"' EXIT HUP INT TERM
javac -Xlint:all -Werror -d "$ui_test_out" \
    "$ui_test_dir/../src/org/lineageos/settings/picasso/nr/NrUiSession.java" \
    "$ui_test_dir/NrUiSessionTest.java"
java -cp "$ui_test_out" org.lineageos.settings.picasso.nr.NrUiSessionTest
