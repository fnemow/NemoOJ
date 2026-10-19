"""Publish reviewed changes through admin draft/version APIs; preserve every old snapshot."""
import crypt,json,secrets,time,sys,hashlib
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
from cloud_acceptance import Api

def canonical(value):
    if isinstance(value,dict):return {k:canonical(v) for k,v in value.items() if v is not None}
    if isinstance(value,list):return [canonical(v) for v in value]
    return value

def main(source,output):
    revisions=json.loads(Path(source).read_text());account='quality_admin_'+secrets.token_hex(6);password=secrets.token_hex(16)
    hashed=crypt.crypt(password,crypt.mksalt(crypt.METHOD_BLOWFISH))
    uid=int(mysql("INSERT INTO user(userAccount,userPassword,userName,userRole) VALUES ('"+account+"','"+hashed+"','Private quality release administrator','admin'); SELECT LAST_INSERT_ID();"))
    api=Api();evidence={'started':time.time(),'reviewed':[],'fixtureAdminId':uid};atomic_json(output,evidence)
    try:
        api.login(account,password)
        for change in revisions:
            number=change['number'];public=api.call('/question/get/vo/by-number?problemNo='+str(number))
            q=api.call('/question/admin/detail?id='+str(public['id']));assert q['problemSpec']['methodName']==change['method']
            row={'problemNo':number,'previousId':q['id'],'previousVersion':q['problemVersion'],'review':change['review']}
            if change['changed'] and (q['content']!=change['content'] or canonical(q['judgeCase'])!=canonical(change['acm']) or canonical(q['problemSpec'])!=canonical(change['spec'])):
                existing=mysql('SELECT id FROM question WHERE problemNo='+str(number)+' AND isPublished=0 AND isDelete=0;')
                assert '\n' not in existing
                if existing:
                    owner=mysql('SELECT u.userName FROM question q JOIN user u ON u.id=q.userId WHERE q.id='+existing+';')
                    assert owner=='Private quality release administrator','An unrelated administrator draft exists; do not overwrite it'
                draft_id=existing or api.call('/question/admin/new_version',{'id':q['id'],'expectedRowVersion':q['rowVersion']})
                draft=api.call('/question/admin/detail?id='+str(draft_id))
                body={'id':draft_id,'expectedRowVersion':draft['rowVersion'],'title':q['title'],'content':change['content'],
                      'tags':q['tags'],'answer':q['answer'],'judgeConfig':q['judgeConfig'],'judgeCase':change['acm'],'problemSpec':change['spec']}
                api.call('/question/update',body)
                draft=api.call('/question/admin/detail?id='+str(draft_id))
                api.call('/question/admin/publish',{'id':draft_id,'expectedRowVersion':draft['rowVersion']})
                current=api.call('/question/get/vo/by-number?problemNo='+str(number))
                assert current['id']==draft_id and int(current['problemVersion'])>int(q['problemVersion'])
                row.update(published=True,newId=draft_id,newVersion=current['problemVersion'],acmCases=len(change['acm']),functionCases=len(change['spec']['judgeCases']))
            else:row['published']=False
            evidence['reviewed'].append(row);atomic_json(output,evidence)
        evidence['passed']=len(evidence['reviewed'])==20
    finally:
        # Audit rows retain the operator identity; account can no longer log in.
        mysql("UPDATE user SET userRole='ban',isDelete=1 WHERE id="+str(uid)+';')
        evidence.update(ended=time.time(),fixtureRemoved=True);atomic_json(output,evidence)
    print(json.dumps({'reviewed':len(evidence['reviewed']),'published':sum(r['published'] for r in evidence['reviewed']),'passed':evidence.get('passed',False)}))

if __name__=='__main__':main(sys.argv[1],sys.argv[2])
