#!/usr/bin/env sh
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/android/gradlew" -p "$DIR/android" "$@"
