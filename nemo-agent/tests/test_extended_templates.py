"""Execute repository-authored references only; never provider code on the host."""
import copy
import pytest
from nemo_agent.trusted_template_extensions import CONTRACTS, inputs, check_input, PY, ORACLE


class Node:
    def __init__(self, val=0, next=None, left=None, right=None):
        self.val, self.next, self.left, self.right = val, next, left, right


def decoded(args):
    args = copy.deepcopy(args)
    if 'head' in args:
        head = None
        for value in reversed(args['head'] or []): head = Node(value, next=head)
        args['head'] = head
    if 'root' in args:
        raw = args['root'] or []
        root = Node(raw[0]) if raw and raw[0] is not None else None
        queue = [root] if root else []
        offset = 1
        for node in queue:
            for attr in ('left', 'right'):
                if offset < len(raw) and raw[offset] is not None:
                    child = Node(raw[offset]); setattr(node, attr, child); queue.append(child)
                offset += 1
        args['root'] = root
    return args


def invoke(body, args):
    namespace = {'ListNode': Node, 'TreeNode': Node}
    source = 'def solve(' + ','.join(args) + '):\n' + '\n'.join('    ' + line for line in body.splitlines())
    exec(source, namespace)
    return namespace['solve'](**decoded(args))


@pytest.mark.parametrize('key', list(CONTRACTS))
def test_independent_algorithms_agree_and_candidates_are_deterministic(key):
    for seed in range(25):
        values = inputs(key, seed)
        assert len(values) == 20 and values == inputs(key, seed)
        for args in values:
            check_input(key, args)
            assert invoke(PY[key], args) == invoke(ORACLE[key], args)


def test_known_boundary_results_and_earliest_window_ties():
    samples = [('string_unique', {'s': 'aabbc'}, 4),
               ('string_distinct', {'s': 'abba'}, 2),
               ('string_window', {'s': 'abxxab', 't': 'ab'}, 'ab'),
               ('dp_climb', {'n': 0}, 1),
               ('dp_coins', {'coins': [2], 'amount': 3}, -1),
               ('dp_edit', {'a': 'ab', 'b': 'ba'}, 2),
               ('list_reverse', {'head': []}, []),
               ('list_kth', {'head': [1, 2, 3], 'k': 2}, 2),
               ('list_groups', {'head': [1, 2, 3, 4, 5], 'k': 2}, [2, 1, 4, 3, 5]),
               ('tree_depth', {'root': []}, 0),
               ('tree_levels', {'root': [1, None, 2, 3]}, [1, 2, 3]),
               ('tree_path', {'root': [-3, -2, -8]}, -2)]
    for key, args, expected in samples:
        assert invoke(PY[key], args) == invoke(ORACLE[key], args) == expected


def test_invalid_contracts_and_unreachable_nodes_are_rejected():
    for key, args in [('string_unique', {'s': 'é'}), ('dp_climb', {'n': True}),
                      ('dp_coins', {'coins': [0], 'amount': 1}),
                      ('list_kth', {'head': [], 'k': 1}),
                      ('list_groups', {'head': [1], 'k': 0}),
                      ('tree_depth', {'root': [None, 1]})]:
        with pytest.raises(ValueError): check_input(key, args)
