"""Generate original multilingual OCR images and embedded subtitle fixtures."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/test-python'))
from PIL import Image, ImageDraw, ImageFont
import imageio_ffmpeg

out = ROOT / 'app/src/androidTest/assets/subtitles'
out.mkdir(parents=True, exist_ok=True)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
font_path = Path('C:/Windows/Fonts/msyh.ttc')
font_jp = next((p for p in [Path('C:/Windows/Fonts/meiryo.ttc'), Path('C:/Windows/Fonts/YuGothM.ttc'), font_path] if p.exists()))
samples = {'zh': '请闭上眼睛，慢慢放松。', 'en': 'Close your eyes and relax.', 'ja': '目を閉じて、ゆっくり休んでください。'}
for language, text in samples.items():
    image = Image.new('RGB', (1280, 720), '#20232b')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(font_jp if language == 'ja' else font_path), 48)
    draw.text((640, 600), text, font=font, anchor='mm', fill='white', stroke_width=2, stroke_fill='black')
    image.save(out / f'{language}.png')
(out / 'reference.srt').write_text('1\n00:00:01,000 --> 00:00:02,500\nFirst subtitle\n\n2\n00:00:03,000 --> 00:00:04,500\n第二行字幕\nAnother line\n', encoding='utf-8')
for codec, extension in [('srt', 'mkv'), ('ass', 'mkv'), ('mov_text', 'mp4')]:
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(ROOT/'app/src/androidTest/assets/video.mp4'), '-i', str(out/'reference.srt'), '-map', '0:v', '-map', '0:a', '-map', '1:0', '-c:v', 'copy', '-c:a', 'copy', '-c:s', codec, '-metadata:s:s:0', 'language=zho', str(out/f'embedded-{codec}.{extension}')], check=True)
subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-loop', '1', '-i', str(out/'zh.png'), '-i', str(ROOT/'app/src/androidTest/assets/stereo48.wav'), '-t', '3', '-r', '8', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', str(out/'burned.mp4')],check=True)
print('Created OCR and subtitle fixtures')

# A small ISO-BMFF WebVTT track. FFmpeg cannot mux wvtt, so reuse its timed-text
# timing/header boxes and replace only the sample description and sample tables.
import struct
def box(kind, payload):
    return struct.pack('>I', 8+len(payload))+kind+payload
def children(data):
    pos=0
    while pos+8<=len(data):
        size=struct.unpack_from('>I',data,pos)[0]
        if size<8: raise ValueError('Invalid fixture atom')
        yield data[pos+4:pos+8],data[pos+8:pos+size]
        pos+=size
source=(out/'embedded-mov_text.mp4').read_bytes()
atoms=dict(children(source)); moov=list(children(atoms[b'moov']))
track=next(payload for kind,payload in moov if kind==b'trak' and b'tx3g' in payload)
samples=[box(b'vtte',b''),box(b'vttc',box(b'payl',b'First subtitle')),box(b'vtte',b''),box(b'vttc',box(b'payl','第二行字幕\nAnother line'.encode())),box(b'vtte',b'')]
ftyp=box(b'ftyp',b'isom'+struct.pack('>I',512)+b'isomiso2mp41')
sample_offset=len(ftyp)+8
def rewrite(data):
    result=b''
    for kind,payload in children(data):
        if kind in [b'trak',b'mdia',b'minf',b'stbl']: payload=rewrite(payload)
        elif kind==b'stsd': payload=b'\0'*4+struct.pack('>I',1)+box(b'wvtt',b'\0'*6+b'\0\1'+box(b'vttC',b'WEBVTT\n'))
        elif kind==b'stsz': payload=b'\0'*8+struct.pack('>I',len(samples))+b''.join(struct.pack('>I',len(s)) for s in samples)
        elif kind==b'stco': payload=b'\0'*4+struct.pack('>II',1,sample_offset)
        elif kind==b'stsc': payload=b'\0'*4+struct.pack('>IIII',1,1,len(samples),1)
        result+=box(kind,payload)
    return result
(out/'embedded-wvtt.mp4').write_bytes(ftyp+box(b'mdat',b''.join(samples))+box(b'moov',next(box(k,p) for k,p in moov if k==b'mvhd')+box(b'trak',rewrite(track))))
