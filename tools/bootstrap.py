"""Install project-local Windows build tools from official repositories."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from xml.dom import minidom

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(url):
    return urllib.request.urlopen(url, timeout=120).read()

def install(name, url, destination, strip=True, checksum=None, algorithm='sha256'):
    marker = destination / '.installed'
    if marker.exists():
        print(name, 'already installed', flush=True)
        return
    archive = TOOLS / (name + '.zip')
    if archive.exists() and checksum:
        with archive.open('rb') as existing:
            valid = hashlib.file_digest(existing, algorithm).hexdigest() == checksum
        if not valid:
            archive.unlink()
    if not archive.exists():
        print('Downloading', name, flush=True)
        with urllib.request.urlopen(url, timeout=300) as src, archive.open('wb') as out:
            while block := src.read(128 * 1024):
                out.write(block)
    if checksum:
        with archive.open('rb') as downloaded:
            digest = hashlib.file_digest(downloaded, algorithm).hexdigest()
        if digest != checksum:
            archive.unlink()
            raise RuntimeError(name + ' checksum mismatch')
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            parts = Path(entry.filename).parts
            relative = Path(*parts[1:]) if strip else Path(*parts)
            if not relative.parts:
                continue
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError('Unsafe archive path')
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(entry) as src, target.open('wb') as out:
                    while block := src.read(1024 * 1024):
                        out.write(block)
    marker.write_text(url, encoding='utf-8')
    print('Installed', name, flush=True)

packages = json.loads(fetch('https://api.azul.com/metadata/v1/zulu/packages/?java_version=17&os=windows&arch=x86&archive_type=zip&java_package_type=jdk&latest=true&release_status=ga&availability_types=CA'))
jdk = next(p for p in packages if 'win_x64' in p['name'] and '-fx-' not in p['name'] and '-crac-' not in p['name'])
repo_bytes = fetch('https://dl.google.com/android/repository/repository2-3.xml')
repo = ET.fromstring(repo_bytes)
jobs = [('jdk', jdk['download_url'], TOOLS / 'jdk', True, None, 'sha256'),
        ('gradle', 'https://downloads.gradle.org/distributions/gradle-9.3.1-bin.zip', TOOLS / 'gradle', True,
         'b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06', 'sha256')]
for package, folder in [('platforms;android-37.0', 'platforms/android-37.0'), ('build-tools;36.0.0', 'build-tools/36.0.0'), ('platform-tools', 'platform-tools'), ('cmdline-tools;latest', 'cmdline-tools/latest')]:
    p = next(p for p in repo.findall('remotePackage') if p.get('path') == package)
    a = next(a for a in p.findall('./archives/archive') if a.findtext('host-os') in (None, 'windows'))
    checksum = a.find('complete/checksum')
    jobs.append((package.replace(';', '-'), 'https://dl.google.com/android/repository/' + a.findtext('complete/url'), TOOLS / 'android-sdk' / folder, True, checksum.text, checksum.get('type', 'sha1')))
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
    for result in pool.map(lambda j: install(*j), jobs):
        pass
# Register packages using repository metadata. API 37's minor version is not
# understood by the legacy source.properties fallback in some build tools.
doc = minidom.parseString(repo_bytes)
sdk = TOOLS / 'android-sdk'
for package, folder in [('platforms;android-37.0', 'platforms/android-37.0'), ('build-tools;36.0.0', 'build-tools/36.0.0'), ('platform-tools', 'platform-tools')]:
    remote = next(p for p in doc.getElementsByTagName('remotePackage') if p.getAttribute('path') == package)
    local = minidom.Document()
    root = local.createElementNS(doc.documentElement.namespaceURI, doc.documentElement.tagName)
    for index in range(doc.documentElement.attributes.length):
        attr = doc.documentElement.attributes.item(index)
        root.setAttribute(attr.name, attr.value)
    local.appendChild(root)
    for license in doc.getElementsByTagName('license'):
        root.appendChild(local.importNode(license, True))
        terms = ''.join(n.data for n in license.childNodes if n.nodeType in [n.TEXT_NODE, n.CDATA_SECTION_NODE]).strip()
        (sdk / 'licenses').mkdir(exist_ok=True)
        (sdk / 'licenses' / license.getAttribute('id')).write_text(hashlib.sha1(terms.encode()).hexdigest() + '\n')
    item = local.importNode(remote, True)
    local.renameNode(item, None, 'localPackage')
    for child in list(item.childNodes):
        if child.nodeType == child.ELEMENT_NODE and child.tagName in ['archives', 'channelRef', 'dependencies']:
            item.removeChild(child)
    root.appendChild(item)
    (sdk / folder / 'package.xml').write_text(local.toxml(), encoding='utf-8')
alias_sdk = Path.home() / 'jingting-workspace/.tools/android-sdk'
sdk_property = (alias_sdk if alias_sdk.exists() else TOOLS / 'android-sdk').as_posix().replace(':', '\\:')
sdk_property = sdk_property.encode('unicode_escape').decode('ascii').replace('\\\\:', '\\:')
(ROOT / 'local.properties').write_text('sdk.dir=' + sdk_property + '\n', encoding='ascii', newline='\n')
print('Build tools ready.', flush=True)
