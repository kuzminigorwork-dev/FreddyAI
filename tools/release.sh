#!/usr/bin/env bash
# Выпуск новой версии FreddyAI: тесты → подписанный APK → коммит и тег → релиз на GitHub.
# Приложения у пользователей увидят релиз и предложат обновиться.
#   tools/release.sh 2.2.1 "Что нового"
# Нужны: JDK 17, Android SDK, Gradle, вошедший gh и файл ключа подписи (FREDDY_SIGNING).
set -euo pipefail
VER="${1:?Укажи версию, например 2.2.1}"; NOTES="${2:-}"
cd "$(dirname "$0")/.."
SIGNING="${FREDDY_SIGNING:-D:/DevTools/keys/keystore.properties}"
[ -f "$SIGNING" ] || { echo "Нет файла ключа подписи: $SIGNING"; exit 1; }
sed -i -E 's/(orNull \?: ")[0-9]+\.[0-9]+\.[0-9]+"/\1'"$VER"'"/' app/build.gradle.kts
grep -q "?: \"$VER\"" app/build.gradle.kts || { echo "Не удалось записать версию"; exit 1; }
gradle --no-daemon -q :app:testDebugUnitTest
gradle --no-daemon -q -Pfreddy.signing="$SIGNING" :app:assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
[ -n "${FREDDY_BUILD_ROOT:-}" ] && APK="$FREDDY_BUILD_ROOT/app/outputs/apk/release/app-release.apk"
OUT="build/FreddyAI-$VER.apk"; mkdir -p build; cp "$APK" "$OUT"
git add -A && git commit -m "FreddyAI $VER" && git tag "v$VER" && git push && git push origin "v$VER"
gh release create "v$VER" "$OUT" --title "FreddyAI $VER" --notes "${NOTES:-Новая версия $VER}"
echo "Готово: релиз v$VER опубликован."
