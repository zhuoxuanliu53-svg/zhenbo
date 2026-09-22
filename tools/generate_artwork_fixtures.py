"""Create synthetic colored artwork and a video with an initially black frame."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.tools/test-python'))
import imageio_ffmpeg

ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
assets = root / 'app/src/androidTest/assets'

def run(*args):
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)], check=True)

run('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=5:duration=4',
    '-vf', "drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable='lt(t,0.5)'",
    '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', assets / 'artwork-video.mp4')

with tempfile.TemporaryDirectory() as folder:
    cover = Path(folder) / 'cover.jpg'
    run('-f', 'lavfi', '-i', 'testsrc2=size=800x800', '-frames:v', 1, cover)
    run('-i', assets / 'mono44.wav', '-i', cover, '-map', '0:a', '-map', '1:v',
        '-c:a', 'libmp3lame', '-c:v', 'copy', '-id3v2_version', 3,
        '-metadata:s:v', 'title=Test cover', '-metadata:s:v', 'comment=Cover (front)',
        '-disposition:v:0', 'attached_pic', assets / 'artwork-audio.mp3')
print('Generated synthetic artwork fixtures.')
