#!/usr/bin/env bash
set -euo pipefail
mvn -q -DskipTests -Dexec.mainClass="com.localbridge.gui.LocalBridgeGUI" \
    exec:java \
    -Dexec.jvmArgs="--module-path /usr/share/openjfx/lib --add-modules=javafx.controls,javafx.fxml"


