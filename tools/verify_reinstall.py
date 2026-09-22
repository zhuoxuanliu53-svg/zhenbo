"""Verify public exports survive app uninstall, on an explicitly selected emulator only.

This deliberately removes Zhenbo's private data on that emulator. Build/package
the signed V1.0 release and finish other tests first. No physical device is accepted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'app.jingting.localplayer'
TEST_PACKAGE = PACKAGE + '.test'
RUNNER = TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Disposable test emulator, e.g. emulator-5556')
    parser.add_argument('--apk', type=Path, default=ROOT / 'artifacts/Zhenbo-1.0.0.apk')
    parser.add_argument('--test-apk', type=Path, default=ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    parser.add_argument('--report', type=Path, default=ROOT / 'artifacts/reinstall-verification-v1.0.json')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only an explicit emulator-NNNN serial is permitted. Physical devices are never accepted.')

    alias = Path.home() / 'jingting-workspace'
    tool_root = alias / '.tools' if (alias / '.tools').exists() else ROOT / '.tools'
    adb_path = tool_root / 'android-sdk/platform-tools/adb.exe'
    aapt_path = tool_root / 'android-sdk/build-tools/36.0.0/aapt2.exe'
    for path in [adb_path, aapt_path, args.apk, args.test_apk]:
        if not path.is_file():
            parser.error('Missing prerequisite: ' + str(path))
    adb = [str(adb_path), '-s', args.serial]

    def run(*command, binary=False, timeout=90):
        result = subprocess.run(adb + list(command), capture_output=True, timeout=timeout, check=True)
        if binary:
            return result.stdout
        return result.stdout.decode('utf-8', errors='replace').strip()

    def shell(*command):
        # Android's shell parses the command; quote each argument using its POSIX syntax.
        return run('shell', ' '.join(shlex.quote(str(part)) for part in command))

    def require_emulator():
        if shell('getprop', 'ro.kernel.qemu') != '1':
            parser.error('The selected serial is not identified as an Android emulator.')
        if shell('getprop', 'sys.boot_completed') != '1':
            parser.error('Wait for the selected emulator to finish booting.')

    require_emulator()
    api = int(shell('getprop', 'ro.build.version.sdk'))
    if api < 29:
        parser.error('This test requires Android 10 or newer.')

    def badging(apk):
        return subprocess.run([str(aapt_path), 'dump', 'badging', str(apk)], capture_output=True, check=True).stdout.decode('utf-8', errors='replace')

    app_badging = badging(args.apk)
    app_match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", app_badging)
    if not app_match or app_match.group(1) != PACKAGE or int(app_match.group(2)) < 100:
        parser.error('The APK must be Zhenbo V1.0 or newer (versionCode >= 100).')
    if 'application-debuggable' in app_badging:
        parser.error('Use the actual non-debuggable release APK for this verification.')
    if f"package: name='{TEST_PACKAGE}'" not in badging(args.test_apk):
        parser.error('The test APK does not belong to Zhenbo.')

    report = {
        'serial': args.serial,
        'androidApi': api,
        'appVersion': app_match.group(3),
        'appVersionCode': int(app_match.group(2)),
        'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'nonDebuggableRelease': True,
        'startedAt': datetime.now(timezone.utc).isoformat(),
        'status': 'running',
        'scope': 'Disposable emulator only; uninstall removes app-private collections, aliases and schedules.',
        'steps': [],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def step(text):
        report['steps'].append(text)
        save()
        print(text, flush=True)

    def install():
        for apk in [args.apk, args.test_apk]:
            output = run('install', '-r', str(apk.resolve()), timeout=180)
            if 'Success' not in output:
                raise RuntimeError('APK install did not report success: ' + output)

    def instrument(class_name, extras, log_name):
        command = ['am', 'instrument', '-w', '-r', '-e', 'class', class_name]
        for key, value in extras.items():
            command.extend(['-e', key, value])
        command.append(RUNNER)
        output = run('shell', ' '.join(shlex.quote(part) for part in command), timeout=240)
        log = args.report.with_name(log_name)
        log.write_text(output + '\n', encoding='utf-8')
        if not re.search(r'OK \(1 test\)', output) or re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-[34]\b', output):
            raise RuntimeError('Required device test failed or was skipped; see ' + str(log))
        return log.name

    def select_export():
        output = shell('content', 'query', '--uri', 'content://media/external_primary/audio/media',
                       '--projection', '_id:_display_name:_data', '--where',
                       "is_pending=0 AND relative_path='Music/静听/' AND _display_name LIKE 'test-concatenated%.m4a'")
        found = []
        for line in output.splitlines():
            match = re.search(r'_id=(\d+), _display_name=(.*), _data=(.*)$', line)
            if match:
                media_id, title, path = match.groups()
                if path.startswith('/storage/emulated/0/Music/静听/') or path.startswith('/sdcard/Music/静听/'):
                    found.append((int(media_id), title, path))
        if not found:
            return None
        media_id, title, path = max(found, key=lambda item: item[0])
        return {'uri': f'content://media/external_primary/audio/media/{media_id}', 'title': title, 'path': path}

    def file_hash(path):
        data = run('exec-out', 'cat ' + shlex.quote(path), binary=True)
        if len(data) < 32 or data[4:8] != b'ftyp':
            raise RuntimeError('The public export is missing or is not an MP4/M4A file.')
        return hashlib.sha256(data).hexdigest(), len(data)

    save()
    try:
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        # Install before destructive work as an additional compatibility/signature preflight.
        install()
        previous_export = select_export()
        # Always exercise this APK's real encoder/publication path. A file from an
        # earlier test run alone does not prove the release can still export.
        report['fixtureCreationTest'] = instrument(
            PACKAGE + '.MediaIntegrationTest#concatenatesMixedRatesAndChannels', {},
            args.report.stem + '-fixture.txt')
        exported = select_export()
        if exported is None or (previous_export is not None and exported['uri'] == previous_export['uri']):
            raise RuntimeError('The synthetic concatenation test did not create a new discoverable public export.')
        report['export'] = exported
        before_hash, size = file_hash(exported['path'])
        report['sha256Before'] = before_hash
        report['fileBytes'] = size
        step('Synthetic public export recorded; all APK and emulator checks passed.')

        # This command intentionally does NOT use -k: the test covers a full uninstall.
        require_emulator()
        output = run('uninstall', PACKAGE)
        if 'Success' not in output:
            raise RuntimeError('Application uninstall did not report success: ' + output)
        after_hash, after_size = file_hash(exported['path'])
        report['sha256AfterUninstall'] = after_hash
        if (before_hash, size) != (after_hash, after_size):
            raise RuntimeError('The public file changed or disappeared after uninstall.')
        step('Full uninstall completed; the public audio remains byte-for-byte unchanged.')

        install()
        permission = 'android.permission.READ_MEDIA_AUDIO' if api >= 33 else 'android.permission.READ_EXTERNAL_STORAGE'
        shell('pm', 'grant', PACKAGE, permission)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        report['scanTestLog'] = instrument(PACKAGE + '.ReinstallScanTest', {
            'expectedExportUri': exported['uri'], 'expectedTitle': exported['title'],
        }, args.report.stem + '-scan.txt')
        final_hash, final_size = file_hash(exported['path'])
        report['sha256AfterReinstall'] = final_hash
        if (before_hash, size) != (final_hash, final_size):
            raise RuntimeError('The public file changed during reinstall or rescan.')
        step(f"V{app_match.group(3)} reinstalled offline; granted audio access and rediscovered the original URI/title.")
        report['status'] = 'passed'
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        save()
        print('Reinstall verification passed: ' + str(args.report), flush=True)
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        save()
        raise


if __name__ == '__main__':
    main()
