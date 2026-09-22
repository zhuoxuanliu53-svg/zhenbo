"""Install optional API 36 emulator; does not modify system virtualization settings."""
import concurrent.futures
import importlib.util
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import sys

spec = importlib.util.spec_from_file_location('bootstrap', Path(__file__).with_name('bootstrap.py'))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)
root = bootstrap.TOOLS / 'android-sdk'
api = '37.0' if '--api37' in sys.argv else '36'
tag = 'google_apis' if api == '37.0' else 'default'
repo_kind = 'google_apis' if api == '37.0' else 'android'
avd_name = 'JingTing37' if api == '37.0' else 'JingTing36'
repos = [('https://dl.google.com/android/repository/', 'repository2-3.xml', 'emulator', 'emulator'),
         (f'https://dl.google.com/android/repository/sys-img/{repo_kind}/', 'sys-img2-3.xml', f'system-images;android-{api};{tag};x86_64', f'system-images/android-{api}/{tag}/x86_64')]
jobs = []
for base, xml, package, folder in repos:
    r = ET.fromstring(bootstrap.fetch(base + xml))
    p = next(p for p in r.findall('remotePackage') if p.get('path') == package)
    a = next(a for a in p.findall('./archives/archive') if a.findtext('host-os') in (None, 'windows'))
    checksum = a.find('complete/checksum')
    jobs.append((package.replace(';','-'), base + a.findtext('complete/url'), root / folder, True, checksum.text, checksum.get('type','sha1')))
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    list(pool.map(lambda j: bootstrap.install(*j), jobs))
avd_home = bootstrap.TOOLS / 'avd'
avd_home.mkdir(exist_ok=True)
avd = avd_home / f'{avd_name}.avd'
avd.mkdir(exist_ok=True)
# Use the ASCII workspace junction for native tool compatibility on Windows.
alias = Path.home() / 'jingting-workspace' / '.tools'
actual_avd = alias / f'avd/{avd_name}.avd'
(avd_home / f'{avd_name}.ini').write_text(f'avd.ini.encoding=UTF-8\npath={actual_avd}\ntarget=android-{api}\n')
(avd / 'config.ini').write_text(f'''AvdId={avd_name}
avd.ini.displayname=JingTing API {api}
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=480
hw.lcd.height=960
hw.lcd.density=200
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.audioInput=no
disk.dataPartition.size=2G
image.sysdir.1=system-images/android-{api}/{tag}/x86_64/
tag.id={tag}
tag.display={tag}
PlayStore.enabled=false
showDeviceFrame=no
''')
print('AVD ready')
