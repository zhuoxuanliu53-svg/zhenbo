"""Offline official release signing. Private keys stay outside the repository.

Run `init` once on the maintainer's Windows machine, then `sign` after
assembleRelease. Forks should use their own application ID and signing keys.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ALIAS = Path.home() / 'jingting-workspace'
RUNTIME = ALIAS if (ALIAS / '.tools/jdk').is_dir() else ROOT
PRIVATE = Path.home() / '.zhenbo-signing'
PUBLIC = ROOT / 'release/signing'
LEGACY_SHA256 = 'ad3e5ae8a5dd45a1a7d01f35efd4d0aadb60fe23f472464707bc638e55992f13'
JAVA = RUNTIME / '.tools/jdk/bin/java.exe'
KEYTOOL = RUNTIME / '.tools/jdk/bin/keytool.exe'
APKSIGNER = RUNTIME / '.tools/android-sdk/build-tools/36.0.0/lib/apksigner.jar'


def run(command):
    result = subprocess.run([str(x) for x in command], capture_output=True, check=True)
    return result.stdout


def protect_directory():
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        account = run(['whoami']).decode().strip()
        run(['icacls', PRIVATE, '/inheritance:r', '/grant:r',
             account + ':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'])
    else:
        PRIVATE.chmod(0o700)


def signer():
    return [JAVA, '-jar', APKSIGNER]


def old_key_args():
    return ['--ks', PRIVATE / 'legacy-debug.keystore', '--ks-key-alias',
            'androiddebugkey', '--ks-pass', 'pass:android']


def new_key_args():
    return ['--ks', PRIVATE / 'release.p12', '--ks-key-alias', 'zhenbo',
            '--ks-pass', 'file:' + str(PRIVATE / 'keystore-password.txt')]


def initialize():
    protect_directory()
    original = Path.home() / '.android/debug.keystore'
    legacy = PRIVATE / 'legacy-debug.keystore'
    legacy_source = legacy if legacy.exists() else original
    cert = run([KEYTOOL, '-exportcert', '-keystore', legacy_source,
                '-alias', 'androiddebugkey', '-storepass', 'android'])
    if hashlib.sha256(cert).hexdigest() != LEGACY_SHA256:
        raise SystemExit('Legacy certificate differs. Refusing to create an incompatible official signing lineage.')
    if not legacy.exists():
        shutil.copyfile(original, legacy)
    key = PRIVATE / 'release.p12'
    password = PRIVATE / 'keystore-password.txt'
    if key.exists() != password.exists():
        raise SystemExit('Incomplete signing directory. Restore the key/password pair; files will not be overwritten.')
    if not key.exists():
        with password.open('x', encoding='ascii') as out:
            out.write(secrets.token_urlsafe(48) + '\n')
        run([KEYTOOL, '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
             '-alias', 'zhenbo', '-keyalg', 'RSA', '-keysize', '3072',
             '-validity', '36500', '-dname', 'CN=Zhenbo, O=Zhenbo',
             '-storepass:file', password, '-keypass:file', password])
    lineage = PRIVATE / 'signing-lineage.bin'
    if not lineage.exists():
        run(signer() + ['rotate', '--out', lineage, '--old-signer'] + old_key_args()
            + ['--new-signer'] + new_key_args())
    PUBLIC.mkdir(parents=True, exist_ok=True)
    release_cert = run([KEYTOOL, '-exportcert', '-keystore', key, '-alias', 'zhenbo',
                        '-storepass:file', password])
    for name, data in [('legacy-cert.der', cert), ('release-cert.der', release_cert),
                       ('signing-lineage.bin', lineage.read_bytes())]:
        target = PUBLIC / name
        if target.exists() and target.read_bytes() != data:
            raise SystemExit('Published signing identity differs; refusing to replace it: ' + name)
        target.write_bytes(data)
    fingerprints = {'legacySha256': LEGACY_SHA256,
                    'releaseSha256': hashlib.sha256(release_cert).hexdigest(),
                    'rotationMinSdkVersion': 28, 'minSupportedAndroidApi': 29}
    (PUBLIC / 'certificates.json').write_text(json.dumps(fingerprints, indent=2) + '\n', 'utf-8')
    print('Signing identity ready. Private material is stored outside this repository at ' + str(PRIVATE))


def sign():
    required = ['release.p12', 'legacy-debug.keystore', 'keystore-password.txt', 'signing-lineage.bin']
    if not all((PRIVATE / name).is_file() for name in required):
        raise SystemExit('Initialize or restore the maintainer signing directory first.')
    version = re.search(r"versionName\s+'([^']+)'", (ROOT / 'app/build.gradle').read_text('utf-8')).group(1)
    source = RUNTIME / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    target = RUNTIME / f'artifacts/Zhenbo-{version}.apk'
    target.parent.mkdir(exist_ok=True)
    # v3 proof-of-rotation preserves upgrades from V0.x on every supported API.
    run(signer() + ['sign'] + old_key_args() + ['--next-signer'] + new_key_args()
        + ['--lineage', PRIVATE / 'signing-lineage.bin', '--rotation-min-sdk-version', '28',
           '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
           '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
           '--debuggable-apk-permitted', 'false', '--out', target, source])
    verification = run(signer() + ['verify', '--verbose', '--print-certs', '--min-sdk-version', '29', target])
    (ROOT / f'artifacts/apk-signature-{version}.txt').write_bytes(verification)
    print('Signed and verified ' + target.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'sign'])
    args = parser.parse_args()
    if args.action == 'init':
        initialize()
    else:
        sign()
