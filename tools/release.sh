#!/usr/bin/env bash
# Выпуск новой версии FreddyAI: тесты → подписанный APK → коммит и тег → релиз на GitHub.
# Установленные приложения увидят релиз и предложат обновиться.
#   tools/release.sh 2.2.1 "Что нового"
# Нужны: JDK 17, Android SDK, вошедший gh и файл ключа подписи.
#   FREDDY_SIGNING     — файл keystore.properties (по умолчанию D:/DevTools/keys/keystore.properties)
#   FREDDY_BUILD_ROOT  — папка сборки вне проекта, если путь к проекту длинный или с кириллицей
set -euo pipefail
VER="${1:?Укажи версию, например 2.2.1}"; NOTES="${2:-Новая версия $1}"
cd "$(dirname "$0")/.."
SIGNING="${FREDDY_SIGNING:-D:/DevTools/keys/keystore.properties}"
[ -f "$SIGNING" ] || { echo "Нет файла ключа подписи: $SIGNING"; exit 1; }
sed -i -E 's/(orNull \?: ")[0-9]+\.[0-9]+\.[0-9]+"/\1'"$VER"'"/' app/build.gradle.kts
grep -q "?: \"$VER\"" app/build.gradle.kts || { echo "Не удалось записать версию"; exit 1; }

ARGS=(--no-daemon -q)
OUTDIR="app/build"
if [ -n "${FREDDY_BUILD_ROOT:-}" ]; then ARGS+=(-Pfreddy.buildRoot="$FREDDY_BUILD_ROOT"); OUTDIR="$FREDDY_BUILD_ROOT/app"; fi
./gradlew "${ARGS[@]}" :app:testDebugUnitTest
./gradlew "${ARGS[@]}" -Pfreddy.signing="$SIGNING" :app:assembleRelease

OUT="build/FreddyAI-$VER.apk"; mkdir -p build
cp "$OUTDIR/outputs/apk/release/app-release.apk" "$OUT"
git add -A
git commit -m "FreddyAI $VER" -m "$NOTES" ${FREDDY_COMMIT_TRAILER:+-m "$FREDDY_COMMIT_TRAILER"}
git tag "v$VER"
git push
git push origin "v$VER"
gh release create "v$VER" "$OUT" --title "FreddyAI $VER" --notes "$NOTES"
echo "Готово: релиз v$VER опубликован."
