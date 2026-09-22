"""Generate synthetic, copyright-free stereo/mono/video fixtures for device tests."""
from pathlib import Path
import math
import struct
import subprocess
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/test-python'))
import imageio_ffmpeg
out = ROOT / 'app/src/androidTest/assets'
out.mkdir(parents=True, exist_ok=True)
def tone(name, rate, channels, seconds):
    with wave.open(str(out / name), 'wb') as w:
        w.setnchannels(channels); w.setsampwidth(2); w.setframerate(rate)
        frames = bytearray()
        for i in range(rate * seconds):
            envelope = min(1, i / 100, (rate * seconds - i) / 100)
            for channel in range(channels):
                frequency = 440 if channel == 0 else 880
                frames += struct.pack('<h', int(8000 * envelope * math.sin(2 * math.pi * frequency * i / rate)))
        w.writeframes(frames)
tone('stereo48.wav', 48000, 2, 3)
tone('mono44.wav', 44100, 1, 2)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=black:s=64x64:r=5:d=10', '-stream_loop', '-1', '-i', str(out / 'stereo48.wav'), '-t', '10', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', str(out / 'video.mp4')], check=True)
subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=black:s=64x64:r=5:d=1', '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(out / 'silent.mp4')], check=True)
# Same-filename publication tests need distinct AAC payloads. Keep the generated
# mono/stereo layouts and sample rates, including their different codec configs.
for source, target in [('mono44.wav', 'collision-mono.m4a'), ('stereo48.wav', 'collision-stereo.m4a')]:
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y',
                    '-i', str(out / source), '-map', '0:a:0', '-c:a', 'aac', '-b:a', '128k',
                    '-map_metadata', '-1', str(out / target)], check=True)
print('Generated fixtures:', [p.name for p in out.iterdir()])
