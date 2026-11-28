#!/bin/sh
# Everything to run before pushing to main (AGENTS.md, "How changes land").
# Needs ANDROID_HOME and JDK 17 or 21 (JAVA_HOME if the default JDK is another one).
set -e
cd "$(dirname "$0")/.."
python3 tools/check_docs.py
./gradlew testDebugUnitTest assembleDebug lint
./gradlew -p server test
