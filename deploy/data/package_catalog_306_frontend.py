"""Package only production static assets; no secrets, source maps or environment files."""
import hashlib
import json
import re
import tarfile
from pathlib import Path

root=Path(__file__).parents[2];dist=root/'nemo-frontend/dist';target=root/'build/catalog-306'
html=(dist/'index.html').read_bytes()
with tarfile.open(target/'frontend.tar.gz','w:gz') as archive:
    for path in sorted(dist.iterdir()):archive.add(path,arcname=path.name)
manifest={'htmlSha256':hashlib.sha256(html).hexdigest(),'entryAssets':re.findall(r'(?:src|href)="(/assets/[^"\s]+)"',html.decode())}
(target/'frontend-manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
print(json.dumps({'frontendArchiveBytes':(target/'frontend.tar.gz').stat().st_size,**manifest}))
