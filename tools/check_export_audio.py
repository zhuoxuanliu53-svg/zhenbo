"""Verify synthetic export duration, ordering and stereo separation using decoded PCM."""
import array
import argparse
import cmath
import hashlib
import io
import json
import math
from pathlib import Path
import subprocess
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/test-python'))
import imageio_ffmpeg
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, help='Exact local M4A export to verify; never invokes ADB when provided.')
parser.add_argument('--report', type=Path, default=ROOT / 'artifacts/audio-verification.json')
parser.add_argument('--serial', help='ADB device serial, only used when --source is omitted.')
args = parser.parse_args()

if args.source is not None:
    source = args.source.resolve()
    if not source.is_file() or source.suffix.lower() != '.m4a':
        parser.error('--source must be an existing local .m4a file.')
else:
    # Legacy device mode. Explicit local sources do not enter this branch.
    adb = [str(Path.home() / 'jingting-workspace/.tools/android-sdk/platform-tools/adb.exe')]
    if args.serial:
        adb.extend(['-s', args.serial])
    directory = ROOT / 'artifacts/qa-audio'
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(adb + ['pull', '/sdcard/Music/静听/', str(directory)], check=True)
    files = list(directory.rglob('test-concatenated_*.m4a'))
    if not files:
        raise RuntimeError('No synthetic concatenation result on device.')
    source = max(files, key=lambda f: f.name)

# WAV exposes the decoded stream's native rate/layout. Do not force -ar/-ac,
# which would hide a release that exported the wrong sample rate or channel count.
# Six seconds bounds memory if an unrelated long file was selected by mistake.
decoded = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-i', str(source),
                          '-map', '0:a:0', '-t', '6', '-f', 'wav', '-acodec', 'pcm_s16le', '-'],
                         capture_output=True, check=True).stdout
with wave.open(io.BytesIO(decoded), 'rb') as wav:
    sample_rate, channels, sample_width = wav.getframerate(), wav.getnchannels(), wav.getsampwidth()
    if (sample_rate, channels, sample_width) != (48000, 2, 2):
        raise RuntimeError(f'Unexpected decoded audio format: {sample_rate} Hz, {channels} channels, {sample_width * 8} bits.')
    raw = wav.readframes(sample_rate * 7)
pcm = array.array('h', raw)
if sys.byteorder != 'little':
    pcm.byteswap()
duration = len(pcm) / 2 / 48000
if not 4.9 < duration < 5.2:
    raise RuntimeError(f'Unexpected concatenated duration: {duration:.6f} seconds.')
def amplitude(channel, frequency, start):
    samples = pcm[int(start*48000)*2+channel:int((start+0.1)*48000)*2:2]
    return abs(sum(s * cmath.exp(-2j * math.pi * frequency * i / 48000) for i, s in enumerate(samples))) / len(samples)
left_ratio = amplitude(0,440,0.5) / max(amplitude(0,880,0.5),1)
right_ratio = amplitude(1,880,0.5) / max(amplitude(1,440,0.5),1)
if not (left_ratio > 10 and right_ratio > 10):
    raise RuntimeError(f'Stereo separation failed: left ratio={left_ratio}, right ratio={right_ratio}.')
left_mono = amplitude(0,440,3.5)
right_mono = amplitude(1,440,3.5)
if not (left_mono > 1000 and right_mono > 1000):
    raise RuntimeError('Expected mono segment was not present in both output channels.')
if abs(left_mono - right_mono) / max(left_mono, right_mono) >= 0.1:
    raise RuntimeError('Mono segment was not duplicated equally into left/right channels.')
with source.open('rb') as stream:
    source_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
report = {'source_filename':source.name, 'source_sha256':source_hash, 'sample_rate_hz':sample_rate,
          'channels':channels, 'duration_seconds':duration, 'left_440_to_880_ratio':left_ratio,
          'right_880_to_440_ratio':right_ratio, 'mono_segment_left_amplitude':left_mono,
          'mono_segment_right_amplitude':right_mono,
          'result':'PASS: stereo retained; mono duplicated; segment order correct'}
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps(report, indent=2) + '\n',encoding='utf-8')
print(json.dumps(report,indent=2))
