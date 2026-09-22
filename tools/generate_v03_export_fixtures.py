"""Generate identifiable audio/video and exact 24-bit PCM for V0.3 export tests."""
from pathlib import Path
import subprocess
import sys
import wave
import struct

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.tools/test-python'))
import imageio_ffmpeg

out = root / 'app/src/androidTest/assets'
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
base = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-y']
subprocess.run(base + ['-f', 'lavfi', '-i', 'color=c=blue:s=64x64:r=5:d=4', '-f', 'lavfi', '-i', 'aevalsrc=0.2*sin(2*PI*1320*t)|0.2*sin(2*PI*1760*t):s=48000:d=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', str(out / 'video-second.mp4')], check=True)
subprocess.run(base + ['-i', str(out / 'video-second.mp4'), '-vn', '-c:a', 'copy', str(out / 'audio-second.m4a')], check=True)
with wave.open(str(out / 'exact24.wav'), 'wb') as w:
    w.setnchannels(2); w.setsampwidth(3); w.setframerate(48000)
    frames = bytearray()
    for index in range(4800):
        for value in ((index * 7919 % 16777216) - 8388608, 8388607 - (index * 3571 % 16777216)):
            frames.extend(struct.pack('<i', value)[:3])
    w.writeframes(frames)
with wave.open(str(out / 'mono48.wav'), 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(48000)
    w.writeframes(b''.join(struct.pack('<h', (i * 37 % 65536) - 32768) for i in range(4800)))
print('Generated four V0.3 export fixtures')
