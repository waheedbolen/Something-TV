#!/usr/bin/env bash
# Build this launcher and make it the TV's Home, without ever leaving the TV with no Home.
#
#   TV_HOST=<tv-ip> ./install.sh
#
# Order (the same guard as tv-vibehack/launcher/build-and-install.sh):
#   1. install; stop if pm install fails
#   2. start the new Home and require "Status: ok"; stop if it does not come up
#   3. only then disable the stock launcher (on this TCL firmware it holds HOME at priority 2, so
#      set-home-activity does nothing while it is enabled) and make the new one the default
#   4. check where HOME resolves now, and put the stock launcher back if it is not this app
# Undo everything: TV_HOST=... ../tv-vibehack/backup/restore.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=<tv-ip> $0}"
export TV_HOST
REPO=../tv-vibehack
PY="$REPO/tools/venv/bin/python"
[ -x "$PY" ] || { echo "missing $PY: python3 -m venv $REPO/tools/venv && $REPO/tools/venv/bin/pip install -r $REPO/tools/requirements.txt" >&2; exit 1; }
tv() { "$PY" -I "$REPO/tools/tv.py" "$@"; }

PKG=com.tvrefresh.home
HOME_ACT=$PKG/.MainActivity
STOCK=com.google.android.tvlauncher
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

if [ "${SKIP_BUILD:-}" != 1 ]; then
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
  ./gradlew --no-daemon -q assembleRelease
fi
APK=app/build/outputs/apk/release/app-release.apk

"$PY" -I "$REPO/tools/b64push.py" "$APK" /data/local/tmp/home.apk
OUT=$(tv 'pm install -r /data/local/tmp/home.apk; rm -f /data/local/tmp/home.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed; the stock launcher was left alone." >&2; exit 1 ;; esac

START=$(tv "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $HOME_ACT")
echo "$START"
if ! printf '%s' "$START" | grep -qF 'Status: ok' || printf '%s' "$START" | grep -qi '^Error'; then
  echo "ERROR: the new Home did not start; the stock launcher was left alone." >&2
  exit 1
fi

if tv "pm list packages -e $STOCK" | grep -qx "package:$STOCK"; then
  tv "pm disable-user --user 0 $STOCK"
fi
tv "cmd package set-home-activity --user 0 $HOME_ACT"

NOW=$(tv "$HOME_QUERY" | tail -n 1 | tr -d '\r')
case "$NOW" in
  "$PKG"/*) echo "Home now resolves to $NOW" ;;
  *)
    echo "ERROR: Home resolves to '${NOW:-nothing}', not to $PKG. Putting the stock launcher back." >&2
    tv "pm enable --user 0 $STOCK" || true
    tv "cmd package set-home-activity --user 0 $STOCK/.MainActivity" || true
    echo "Home is now: $(tv "$HOME_QUERY" | tail -n 1)" >&2
    exit 1 ;;
esac
tv 'input keyevent HOME'
echo "Installed. Press Home on the remote."
