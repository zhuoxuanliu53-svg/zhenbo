"""Verify bundled OCR assets against the pinned upstream/derived manifest."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/ocr'
manifest = json.loads((root / 'models.json').read_text(encoding='utf-8'))
for name, entry in manifest.items():
    if Path(name).name != name:
        raise SystemExit('Invalid model filename')
    content = (root / name).read_bytes()
    if hashlib.sha256(content).hexdigest() != entry['sha256']:
        raise SystemExit('Checksum mismatch: ' + name)
    if 'bytes' in entry and len(content) != entry['bytes']:
        raise SystemExit('Size mismatch: ' + name)
print(f'Validated {len(manifest)} pinned OCR files ({sum((root / n).stat().st_size for n in manifest):,} bytes).')
