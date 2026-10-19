import gzip,json,unittest
from unittest.mock import MagicMock
from cloud_acceptance import Api

class ResponseCompressionTest(unittest.TestCase):
    def test_public_catalog_plain_and_compressed_responses_have_identical_data(self):
        expected={'records':[{'problemNo':3,'title':'二分查找'}],'total':166}
        raw=json.dumps({'code':0,'data':expected}).encode()
        for encoded,headers in [(raw,{}),(gzip.compress(raw),{'Content-Encoding':'gzip'})]:
            api=Api();response=MagicMock();response.read.return_value=encoded;response.headers=headers
            api.opener.open=MagicMock();api.opener.open.return_value.__enter__.return_value=response
            self.assertEqual(api.call('/question/list/page/vo'),expected)
            self.assertEqual(api.opener.open.call_args.args[0].get_header('Accept-encoding'),'gzip')

if __name__=='__main__':unittest.main()
