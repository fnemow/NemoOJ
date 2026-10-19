import json,sys
from pathlib import Path
from release_control import remote
ROOT=Path(__file__).resolve().parents[2];out=ROOT/'build/trusted-artifacts-20261014';stage='/home/ubuntu/nemo-artifacts-20261014';key=Path('C:/Users/nemo/.ssh/id_ed25519_nemooj')
names=['prewarm.json','restart-check.json','live-generation.json','formal.json','judge-release.json']
script='import json;from pathlib import Path;root=Path('+repr(stage)+');print(json.dumps({name:json.loads((root/name).read_text()) for name in '+repr(names)+' if (root/name).exists()}))'
reports=json.loads(remote('ubuntu@43.142.118.220',key,script))
for name,report in reports.items():(out/name).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
rows=reports['prewarm.json']['samples'];cold=sum(r['coldSeconds'] for r in rows)/len(rows);warm=sum(r['warmSeconds'] for r in rows)/len(rows)
summary={'contracts':len(rows),'coldMeanSeconds':round(cold,3),'warmMeanSeconds':round(warm,3),'validationReductionPercent':round((1-warm/cold)*100,2),'allPassed':reports['prewarm.json']['passed'],'restartCachePassed':reports['restart-check.json']['passed']}
if 'live-generation.json' in reports:summary['liveGeneration']=reports['live-generation.json']
if 'formal.json' in reports:summary['formalPassed']=reports['formal.json'].get('passed')
(out/'performance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(summary,ensure_ascii=False))
