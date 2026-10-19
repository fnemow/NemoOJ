import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from nemo_agent.model_calls import ModelCallRecorder, ReceiptDeliveryError


class ModelCallTests(unittest.IsolatedAsyncioTestCase):
    def setup_recorder(self):
        client=SimpleNamespace(start_call=AsyncMock(),finish_call=AsyncMock())
        settings=SimpleNamespace(environment="test",mock_enabled=False,
                                 input_usd_per_million_tokens=1,output_usd_per_million_tokens=2)
        return ModelCallRecorder(client,settings,"task","worker"),client

    async def test_budget_rejection_never_invokes_provider(self):
        recorder,client=self.setup_recorder();client.start_call.side_effect=RuntimeError("budget")
        model=SimpleNamespace(ainvoke=AsyncMock())
        with self.assertRaises(RuntimeError):await recorder.invoke(model,[])
        model.ainvoke.assert_not_awaited();client.finish_call.assert_not_awaited()

    async def test_timeout_and_success_are_distinct_accounted_calls(self):
        recorder,client=self.setup_recorder()
        raw=SimpleNamespace(usage_metadata={"input_tokens":100,"output_tokens":20})
        model=SimpleNamespace(ainvoke=AsyncMock(side_effect=[TimeoutError(),{"raw":raw}]))
        with self.assertRaises(TimeoutError):await recorder.invoke(model,[])
        await recorder.invoke(model,[])
        receipts=[c.args for c in client.finish_call.await_args_list]
        self.assertEqual([r[3] for r in receipts],["UNKNOWN","KNOWN"])
        self.assertNotEqual(receipts[0][1],receipts[1][1]);self.assertEqual(receipts[1][-1],"0.00014")

    async def test_invalid_structured_output_still_accounts_known_provider_usage(self):
        recorder,client=self.setup_recorder()
        answer={"parsed":None,"raw":SimpleNamespace(usage_metadata={"input_tokens":3,"output_tokens":4})}
        result=await recorder.invoke(SimpleNamespace(ainvoke=AsyncMock(return_value=answer)),[])
        self.assertIs(result,answer);self.assertEqual(client.finish_call.await_args.args[3],"KNOWN")

    async def test_missing_usage_does_not_claim_a_free_call(self):
        recorder,client=self.setup_recorder()
        await recorder.invoke(SimpleNamespace(ainvoke=AsyncMock(return_value={"raw":None})),[])
        self.assertEqual(client.finish_call.await_args.args[3],"UNKNOWN")

    async def test_receipt_retries_do_not_repeat_inference(self):
        recorder,client=self.setup_recorder();client.finish_call.side_effect=[ConnectionError(),None]
        model=SimpleNamespace(ainvoke=AsyncMock(return_value={"raw":SimpleNamespace(usage_metadata={"input_tokens":1,"output_tokens":2})}))
        with patch("nemo_agent.model_calls.asyncio.sleep",new=AsyncMock()):await recorder.invoke(model,[])
        self.assertEqual(model.ainvoke.await_count,1);self.assertEqual(client.finish_call.await_count,2)

if __name__=="__main__":unittest.main()
