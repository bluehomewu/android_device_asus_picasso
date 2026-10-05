#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

storage_test_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
storage_test_out=$(mktemp -d "${TMPDIR:-/tmp}/picasso-nr-storage-test.XXXXXX")
trap 'rm -rf -- "$storage_test_out"' EXIT HUP INT TERM

"${JAVAC:-javac}" -Xlint:all -Werror -d "$storage_test_out" \
    "$storage_test_src"/NrPreferencesTest.java \
    "$storage_test_src"/../src/org/lineageos/settings/picasso/nr/NrPreferences.java
"${JAVA:-java}" -ea -cp "$storage_test_out" \
    org.lineageos.settings.picasso.nr.NrPreferencesTest "$@"
