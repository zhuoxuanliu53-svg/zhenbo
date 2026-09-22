"""Verify synthetic export duration, ordering and stereo separation using decoded PCM."""
import array
import cmath
import json
import math
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/test-python'))
import imageio_ffmpeg
adb = str(Path.home() / 'jingting-workspace/.tools/android-sdk/platform-tools/adb.exe')
directory = ROOT / 'artifacts/qa-audio'
directory.mkdir(parents=True, exist_ok=True)
subprocess.run([adb, 'pull', '/sdcard/Music/静听/', str(directory)], check=True)
files = list(directory.rglob('test-concatenated_*.m4a'))
assert files, 'No synthetic concatenation result on device'
source = max(files, key=lambda f: f.name)
raw = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-i', str(source), '-f', 's16le', '-acodec', 'pcm_s16le', '-'], capture_output=True, check=True).stdout
pcm = array.array('h', raw)
duration = len(pcm) / 2 / 48000
assert 4.9 < duration < 5.2, duration
def amplitude(channel, frequency, start):
    samples = pcm[int(start*48000)*2+channel:int((start+0.1)*48000)*2:2]
    return abs(sum(s * cmath.exp(-2j * math.pi * frequency * i / 48000) for i, s in enumerate(samples))) / len(samples)
left_ratio = amplitude(0,440,0.5) / max(amplitude(0,880,0.5),1)
right_ratio = amplitude(1,880,0.5) / max(amplitude(1,440,0.5),1)
assert left_ratio > 10 and right_ratio > 10, (left_ratio,right_ratio)
left_mono = amplitude(0,440,3.5)
right_mono = amplitude(1,440,3.5)
assert left_mono > 1000 and right_mono > 1000
assert abs(left_mono - right_mono) / max(left_mono, right_mono) < 0.1
report = {'duration_seconds':duration, 'left_440_to_880_ratio':left_ratio, 'right_880_to_440_ratio':right_ratio, 'mono_segment_left_amplitude':left_mono, 'mono_segment_right_amplitude':right_mono, 'result':'PASS: stereo retained; mono duplicated; segment order correct'}
(ROOT/'artifacts/audio-verification.json').write_text(json.dumps(report, indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
