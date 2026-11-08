#!/bin/bash
# Uso: tools/schermate.sh CARTELLA_APK CARTELLA_FOTO  (dentro un emulatore già acceso)
set -x
APK=$(ls "$1"/*.apk | head -1)
OUT=$2
mkdir -p "$OUT"
PKG=it.sirjoe.training
adb install -r -g "$APK"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS CAMERA; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null
done
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent 82

shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; }

run() { # nome dimensione densità programma
  local name=$1 size=$2 dens=$3 wo=$4
  adb shell am force-stop $PKG
  adb shell wm size "$size"; adb shell wm density "$dens"
  sleep 3
  if [ -n "$wo" ]; then
    adb shell am start -n $PKG/.ui.MainActivity --ez demo true --es workout "$wo"
  else
    adb shell am start -n $PKG/.ui.MainActivity --ez demo true
  fi
  shot "$name-1-partenza" 6
  shot "$name-2-in-corsa" 12
  # tocco al centro: menu e pulsanti compaiono
  read W H < <(echo "$size" | tr 'x' ' ')
  adb shell input tap $((W/2)) $((H/2))
  shot "$name-3-menu-aperto" 2
  shot "$name-4-menu-richiuso" 10
  adb shell am force-stop $PKG
}

run grande-programma 1080x2340 480 dim-4
run grande-libera 1080x2340 480 ""
run piccolo-programma 720x1280 320 dim-4
run piccolo-libera 720x1280 320 ""
adb shell wm size reset; adb shell wm density reset
adb logcat -d -s AndroidRuntime:E > "$OUT/errori.txt" || true
ls -la "$OUT"
