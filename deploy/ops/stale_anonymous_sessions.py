"""Bounded anonymous-session maintenance. Never deserialize objects or print keys/tokens."""
import json,socket,struct,subprocess,sys,time
mode=sys.argv[1];assert mode in ('inspect','clean');limit=int(sys.argv[2]);assert 1<=limit<=30000
m=json.loads(subprocess.check_output(['docker','inspect','nemo-oj-redis-1']))[0]
args=m['Config']['Cmd'];secret=args[args.index('--requirepass')+1];ip=next(iter(m['NetworkSettings']['Networks'].values()))['IPAddress']
s=socket.create_connection((ip,6379),5);f=s.makefile('rb')
def reply():
    line=f.readline();kind=line[:1];value=line[1:-2]
    if kind==b'*':return [reply() for _ in range(int(value))]
    if kind==b'$':
        n=int(value)
        if n<0:return None
        data=f.read(n);f.read(2);return data
    if kind==b'-':raise RuntimeError('Redis rejected maintenance command; details withheld')
    return value
def command(*parts):
    data=[p if isinstance(p,bytes) else str(p).encode() for p in parts]
    s.sendall(b'*'+str(len(data)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in data));return reply()
assert command('AUTH',secret)==b'OK'
def timestamp(raw):
    if not raw:return None
    try:
        if raw[:4]==b'\xac\xed\x00\x05' and b'java.lang.Long' in raw:
            value=struct.unpack('>q',raw[-8:])[0]
        else:
            decoded=json.loads(raw)
            value=decoded[1] if isinstance(decoded,list) and len(decoded)==2 and decoded[0]=='java.lang.Long' else decoded
        return value if isinstance(value,int) and 946684800000<value<int(time.time()*1000)+60000 else None
    except Exception:return None
allowed={b'creationTime',b'lastAccessedTime',b'maxInactiveInterval',b'sessionAttr:NEMO_CSRF_TOKEN'}
lua="""#!lua flags=allow-oom
if redis.call('TYPE',KEYS[1]).ok~='hash' then return 0 end
if redis.call('HGET',KEYS[1],'lastAccessedTime')~=ARGV[1] then return 0 end
local fs=redis.call('HKEYS',KEYS[1]);for _,field in ipairs(fs) do
 if field~='creationTime' and field~='lastAccessedTime' and field~='maxInactiveInterval' and field~='sessionAttr:NEMO_CSRF_TOKEN' then return 0 end
end
return redis.call('DEL',KEYS[1])"""
report={'mode':mode,'scanned':0,'eligible':0,'removed':0,'protected':0,'unknownTimestamp':0,'fieldNames':[]};names=set();cursor=b'0';cutoff=int((time.time()-86400)*1000)
while True:
    cursor,keys=command('SCAN',cursor,'MATCH','nemo-oj:session:sessions:*','COUNT',200)
    for key in keys:
        if command('TYPE',key)!=b'hash':continue
        fields=set(command('HKEYS',key));report['scanned']+=1;names.update(fields)
        if not fields.issubset(allowed):report['protected']+=1;continue
        raw=command('HGET',key,'lastAccessedTime');stamp=timestamp(raw)
        if stamp is None:report['unknownTimestamp']+=1;continue
        if stamp>=cutoff:continue
        report['eligible']+=1
        if mode=='clean':report['removed']+=int(command('EVAL',lua,1,key,raw))
        if report['eligible']>=limit:break
    if cursor==b'0' or report['eligible']>=limit:break
report['fieldNames']=sorted(n.decode(errors='replace') for n in names)
info=dict(l.split(':',1) for l in command('INFO','memory').decode().splitlines() if ':' in l)
report['usedMemoryMiB']=round(int(info['used_memory'])/1048576,2);report['maxMemoryMiB']=round(int(info['maxmemory'])/1048576,2)
print(json.dumps(report));s.close()
