"""Produce a shareable summary from an independent-host acceptance JSON file."""
import argparse,collections,json,math
from pathlib import Path
from cloud_acceptance import percentile

def summarize(data):
    rows=data['submissions'];resources=data.get('resources',[])
    groups=collections.defaultdict(list)
    for row in rows:
        if 'seconds' in row:groups[(row['language'],row.get('mode','FUNCTION'),row.get('expected','accepted'))].append(row['seconds'])
    cpu=[]
    for first,last in zip(resources,resources[1:]):
        a,b=first['cpuTicks'][:8],last['cpuTicks'][:8]
        total=sum(b)-sum(a);idle=b[3]+b[4]-a[3]-a[4]
        if total>0:cpu.append(100*(1-idle/total))
    passed=bool(data.get('summary',{}).get('passed'))
    result={'passed':passed,'profile':data.get('profile','binary'),'durationSeconds':data['duration'],
            'dispatchSeconds':data.get('dispatchSeconds'),'configuredSubmissionsPerMinute':60/data['interval'],
            'submissions':len(rows),'verdicts':dict(collections.Counter(row.get('verdict','missing') for row in rows)),
            'byLanguage':dict(collections.Counter(row['language'] for row in rows)),
            'expectedVerdicts':sum(row.get('verdict')==row.get('expected','accepted') for row in rows),
            'summary':data.get('summary'),
            'groups':[{'language':k[0],'mode':k[1],'expected':k[2],'count':len(v),'p50Seconds':percentile(v,.5),
                       'p95Seconds':percentile(v,.95),'maximumSeconds':max(v)} for k,v in sorted(groups.items())],
            'containsUserCode':False,'capacityScope':'This profile and tested arrival rate; not a production maximum'}
    if resources:
        first,last=resources[0],resources[-1]
        result['resources']={'samples':len(resources),'minimumAvailableMiB':min(r['availableMiB'] for r in resources),
             'pendingMaximum':max(r['pending'] for r in resources),'oomDelta':last['oom']-first['oom'],
             'swapInPages':last['swapIn']-first['swapIn'],'swapOutPages':last['swapOut']-first['swapOut'],
             'swapUsedMiBBefore':first['swapUsedMiB'],'swapUsedMiBAfter':last['swapUsedMiB'],
             'meanSampleCpuPercent':sum(cpu)/len(cpu) if cpu else None,'maximumSampleCpuPercent':max(cpu) if cpu else None}
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('evidence');parser.add_argument('output');args=parser.parse_args()
    report=summarize(json.loads(Path(args.evidence).read_text(encoding='utf-8-sig')))
    Path(args.output).write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='groups'}))
