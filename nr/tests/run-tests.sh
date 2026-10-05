#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
classes_dir=$(mktemp -d /tmp/picasso-nr-tests.XXXXXXXX)
trap 'rm -r -- "$classes_dir"' EXIT HUP INT TERM

"${JAVAC:-javac}" -Xlint:all -Werror -d "$classes_dir" \
    "$test_dir/../src/org/lineageos/settings/picasso/nr/NrModeController.java" \
    "$test_dir/NrModeControllerTest.java"
"${JAVA:-java}" -ea -cp "$classes_dir" \
    org.lineageos.settings.picasso.nr.NrModeControllerTest
