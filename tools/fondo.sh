#!/bin/bash
# My System TV: prepares one of your videos as a Home background and puts it on the TV.
#
#   TV_HOST=192.168.1.50 tools/fondo.sh <id> <video>
#
# <id> picks the background's slot and name in the Control Center: gg-sunset (Atardecer), gg-night (Puente de
# noche), sequoia (Sequoia), noche (Noche) or utah (Utah). The video becomes a seamless 1080p loop of at most a
# minute, with the launcher's dark edges painted in (tools/fondo-sombra.png), and a small still for the picker.
# Needs ffmpeg and adb (Android platform-tools) on the PATH, and ADB debugging on the TV. Use videos you have the
# right to use, such as Pexels' free videos.
set -euo pipefail
ID=${1:-}; SRC=${2:-}
case "$ID" in gg-sunset|gg-night|sequoia|noche|utah) ;; *) echo "usage: TV_HOST=<tv ip> $0 <gg-sunset|gg-night|sequoia|noche|utah> <video>" >&2; exit 2 ;; esac
[ -f "$SRC" ] || { echo "no such video: $SRC" >&2; exit 2; }
[ -n "${TV_HOST:-}" ] || { echo "set TV_HOST to your TV's IP address" >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is needed" >&2; exit 2; }
command -v adb >/dev/null || { echo "adb (Android platform-tools) is needed" >&2; exit 2; }
SHADE="$(cd "$(dirname "$0")" && pwd)/fondo-sombra.png"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# A loop of D seconds whose last F seconds cross-fade into its first ones, so the restart never shows.
# ffmpeg -i alone exits 1 (no output named); only its Duration line is wanted.
LEN=$( (ffmpeg -hide_banner -i "$SRC" 2>&1 || true) | awk -F'[:, ]+' '/Duration:/ { print $3 * 3600 + $4 * 60 + $5; exit }')
[ -n "$LEN" ] || { echo "could not read the video's length: $SRC" >&2; exit 2; }
F=1
D=$(awk -v l="$LEN" -v f="$F" 'BEGIN { d = l - f - 0.1; if (d > 60) d = 60; printf "%.2f", d }')
awk -v d="$D" 'BEGIN { exit !(d >= 4) }' || { echo "the video is too short (at least 5 seconds)" >&2; exit 2; }
END=$(awk -v d="$D" -v f="$F" 'BEGIN { printf "%.2f", d + f }')
ffmpeg -v error -y -t "$END" -i "$SRC" -i "$SHADE" -an -filter_complex "
  [0:v]scale=1920:1080:force_original_aspect_ratio=increase:flags=lanczos,crop=1920:1080,fps=30,format=yuv420p,split=3[a][b][c];
  [a]trim=start=$F:end=$D,setpts=PTS-STARTPTS,fps=30,settb=1/30[body];
  [b]trim=start=$D:end=$END,setpts=PTS-STARTPTS,fps=30,settb=1/30[tail];
  [c]trim=start=0:end=$F,setpts=PTS-STARTPTS,fps=30,settb=1/30[head];
  [tail][head]xfade=transition=fade:duration=$F:offset=0[seam];
  [body][seam]concat=n=2:v=1[loop];
  [loop][1:v]overlay=0:0:format=auto,format=yuv420p[out]" \
  -map "[out]" -c:v libx264 -preset slow -crf 19 -profile:v high -level 4.1 -movflags +faststart "$OUT/$ID.mp4"
ffmpeg -v error -y -ss 2 -i "$OUT/$ID.mp4" -frames:v 1 -vf scale=320:180 -q:v 3 "$OUT/$ID.jpg"

DIR=/sdcard/Android/data/com.gathin.tvhome/files/videos
adb connect "$TV_HOST:5555" >/dev/null
adb -s "$TV_HOST:5555" shell mkdir -p "$DIR"
adb -s "$TV_HOST:5555" push "$OUT/$ID.mp4" "$OUT/$ID.jpg" "$DIR/" >/dev/null
echo "$ID is on the TV ($(du -h "$OUT/$ID.mp4" | awk '{print $1}')). Pick it in the Control Center, under Fondo."
