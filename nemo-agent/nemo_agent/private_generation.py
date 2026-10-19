"""Pure data preparation. Generated programs are executed exclusively by the judge sandbox."""
import copy
import json
import keyword
import math
import re

from nemo_agent.schemas import CanonicalPrivateDraft
from nemo_agent.custom_structures import is_node,validate_signature,validate_graph,canonical_graph

PROTOCOL = "CANONICAL_LINES_V1"
RESERVED = set("class public private protected static return void int long double boolean string new null true false import package using namespace std main Main Solution Runner NemoPlainIO".split())


def plain_value(value, kind, structures=None):
    """Length-prefixed recursive lines; exactly the same codec is used by trusted wrappers."""
    if is_node(kind):
        definition=next(d for d in structures or [] if d['name']==kind[5:-1]);validate_graph(value,definition)
        if value is None:return '-1\n'
        return str(len(value['nodes']))+'\n'+str(value['root'])+'\n'+''.join(plain_value(node[f['name']], 'int' if f['type']=='ref' else 'int[]' if f['type']=='ref[]' else f['type']) for node in value['nodes'] for f in definition['fields'])
    if kind.endswith("[]") or kind.startswith("list<"):
        element = kind[:-2] if kind.endswith("[]") else kind[5:-1]
        if type(value) is not list or len(value) > 200:
            raise ValueError("small array input required")
        return str(len(value)) + "\n" + "".join(plain_value(v, element) for v in value)
    if kind in {"list_node<int>", "tree_node<int>"}:
        if value is None:
            return "-1\n"
        if type(value) is not list or len(value) > 200:
            raise ValueError("small node input required")
        tree = kind == "tree_node<int>"
        if tree:
            if value and value[0] is None and any(v is not None for v in value):
                raise ValueError("unreachable tree node")
            pending = 1
            for v in value:
                if pending == 0 and v is not None:
                    raise ValueError("unreachable tree node")
                if pending > 0:
                    pending -= 1
                    if v is not None: pending += 2
        return str(len(value)) + "\n" + "".join("null\n" if v is None and tree else plain_value(v, "int") for v in value)
    if kind in {"int", "long"}:
        bits = 32 if kind == "int" else 64
        if type(value) is not int or not -(2 ** (bits-1)) <= value < 2 ** (bits-1):
            raise ValueError("integer type/range")
        return str(value) + "\n"
    if kind == "double":
        if type(value) not in {int, float} or not math.isfinite(value):
            raise ValueError("finite number required")
        return format(float(value), ".16e") + "\n"
    if kind == "boolean":
        if type(value) is not bool: raise ValueError("boolean required")
        return ("true" if value else "false") + "\n"
    if kind == "string":
        if type(value) is not str or "\n" in value or "\r" in value or len(value) > 200:
            raise ValueError("small single-line string required")
        return value + "\n"
    raise ValueError("unsupported value type")


