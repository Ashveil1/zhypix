# Zhypix (fresh)

A clean-room rebuild of the Zhypix Android agent: chat with an AI model that can
see your screen and act on your phone - observe, one action, settle, verify.

Written from scratch. Design ideas come from an architecture write-up supplied by
the project owner; all code is original.

## Build

CI builds `debug` and `beta` (minified, release-like but debug-signed) APKs and
uploads them as artifacts. Local build needs JDK 17 and the Android SDK:
`gradle :app:assembleDebug` (create `debug.keystore` first - see CI workflow).
