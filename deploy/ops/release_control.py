"""Local SSH orchestration for an explicitly authorized private acceptance release.
Does not load credentials into evidence. Remote script output must be sanitized.
"""
import argparse,json,subprocess,sys,tarfile,zipfile,io
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]

def remote(host,key,script,timeout=180):
    encoded=script.encode().hex()
    command="sudo python3 -c 'exec(bytes.fromhex(\""+encoded+"\").decode())'"
    result=subprocess.run(['ssh','-i',str(key),'-o','BatchMode=yes','-o','ConnectTimeout=8',
                           '-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3',host,command],
                          capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError('SSH control failed: '+result.stderr.decode('utf-8','replace')[-600:])
    return result.stdout.decode('utf-8','replace')

def package(target):
    target=Path(target)
    with tarfile.open(target,'w:gz') as archive:
        for folder in ['nemo-agent/nemo_agent','nemo-agent/tests','deploy/ops','deploy/migration','deploy/haproxy']:
            for source in (ROOT/folder).rglob('*'):
                if source.is_file() and '__pycache__' not in source.parts:
                    archive.add(source,arcname=source.relative_to(ROOT))
        for filename in ['deploy/docker-compose.yml','deploy/compose.core-cloud.yml','deploy/compose.core-4g-preview.yml','deploy/java-service.Dockerfile']:
            archive.add(ROOT/filename,arcname=filename)
        jar=ROOT/'OJ-backend-microservice/OJ-backend-question-service/target/OJ-backend-question-service-0.0.1-SNAPSHOT.jar'
        # Dependencies are already present in the deployed image. Ship only
        # application classes/shared modules; verify every retained library.
        with zipfile.ZipFile(jar) as source:
            manifest={i.filename:{'crc':i.CRC,'size':i.file_size} for i in source.infolist()
                      if i.filename.startswith('BOOT-INF/lib/') and i.filename.endswith('.jar')
                      and not i.filename.startswith('BOOT-INF/lib/OJ-backend-')}
            overlay=io.BytesIO()
            with zipfile.ZipFile(overlay,'w',zipfile.ZIP_DEFLATED) as patch:
                for item in source.infolist():
                    if (item.filename.startswith('BOOT-INF/classes/') or item.filename.startswith('BOOT-INF/lib/OJ-backend-')
                            or item.filename in ['META-INF/MANIFEST.MF','BOOT-INF/classpath.idx']):
                        patch.writestr(item,source.read(item))
            for name,value in [('question/overlay.zip',overlay.getvalue()),('question/libraries.json',json.dumps(manifest).encode())]:
                entry=tarfile.TarInfo(name);entry.size=len(value);archive.addfile(entry,io.BytesIO(value))
    return target

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--host');p.add_argument('--key');p.add_argument('--script');p.add_argument('--package');p.add_argument('--timeout',type=int,default=180)
    a=p.parse_args()
    if a.package:print(package(a.package))
    else:print(remote(a.host,a.key,Path(a.script).read_text(encoding='utf-8'),a.timeout))
