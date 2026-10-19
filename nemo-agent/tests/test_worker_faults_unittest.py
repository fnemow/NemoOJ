"""Deterministic fault injection: no real model calls and no emails."""
import unittest,uuid,json
from types import SimpleNamespace
from unittest.mock import AsyncMock,patch
from nemo_agent.worker import Worker,ResultDeliveryError
from nemo_agent.config import Settings
from nemo_agent.analysis_graph import create_model
from nemo_agent.signed_client import QuestionTransportError

class Message:
 def __init__(self):self.body=json.dumps({'taskId':str(uuid.uuid4())}).encode()
 def process(self,**kwargs):return self
 async def __aenter__(self):return self
 async def __aexit__(self,*args):return False

class WorkerFaults(unittest.IsolatedAsyncioTestCase):
 def setup_worker(self):
  client=SimpleNamespace(claim=AsyncMock(return_value=True),context=AsyncMock(return_value={}),complete=AsyncMock(),fail=AsyncMock())
  graph=SimpleNamespace(checkpointer=None,ainvoke=AsyncMock(return_value={'report':{'summary':'test'},'input_tokens':10,'output_tokens':5}))
  return Worker(Settings(environment='test'),client,graph),client,graph
 async def test_timeout_reports_failure_once(self):
  w,c,g=self.setup_worker();g.ainvoke.side_effect=TimeoutError()
  await w.handle(Message());self.assertEqual(g.ainvoke.await_count,1);c.complete.assert_not_awaited();self.assertEqual(c.fail.await_args.args[1],'MODEL_TIMEOUT')
 async def test_site_help_payload_is_separate_from_analysis_and_feedback(self):
  w,c,g=self.setup_worker();c.context.return_value={'taskType':'SITE_HELP'}
  g.ainvoke.return_value={'site_help':{'kind':'HELP','sections':['submit_flow']},'input_tokens':1,'output_tokens':2}
  await w.handle(Message())
  self.assertEqual(c.complete.await_args.args[1],{'siteHelp':{'kind':'HELP','sections':['submit_flow']}})
  self.assertEqual(g.ainvoke.await_count,1)
 async def test_structural_failure_does_not_become_a_model_retry(self):
  from nemo_agent.generation_diagnostics import GenerationFailure
  w,c,g=self.setup_worker();g.ainvoke.side_effect=GenerationFailure('INPUT_CONTRACT','SPECIFICATION')
  await w.handle(Message());self.assertEqual(c.fail.await_args.args[1],'INVALID_OUTPUT');self.assertEqual(g.ainvoke.await_count,1)
 async def test_claim_disconnect_does_not_fail_an_unowned_task(self):
  w,c,g=self.setup_worker();c.claim.side_effect=ConnectionError()
  with self.assertRaises(ConnectionError):await w.handle(Message())
  g.ainvoke.assert_not_awaited();c.fail.assert_not_awaited()
 async def test_private_link_polling_failure_preserves_checkpoint_and_reservation(self):
  w,c,g=self.setup_worker();g.ainvoke.side_effect=QuestionTransportError('private polling unavailable')
  with patch('nemo_agent.worker.asyncio.sleep',new=AsyncMock()):
   with self.assertRaises(QuestionTransportError):await w.handle(Message())
  self.assertEqual(g.ainvoke.await_count,1);c.complete.assert_not_awaited();c.fail.assert_not_awaited()
 async def test_private_link_context_failure_does_not_fail_claimed_task(self):
  w,c,g=self.setup_worker();c.context.side_effect=QuestionTransportError('private context unavailable')
  with patch('nemo_agent.worker.asyncio.sleep',new=AsyncMock()):
   with self.assertRaises(QuestionTransportError):await w.handle(Message())
  g.ainvoke.assert_not_awaited();c.fail.assert_not_awaited()
 async def test_provider_rate_limit_does_not_retry_in_worker(self):
  w,c,g=self.setup_worker();g.ainvoke.side_effect=RuntimeError('429')
  await w.handle(Message());self.assertEqual(g.ainvoke.await_count,1);self.assertEqual(c.fail.await_count,1)
 async def test_completion_disconnect_only_retries_delivery(self):
  w,c,g=self.setup_worker();c.complete.side_effect=[ConnectionError(),None]
  with patch('nemo_agent.worker.asyncio.sleep',new=AsyncMock()):await w.handle(Message())
  self.assertEqual(g.ainvoke.await_count,1);self.assertEqual(c.complete.await_count,2);c.fail.assert_not_awaited()
 async def test_persistent_delivery_failure_preserves_checkpoint_and_reservation(self):
  w,c,g=self.setup_worker();c.complete.side_effect=ConnectionError()
  with patch('nemo_agent.worker.asyncio.sleep',new=AsyncMock()):
   with self.assertRaises(ResultDeliveryError):await w.handle(Message())
  self.assertEqual(g.ainvoke.await_count,1);self.assertEqual(c.complete.await_count,3);c.fail.assert_not_awaited()
 async def test_completed_checkpoint_reused_without_model_call(self):
  w,c,g=self.setup_worker();g.checkpointer=object();g.aget_state=AsyncMock(return_value=SimpleNamespace(values={'report':{'summary':'saved'}},next=()))
  await w.handle(Message());g.ainvoke.assert_not_awaited();self.assertEqual(c.complete.await_args.args[1],{'report':{'summary':'saved'}})
 async def test_private_deadline_accepts_backend_long_serialized_as_string(self):
  import time
  w,c,g=self.setup_worker();c.context.return_value={'taskType':'PRIVATE_PROBLEM','deadlineEpoch':str(int(time.time())+900)};c.heartbeat=AsyncMock();g.ainvoke.return_value={'private_problem':{'title':'test'}}
  await w.handle(Message());c.fail.assert_not_awaited();self.assertIsInstance(g.ainvoke.await_args.args[0]['context']['deadlineEpoch'],float);self.assertEqual(c.complete.await_args.args[1],{'privateProblem':{'title':'test'}})
 def test_sdk_retries_disabled(self):
  model=create_model(Settings(environment='test',provider='openai-compatible',model_id='test',api_key='test',base_url='http://127.0.0.1:1'))
  self.assertEqual(model.max_retries,0)
if __name__=='__main__':unittest.main()