def prepare_candidate(proposal: CanonicalPrivateDraft) -> dict:
    p = proposal.model_dump()
    signature = p["signature"]
    validate_signature(signature)
    structures=signature.pop('dataStructures',[])
    if structures:signature['dataStructures']=structures
    names = [a["name"] for a in signature["parameters"]]
    for name in [signature["methodName"], *names]:
        if keyword.iskeyword(name) or name in RESERVED or name.startswith("__"):
            raise ValueError("reserved identifier")
    if len(names) != len(set(names)): raise ValueError("duplicate parameter")
    # Existing three-language harnesses share this subset. Unsupported list aliases
    # are rejected rather than silently translated into a different signature.
    supported = {"int", "long", "double", "boolean", "string", "int[]", "long[]", "double[]", "boolean[]", "string[]", "int[][]", "list<int>", "list_node<int>", "tree_node<int>"}
    if any(a["type"] not in supported and not is_node(a['type']) for a in signature["parameters"]) or (signature["returnType"] not in supported and not is_node(signature['returnType'])):
        raise ValueError("type not supported by all three harnesses")
    cases, fn = [], []
    for i, raw in enumerate(p["inputs"]):
        if len(raw) > 12000: raise ValueError("input too large")
        args = json.loads(raw)
        if not isinstance(args, dict) or set(args) != set(names): raise ValueError("parameter-keyed JSON required")
        for parameter in signature['parameters']:
            if is_node(parameter['type']):
                definition=next(d for d in structures if d['name']==parameter['type'][5:-1])
                args[parameter['name']]=canonical_graph(args[parameter['name']],definition)
        stdin = "".join(plain_value(args[a["name"]], a["type"],structures) for a in signature["parameters"])
        canonical = json.dumps(args, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        if len(stdin) > 12000 or len(canonical) > 12000: raise ValueError("input too large")
        cases.append({"input": stdin, "output": "", "isHidden": i >= 2})
        fn.append({"input": canonical, "output": "null", "isHidden": i >= 2})
    java = p["javaSolution"]
    if re.search(r"\bpublic\s+class\s+Solution\b", java):
        java = re.sub(r"\bpublic\s+class\s+Solution\b", "class Solution", java, count=1)
    java = "import java.util.*;\n" + java
    cpp = "#include <bits/stdc++.h>\nusing namespace std;\n" + p["cppSolution"]
    spec = {"mode":"function", "className":"Solution", **signature, "resultSource":"return", "comparator":{"type":"EXACT","epsilon":None}, "harnessVersion":1, "judgeCases":fn}
    protocol = "\n\n## ACM 输入与输出约定\n\n参数按函数签名顺序读取。标量每值一行；字符串整行读取，可为空但不含换行；数组或列表先读取长度，再逐项读取；矩阵递归按数组读取，每行先给出长度；链表和二叉树先读取序列长度，随后每项一行，二叉树采用层序并用 null 表示空节点，-1 表示空链表或空树。输出按返回类型采用相同规则，布尔值为 true/false，double 使用小数点后 16 位的科学计数法（例如 1.0000000000000000e+00）。核心代码模式按下方函数签名返回结果。\n"
    if structures:protocol+='自定义节点：空节点单行 -1；非空先读节点数量、根编号，再按编号顺序读取每个节点的声明字段；ref按int读取（-1为空引用），ref[]按int[]读取。输出采用相同协议，节点以声明字段顺序广度遍历重新编号，根为0。核心代码输入为 {root:编号,nodes:节点记录列表}，类由平台提供。\n'
    return {"inputProtocol":PROTOCOL,"title":p["title"],"tags":p["tags"],"difficulty":p["difficulty"],"contentMarkdown":p["contentMarkdown"]+protocol,
            "judgeCases":cases,"judgeConfig":{"timeLimit":2000,"memoryLimit":262144,"outputLimitBytes":1048576},
            "problemSpec":spec,"referenceSolutions":{"python:FUNCTION":p["pythonSolution"],"java:FUNCTION":java,"cpp:FUNCTION":cpp},
            "generation":{"protocol":PROTOCOL,"oracleSolution":p["pythonOracle"],"solutionStrategy":p["solutionStrategy"],"oracleStrategy":p["oracleStrategy"]}}


def patch_target(validation: dict) -> str | None:
    combo = validation.get("combination", "")
    if combo == "python:ORACLE": return "oracle"
    if combo in {"python:FUNCTION", "java:FUNCTION", "cpp:FUNCTION"}: return combo
    # ACM wrappers are trusted platform code; never ask the model to rewrite them.
    return None


def apply_patch(candidate: dict, target: str, source: str) -> dict:
    result = copy.deepcopy(candidate)
    if target == "oracle": result["generation"]["oracleSolution"] = source
    elif target in {"python:FUNCTION", "java:FUNCTION", "cpp:FUNCTION"}:
        if target == "java:FUNCTION": source = "import java.util.*;\n" + source
        if target == "cpp:FUNCTION": source = "#include <bits/stdc++.h>\nusing namespace std;\n" + source
        result["referenceSolutions"][target] = source
    else: raise ValueError("invalid patch target")
    if result["generation"]["oracleSolution"].strip() == result["referenceSolutions"]["python:FUNCTION"].strip():
        raise ValueError("oracle must remain independent")
    return result
