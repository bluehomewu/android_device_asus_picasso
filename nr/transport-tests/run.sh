#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

transport_test_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
transport_test_out=$(mktemp -d "${TMPDIR:-/tmp}/picasso-nr-transport-test.XXXXXX")
trap 'rm -rf -- "$transport_test_out"' EXIT HUP INT TERM

"${JAVAC:-javac}" -Xlint:all -Werror -d "$transport_test_out" \
    "$transport_test_src"/android/os/*.java \
    "$transport_test_src"/QtiNrRadioTest.java \
    "$transport_test_src"/../src/org/lineageos/settings/picasso/nr/QtiNrRadio.java
"${JAVA:-java}" -ea -cp "$transport_test_out" \
    org.lineageos.settings.picasso.nr.QtiNrRadioTest "$@"
