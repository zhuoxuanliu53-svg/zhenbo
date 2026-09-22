"""Run instrumentation against an explicitly selected disposable test device."""
import argparse
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--report', required=True)
parser.add_argument('--method')
parser.add_argument('--apk', type=Path, help='App APK to test; permits verification of the actual signed release.')
parser.add_argument('--test-apk', type=Path, help='Instrumentation APK; defaults to the debug test build.')
parser.add_argument('--test-class', default='app.jingting.localplayer.MediaIntegrationTest')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
adb = [str(Path.home() / 'jingting-workspace/.tools/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
def run(*params, capture=False):
    return subprocess.run(adb + list(params), check=True, capture_output=capture, text=True, encoding='utf-8', errors='replace', timeout=60)
deadline = time.monotonic() + 240
while run('shell', 'getprop', 'sys.boot_completed', capture=True).stdout.strip() != '1':
    if time.monotonic() > deadline: raise SystemExit('Device boot timed out')
    time.sleep(2)
print('Device booted.', flush=True)
run('install', '-r', str(args.apk or root / 'app/build/outputs/apk/debug/app-debug.apk'))
run('install', '-r', str(args.test_apk or root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
run('shell', 'appops', 'set', 'app.jingting.localplayer', 'SCHEDULE_EXACT_ALARM', 'allow')
run('shell', 'pm', 'grant', 'app.jingting.localplayer', 'android.permission.POST_NOTIFICATIONS')
run('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
command = adb + ['shell', 'am', 'instrument', '-w', '-r']
if args.method: command += ['-e', 'class', args.test_class + '#' + args.method]
elif args.test_class != 'app.jingting.localplayer.MediaIntegrationTest': command += ['-e', 'class', args.test_class]
command += ['app.jingting.localplayer.test/androidx.test.runner.AndroidJUnitRunner']
report = root / args.report
report.parent.mkdir(parents=True, exist_ok=True)
with report.open('w', encoding='utf-8') as f:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    for line in process.stdout:
        f.write(line); f.flush()
        if any(word in line for word in ['INSTRUMENTATION_STATUS: test=', 'OK (', 'FAILURES', 'Time:']): print(line.strip(), flush=True)
    process.wait()
text = report.read_text(encoding='utf-8')
if process.returncode != 0 or not re.search(r'OK \(\d+ tests?\)', text): raise SystemExit('Device tests failed; see ' + str(report))
print('Device tests passed.', flush=True)
