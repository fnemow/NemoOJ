import copy,json,pytest
from nemo_agent.custom_structures import validate_graph,validate_signature,canonical_graph
from nemo_agent.schemas import CanonicalSignature
from nemo_agent.private_generation import plain_value

DEFINITION={'name':'ColoredVertex','fields':[{'name':'value','type':'int'},{'name':'parent','type':'ref'},{'name':'links','type':'ref[]'}]}
GRAPH={'root':1,'nodes':[{'value':3,'parent':1,'links':[1]},{'value':5,'parent':-1,'links':[0,0,-1]}]}
def signature():return {'methodName':'solve','parameters':[{'name':'start','type':'node<ColoredVertex>'}],'returnType':'node<ColoredVertex>','dataStructures':[copy.deepcopy(DEFINITION)]}
def test_cycles_sharing_and_plain_protocol_are_bounded_and_preserved():
    validate_graph(GRAPH,DEFINITION);CanonicalSignature(**signature())
    assert plain_value(GRAPH,'node<ColoredVertex>',[DEFINITION])=='2\n1\n3\n1\n1\n1\n5\n-1\n3\n0\n0\n-1\n'
    assert plain_value(None,'node<ColoredVertex>',[DEFINITION])=='-1\n'
@pytest.mark.parametrize('mutation',[lambda g:g.update(root=2),lambda g:g['nodes'][0].update(parent=3),lambda g:g['nodes'][0].update(value=True),lambda g:g['nodes'][1].update(links=[0]*33),lambda g:g['nodes'][1].update(extra=1),lambda g:g.update(nodes=g['nodes']*101),lambda g:g['nodes'][1].update(links=[])])
def test_invalid_graph_is_rejected(mutation):
    g=copy.deepcopy(GRAPH);mutation(g)
    with pytest.raises(ValueError):validate_graph(g,DEFINITION)
@pytest.mark.parametrize('mutation',[lambda s:s.update(dataStructures=[]),lambda s:s['dataStructures'][0].update(name='Solution'),lambda s:s['dataStructures'][0]['fields'][0].update(name='class'),lambda s:s['dataStructures'][0]['fields'][0].update(type='arbitrary_code'),lambda s:s['dataStructures'][0]['fields'].append({'name':'value','type':'int'})])
def test_undeclared_types_and_executable_descriptor_names_are_rejected(mutation):
    s=signature();mutation(s)
    with pytest.raises(ValueError):CanonicalSignature(**s)

def test_input_diagnostic_never_logs_provider_text(caplog):
    from nemo_agent.analysis_graph import log_input_contract
    import logging
    with caplog.at_level(logging.INFO):
        log_input_contract(ValueError('secret provider source'))
        log_input_contract(ValueError('custom reference'))
    assert 'secret provider source' not in caplog.text
    assert 'reason=invalid_specification' in caplog.text and 'reason=custom reference' in caplog.text

def test_rooted_graph_normalization_preserves_cycles_sharing_and_drops_only_unreachable_records():
    g=copy.deepcopy(GRAPH);g['nodes'].append({'value':99,'parent':-1,'links':[]});original=copy.deepcopy(g)
    result=canonical_graph(g,DEFINITION)
    assert g==original and len(result['nodes'])==2 and result['root']==0
    assert result['nodes'][0]=={'value':5,'parent':-1,'links':[1,1,-1]}
    assert result['nodes'][1]=={'value':3,'parent':0,'links':[0]}
    validate_graph(result,DEFINITION)
    g['nodes'][2]['parent']=999
    with pytest.raises(ValueError,match='custom reference'):canonical_graph(g,DEFINITION)
