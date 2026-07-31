#!/usr/bin/env bash
# Stamps out a new Emerge app from apps/template/.
#
#   tools/new-app.sh mygame
#
# Copies the four template modules to apps/<name>/, renames every package, class and Gradle path,
# and wires the new modules into settings.gradle.kts. The result builds and runs immediately:
#
#   ./gradlew :apps:<name>:desktop:run
#
# Everything it produces is ordinary source you own — there is no generator to re-run and nothing
# links back to the template afterwards.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
template="$root/apps/template"

die() { echo "error: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "usage: $(basename "$0") <name>   (lowercase letters and digits, e.g. 'mygame')"

name="$1"
[[ "$name" =~ ^[a-z][a-z0-9]*$ ]] || die "'$name' must be lowercase letters/digits starting with a letter — it becomes a Kotlin package name and a Gradle path."
[[ "$name" != "template" ]] || die "'template' is the template itself."
[[ -d "$template" ]] || die "template not found at $template"
[[ ! -e "$root/apps/$name" ]] || die "apps/$name already exists."

# Class-name and constant forms: mygame -> Mygame, MYGAME.
cap="$(tr '[:lower:]' '[:upper:]' <<< "${name:0:1}")${name:1}"
upper="$(tr '[:lower:]' '[:upper:]' <<< "$name")"

dest="$root/apps/$name"
echo "==> copying apps/template -> apps/$name"
# README.md documents the template itself, so it is deliberately not carried over.
cp -r "$template" "$dest"
rm -f "$dest/README.md"

echo "==> renaming package directories"
# Depth-first, so renaming a parent doesn't invalidate the paths of its children.
find "$dest" -depth -type d -name template | while read -r dir; do
    mv "$dir" "$(dirname "$dir")/$name"
done

echo "==> renaming files"
find "$dest" -depth -type f -name 'Template*' | while read -r file; do
    mv "$file" "$(dirname "$file")/${cap}$(basename "$file" | sed 's/^Template//')"
done

echo "==> rewriting identifiers"
# `applyDefaultHierarchyTemplate` is a Kotlin Gradle DSL function that happens to contain "Template".
# Park it behind a placeholder so the blanket rename can't corrupt it, then put it back.
find "$dest" -type f -print0 | xargs -0 sed -i \
    -e 's/applyDefaultHierarchyTemplate/@@KGP_HIERARCHY@@/g' \
    -e "s/TEMPLATE/$upper/g" \
    -e "s/Template/$cap/g" \
    -e "s/template/$name/g" \
    -e 's/@@KGP_HIERARCHY@@/applyDefaultHierarchyTemplate/g'

echo "==> wiring into settings.gradle.kts"
settings="$root/settings.gradle.kts"
grep -q "\":apps:$name:core\"" "$settings" || python3 - "$settings" "$name" <<'PY'
import sys
settings_path, app = sys.argv[1], sys.argv[2]
text = open(settings_path).read()
block = "".join(f'include(":apps:{app}:{m}")\n' for m in ("core", "desktop", "android", "web"))
# Insert above the template's own block (comment included) so new apps read top-down in the order
# they were created and the template's explanatory comment stays attached to the template.
for anchor in ("// The starting point for a new app", 'include(":apps:template:core")'):
    idx = text.find(anchor)
    if idx != -1:
        break
else:
    raise SystemExit("could not find the template's include block in settings.gradle.kts")
text = text[:idx] + block + "\n" + text[idx:]
open(settings_path, "w").write(text)
PY

cat <<EOF

Created apps/$name — core, desktop, android, web.

  ./gradlew :apps:$name:desktop:run                 # the window
  ./gradlew :apps:$name:core:jvmTest                # the sim tests
  ./gradlew :apps:$name:android:assembleDebug       # the APK
  ./gradlew :apps:$name:web:jsBrowserDevelopmentRun # the browser

Start in apps/$name/core/src/commonMain/kotlin/org/emerge/demo/$name/${cap}Sim.kt — the reducer is
the whole game. The three hosts need no changes until you add input the template does not have.

Delete what you do not need: an app that will never ship on a phone should lose apps/$name/android
and its two settings.gradle.kts lines, and the same goes for web.
EOF
