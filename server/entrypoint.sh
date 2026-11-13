#!/bin/sh

cd /app

exec java \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --enable-native-access=ALL-UNNAMED \
    -server -Xms64m -Xmx64m \
    -jar app.jar "${port:-6000}" ${DATAHIKE_PATH:+"${DATAHIKE_PATH}"}
