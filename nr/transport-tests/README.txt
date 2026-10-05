Picasso NR transport host tests
===============================

Run with a Java 11+ JDK; no Android runtime, phone, network or modem is used:

  sh device/asus/picasso/nr/transport-tests/run.sh

Optionally supply the original ASUS 18.1055.2307.269 stock file:

  sh device/asus/picasso/nr/transport-tests/run.sh /path/to/qti-telephony-common.jar

The optional audit reads only that JAR, verifies its SHA-256, and checks the
original DEX hash arrays plus the six relevant generated Proxy transactions.
Known DEX offsets are intentionally pinned to that exact firmware hash. A
different file is rejected, not silently treated as the same contract. No
proprietary JAR or decompiled implementation is distributed with these tests.

The production QtiNrRadio.java is compiled directly against host-only typed
Android binder doubles. Coverage includes both slot names, version checking,
mode bounds, tokens, transaction numbers, serial preservation, oneway flags,
separate error/status/config fields, callback inheritance/hash layout, IBase,
invalid tokens/flags/truncation, remote failures, parcel cleanup and death
recipient cleanup. close() must not unregister a replacement owner's callbacks.
The controller separately owns stale-generation suppression after close/death.

The runner creates and removes one temporary compilation directory. These
tests do not establish real hwbinder/SELinux connectivity, modem persistence,
SIM identity mapping or actual network registration; device tests are still
required for those properties.
