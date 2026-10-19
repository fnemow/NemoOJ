"""Package only compiled classes selected for this release."""
import hashlib,json,tarfile,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2];stage=Path(sys.argv[1]);stage.mkdir(parents=True,exist_ok=True)
modules={
 'sandbox':(root/'nemo-sandbox/target/classes',('com/nemo/nemosandbox/TrustedCompileArtifactCache','com/nemo/nemosandbox/JavaDockerCodeSandBox','com/nemo/nemosandbox/config/SandboxExecutionMetrics','com/nemo/nemosandbox/model/ExecuteCodeRequestV2')),
 'judge-service':(root/'OJ-backend-microservice/OJ-backend-judge-service/target/classes',('com/nemo/OJbackendjudgeservice/judge/PrivateProblemValidationService',)),
 'question-service':(root/'OJ-backend-microservice/OJ-backend-question-service/target/classes',('com/nemo/OJbackendquestionservice/service/PrivateValidationCheckpointService',)),
 'model':(root/'OJ-backend-microservice/OJ-backend-model/target/classes',('com/nemo/OJbackendmodel/model/codesandbox/ExecuteCodeRequestV2',))}
manifest={}
with tarfile.open(stage/'classes.tgz','w:gz') as archive:
 for module,(folder,prefixes) in modules.items():
  for prefix in prefixes:
   matches=list((folder/prefix).parent.glob((folder/prefix).name+'*.class'));assert matches
   for file in matches:
    name=module+'/'+file.relative_to(folder).as_posix();manifest[name]=hashlib.sha256(file.read_bytes()).hexdigest();archive.add(file,arcname=name)
(stage/'manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps({'classes':len(manifest),'bytes':(stage/'classes.tgz').stat().st_size}))
