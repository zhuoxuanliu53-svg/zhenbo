"""Package a signed release (or --debug test build), source and checksums."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
out = ROOT / 'artifacts'
out.mkdir(exist_ok=True)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--debug', action='store_true', help='Package the development APK instead of the signed release.')
args = parser.parse_args()
gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')


def gradle_value(pattern, label):
    match = re.search(pattern, gradle)
    if not match:
        raise SystemExit('Missing ' + label + ' in app/build.gradle.')
    return match.group(1)


version = gradle_value(r"versionName\s+'([^']+)'", 'versionName')
version_code = int(gradle_value(r'versionCode\s+(\d+)', 'versionCode'))
package_name = gradle_value(r"applicationId\s+'([^']+)'", 'applicationId')
min_sdk = int(gradle_value(r'minSdk\s+(\d+)', 'minSdk'))
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version):
    raise SystemExit('versionName is not safe for a release filename.')
apk = out / f'Zhenbo-{version}{"-test" if args.debug else ""}.apk'
if args.debug:
    shutil.copy2(ROOT / 'app/build/outputs/apk/debug/app-debug.apk', apk)
elif not apk.is_file():
    raise SystemExit('Run assembleRelease and tools/sign_release.py sign first.')
alias = Path.home() / 'jingting-workspace'
local_roots = [ROOT]
if alias.exists() and alias.resolve() == ROOT.resolve():
    local_roots.insert(0, alias)
sdk_candidates = [Path(os.environ[name]) for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT') if os.environ.get(name)]
sdk_candidates.extend(root / '.tools/android-sdk' for root in local_roots)
executable_suffix = '.exe' if os.name == 'nt' else ''
sdk = next((path for path in sdk_candidates if (path / f'build-tools/36.0.0/aapt2{executable_suffix}').is_file()), None)
if sdk is None:
    raise SystemExit('Android build-tools 36.0.0 not found. Set ANDROID_HOME or run tools/bootstrap.py.')
aapt = sdk / f'build-tools/36.0.0/aapt2{executable_suffix}'
permissions = subprocess.run([str(aapt), 'dump', 'permissions', str(apk)], capture_output=True, check=True, text=True, encoding='utf-8').stdout
badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True, check=True, text=True, encoding='utf-8').stdout
identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
if identity is None or (identity.group(1), int(identity.group(2)), identity.group(3)) != (package_name, version_code, version):
    raise SystemExit('APK package/versionCode/versionName does not match app/build.gradle.')
if not args.debug and 'application-debuggable' in badging:
    raise SystemExit('Refusing to publish a debuggable APK.')
for permission in ('INTERNET', 'ACCESS_NETWORK_STATE', 'RECORD_AUDIO', 'MANAGE_EXTERNAL_STORAGE'):
    if 'android.permission.' + permission in permissions:
        raise SystemExit('Unexpected APK permission: android.permission.' + permission)

if not args.debug:
    java_candidates = []
    if os.environ.get('JAVA_HOME'):
        java_candidates.append(Path(os.environ['JAVA_HOME']) / f'bin/java{executable_suffix}')
    java_candidates.extend(root / f'.tools/jdk/bin/java{executable_suffix}' for root in local_roots)
    if shutil.which('java'):
        java_candidates.append(Path(shutil.which('java')))
    java = next((path for path in java_candidates if path.is_file()), None)
    signer = sdk / 'build-tools/36.0.0/lib/apksigner.jar'
    if java is None or not signer.is_file():
        raise SystemExit('Java/apksigner not found; set JAVA_HOME and install Android build-tools 36.0.0.')
    certificates = json.loads((ROOT / 'release/signing/certificates.json').read_text(encoding='utf-8'))
    expected = certificates.get('releaseSha256', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise SystemExit('Invalid release fingerprint in release/signing/certificates.json.')
    # Windows JDK 17 may decode non-ASCII command arguments using the system code
    # page; use the validated junction for the APK as well as for the runtime.
    java_apk = local_roots[0] / apk.relative_to(ROOT)
    verification = subprocess.run([str(java), '-jar', str(signer), 'verify', '--verbose', '--print-certs',
                                   '--min-sdk-version', str(min_sdk), str(java_apk)],
                                  capture_output=True, check=True, text=True, encoding='utf-8')
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)\s*$', verification.stdout, re.MULTILINE)
    if len(fingerprints) != 1 or fingerprints[0].lower() != expected:
        raise SystemExit('APK signing certificate does not match the public release fingerprint.')
    (out / f'apk-signature-{version}.txt').write_text(verification.stdout + verification.stderr, encoding='utf-8')

(out / f'apk-permissions-{version}.txt').write_text(permissions, encoding='utf-8')
(out / f'apk-badging-{version}.txt').write_text(badging, encoding='utf-8')
source = out / f'Zhenbo-{version}-source.zip'
# Match the public repository's selection, including an explicit second guard for
# sensitive files that might have been force-added to Git despite .gitignore.
tracked = subprocess.run(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=ROOT,
                         check=True, capture_output=True).stdout.decode('utf-8').split('\0')
source_files = []
excluded_parts = {'.tools', 'artifacts', '.git', '.gradle', '.kotlin', '.idea', 'build', '__pycache__', 'captures', 'test-results'}
private_suffixes = {'.keystore', '.jks', '.p12', '.pfx', '.pk8', '.key', '.pem', '.p8', '.hprof', '.apk', '.aab', '.log'}
private_names = {'local.properties', '.env', 'keystore.properties', 'signing.properties', 'id_rsa', 'id_dsa', 'id_ecdsa', 'id_ed25519'}
for name in sorted(set(filter(None, tracked))):
    f = ROOT / name
    if not f.is_file():
        continue
    resolved = f.resolve()
    if f.is_symlink() or not resolved.is_relative_to(ROOT.resolve()):
        raise SystemExit('Refusing a source file link/outside path: ' + name)
    parts = set(part.lower() for part in f.relative_to(ROOT).parts + resolved.relative_to(ROOT.resolve()).parts)
    if (parts & excluded_parts or f.suffix.lower() in private_suffixes
            or f.name.lower() in private_names or f.name.lower().startswith('.env.')):
        raise SystemExit('Refusing a private/generated source file: ' + name)
    source_files.append((f, name))
temporary_source = source.with_suffix('.zip.tmp')
try:
    with zipfile.ZipFile(temporary_source, 'w', zipfile.ZIP_DEFLATED) as z:
        for f, name in source_files:
            z.write(f, name)
    temporary_source.replace(source)
finally:
    temporary_source.unlink(missing_ok=True)
report = {}
for f in [apk, source]:
    with f.open('rb') as stream: digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report[f.name] = {'bytes': f.stat().st_size, 'sha256': digest}
(out / f'SHA256-{version}.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
(out / f'SHA256-{version}.txt').write_text(''.join(f"{value['sha256']}  {name}\n" for name, value in report.items()), encoding='utf-8')
print(json.dumps(report, indent=2))
