#!/bin/sh
set -e
GRADLE_VERSION="9.6.0"
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_HOME="$BASE_DIR/.gradle-local/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$BASE_DIR/.gradle-local/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$BASE_DIR/.gradle-local"
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -fL "$GRADLE_URL" -o "$GRADLE_ZIP"
  unzip -q -o "$GRADLE_ZIP" -d "$BASE_DIR/.gradle-local"
  rm -f "$GRADLE_ZIP"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
