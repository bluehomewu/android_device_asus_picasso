Picasso per-SIM NR preferences
=============================

Material3 Compose UI for the shipped ASUS QTI radio 2.5 deployment preference:
  0 = SA + NSA
  1 = NSA only
  2 = SA only
This does not change the allowed-RAT bitmap, enable VoNR, or implement Android's
temporary N1 API. LTE and other allowed RATs remain available.

Integration
-----------
PicassoNrSettings is a platform-signed system_ext privileged app with its own
enforcing SELinux domain, not system UID. The initial installation must include
the device policy and privilege allowlist in an OTA; sideloading just the APK on
a ROM without this integration does not grant the required radio access.
The launcher opens the UI without accepting mode/subscription Intent commands.
The injected Network & Internet Settings entry requires NETWORK_SETTINGS.
There is no exported provider, no ROM-side Settings patch, and no network access.
Only the system user can own the backend. Device-policy restrictions are checked
again before dispatch. No SIM means no radio commands or migrated preference.

The client uses the existing qtiradio slot1/slot2 instances directly. No RIL,
modem firmware, NV data, or foreign telephony framework JAR is replaced.
extphonelib is not a dependency: it binds com.qti.phone.ExtTelephonyService,
which is absent in this ASUS stack. The shipped com.qualcomm.qti.telephonyservice
service implements GBA/IMPI instead, and cannot substitute for ExtPhone.
Adding the client JAR and its library XML alone would not supply that server.
QTI 2.5 setCallback owns a single callback pair per slot. Do not add another
QtiRadio client or ExtPhone server without consolidating callback ownership.

State and failure handling
--------------------------
Preferences are persisted per subscription, not per default data SIM. The
controller serializes each slot, cancels queued obsolete work, validates current
SIM mapping before dispatch, and ignores stale responses. A successful SET
alone never means applied: a matching successful QUERY is also required.
ASUS queryNrConfig can return its RIL cache. A matching configuration readback is
not proof of SA registration, RF capability, provisioning, or a VoNR call.
UI shows desired and last reported configurations separately, including pending
and failure states. SA-only needs a fresh confirmation scoped to this lifecycle
and SIM mapping. Dynamic Material3 colors and matching launch backgrounds avoid
the former theme mismatch; visual behavior still requires hardware testing.

The legacy global persist.radio.tel.5g_sa_off property defaults to 0 in the ROM.
Before issuing per-slot requests, the app durably imports an existing 0/1/2
choice once, then sets -1: the ASUS sentinel that disables the global override.
Do not restore -1 to 0 at each boot while this app owns per-SIM preferences.
When removing the app again, deliberate migration of that sentinel is needed.
Old saved per-SIM preferences are preserved across these application revisions.

Tests
-----
  nr/tests/run-tests.sh
  nr/storage-tests/run.sh
  nr/transport-tests/run.sh [path/to/original/qti-telephony-common.jar]
  nr/ui-tests/run.sh

The optional stock JAR audit checks the actual generated DEX transaction
contract and callback ABI hashes without installing that obsolete framework.
After the OTA, test both SIMs, mode readback, airplane-mode recovery, SIM removal
while confirming SA-only, rotation/backgrounding, and reboot persistence. Check
ordinary calls/data and IMS as well; do not use emergency numbers as tests.
Actual SA/VoNR needs a provisioned SIM and coverage and is not established by
these host tests, the mode label, or a 5G status icon.
