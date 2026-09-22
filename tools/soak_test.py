"""Optional real-device screen-off test. Start a looping queue in the app first."""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--minutes', type=float, default=120)
parser.add_argument('--serial', help='ADB device serial; required when several devices are attached')
args = parser.parse_args()
assert args.minutes > 0
root = Path(__file__).resolve().parents[1]
adb_path = Path.home() / 'jingting-workspace/.tools/android-sdk/platform-tools/adb.exe'
adb = [str(adb_path)] + (['-s', args.serial] if args.serial else [])
def shell(*command):
    return subprocess.run(adb + ['shell', *command], capture_output=True, check=True, text=True, encoding='utf-8', errors='replace', timeout=30).stdout
def playing():
    dump = shell('dumpsys', 'media_session')
    blocks = re.split(r'(?=\s+ownerPid=)', dump)
    return any('package=app.jingting.localplayer' in block and re.search(r'PlaybackState\s*\{state=(?:PLAYING\()?3(?:\)|\b)', block) for block in blocks)
if not playing():
    raise SystemExit('Start playback in JingTing and select repeat-one or repeat-all before running this test.')
shell('input', 'keyevent', '223')  # KEYCODE_SLEEP: explicitly requested screen-off test.
output = root / 'artifacts/soak-test.jsonl'
start = time.monotonic()
with output.open('w', encoding='utf-8') as f:
    while True:
        elapsed = time.monotonic() - start
        ok = playing()
        row = {'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'elapsed_seconds':round(elapsed,1), 'playing':ok}
        f.write(json.dumps(row)+'\n'); f.flush()
        print(row, flush=True)
        if not ok: raise SystemExit('FAIL: playback was not active; inspect device and app status.')
        if elapsed >= args.minutes * 60: break
        time.sleep(min(30, args.minutes * 60 - elapsed))
print('PASS: screen-off playback stayed active for the requested duration.')
