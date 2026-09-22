"""Verify V0.5 -> V1.0 in a disposable emulator; replaces that emulator's app data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'app.jingting.localplayer'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--old-apk', type=Path, default=ROOT / 'artifacts/Zhenbo-0.5.0-test.apk')
parser.add_argument('--apk', type=Path, default=ROOT / 'artifacts/Zhenbo-1.0.0.apk')
parser.add_argument('--report', type=Path, default=ROOT / 'artifacts/upgrade-v1.0.json')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Only an explicitly selected disposable emulator is accepted.')
alias = Path.home() / 'jingting-workspace'
runtime = alias if (alias / '.tools').exists() else ROOT
adb = [str(runtime / '.tools/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
test_apk = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
for file in [args.old_apk, args.apk, test_apk]:
    if not file.is_file():
        parser.error('Missing APK: ' + str(file))

def run(*command):
    result = subprocess.run(adb + list(command), capture_output=True, timeout=180, check=True)
    return result.stdout.decode('utf-8', errors='replace').strip()

if run('shell', 'getprop', 'ro.kernel.qemu') != '1':
    parser.error('The selected device is not an emulator.')
if run('shell', 'getprop', 'sys.boot_completed') != '1':
    parser.error('Wait for emulator boot to complete.')
args.report.parent.mkdir(parents=True, exist_ok=True)
report = {'serial': args.serial, 'api': int(run('shell', 'getprop', 'ro.build.version.sdk')),
          'oldApkSha256': hashlib.sha256(args.old_apk.read_bytes()).hexdigest(),
          'releaseApkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'status': 'running'}

def phase(name):
    run('shell', 'am', 'force-stop', PACKAGE)
    output = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 PACKAGE + '.V10UpgradeTest', '-e', 'upgradePhase', name,
                 PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    log = args.report.with_name(args.report.stem + '-' + name + '.txt')
    log.write_text(output + '\n', encoding='utf-8')
    if not re.search(r'OK \(1 test\)', output) or re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-[34]\b', output):
        raise RuntimeError('Upgrade phase failed or skipped: ' + str(log))
    report[name] = 'passed'
    print('Upgrade phase ' + name + ' passed.', flush=True)

try:
    # Dedicated emulator fixture reset; never run this against a user's phone.
    if ('package:' + PACKAGE) in run('shell', 'pm', 'list', 'packages', PACKAGE).splitlines():
        if 'Success' not in run('uninstall', PACKAGE):
            raise RuntimeError('Could not reset the emulator fixture.')
    for apk in [args.old_apk, test_apk]:
        if 'Success' not in run('install', '-r', str(apk.resolve())):
            raise RuntimeError('Fixture install failed.')
    run('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    phase('before')
    run('shell', 'am', 'force-stop', PACKAGE)
    if 'Success' not in run('install', '-r', str(args.apk.resolve())):
        raise RuntimeError('Signed release did not upgrade the old APK.')
    phase('after')
    report['status'] = 'passed'
except Exception as error:
    report['status'] = 'failed'
    report['error'] = str(error)
    raise
finally:
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
