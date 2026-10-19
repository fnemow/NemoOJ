#!/usr/bin/env python3
"""Generate seven additive NemoOJ catalog batches (problem numbers 25-164).

The catalog deliberately balances every tag that already exists in the first
24 production problems.  Every problem contains five independently checked
judge cases and supports both ACM and FUNCTION submission modes.
"""

from __future__ import annotations

import json
import math
from collections import Counter, deque
from functools import lru_cache
from pathlib import Path

from generate_algorithm_seed import acm_case, compact_json, function_case, spec, sql_string


SUPPORTED_TYPES = {
    "int", "long", "double", "boolean", "string", "int[]", "long[]",
    "double[]", "boolean[]", "string[]", "int[][]", "list<int>",
    "list_node<int>", "tree_node<int>",
}
DIFFICULTIES = ("简单", "中等", "困难")
BATCH_NAMES = (
    "linked_list", "binary_tree", "matrix_backtracking", "bit_math",
    "monotonic_stack", "sliding_window", "sorting_two_pointers",
)
PROBLEMS: list[dict] = []
BATCHES: dict[str, list[dict]] = {name: [] for name in BATCH_NAMES}


def encode_input_value(value, value_type: str) -> str:
    if value_type in {"int", "long", "double"}:
        return f"{value}\n"
    if value_type == "boolean":
        return ("true" if value else "false") + "\n"
    if value_type == "string":
        return str(value) + "\n"
    if value_type in {"int[]", "long[]", "double[]", "boolean[]", "list<int>", "list_node<int>"}:
        rendered = ["true" if item is True else "false" if item is False else str(item) for item in value]
        return f"{len(value)}\n" + " ".join(rendered) + "\n"
    if value_type == "string[]":
        return f"{len(value)}\n" + "\n".join(value) + "\n"
    if value_type == "int[][]":
        columns = len(value[0]) if value else 0
        return f"{len(value)} {columns}\n" + "".join(" ".join(map(str, row)) + "\n" for row in value)
    if value_type == "tree_node<int>":
        tokens = ["null" if item is None else str(item) for item in value]
        return f"{len(tokens)}\n" + " ".join(tokens) + "\n"
    raise AssertionError(f"unsupported input type: {value_type}")


def encode_output(value, value_type: str) -> str:
    if value_type == "boolean":
        return ("true" if value else "false") + "\n"
    if value_type in {"int", "long", "double", "string"}:
        return f"{value}\n"
    if value_type in {"int[]", "long[]", "double[]", "boolean[]", "string[]", "list<int>", "list_node<int>", "tree_node<int>"}:
        return " ".join("null" if item is None else "true" if item is True else "false" if item is False else str(item) for item in value) + "\n"
    raise AssertionError(f"unsupported output type: {value_type}")


def input_rule(name: str, value_type: str) -> str:
    if value_type in {"int", "long", "double", "boolean", "string"}:
        return f"- `{name}`（`{value_type}`）：单独一行。"
    if value_type in {"int[]", "long[]", "double[]", "boolean[]", "list<int>", "list_node<int>"}:
        return f"- `{name}`（`{value_type}`）：先输入长度，再在下一行输入所有元素。"
    if value_type == "string[]":
        return f"- `{name}`（`string[]`）：先输入字符串数量，随后每行一个字符串。"
    if value_type == "int[][]":
        return f"- `{name}`（`int[][]`）：先输入行数和列数，随后逐行输入矩阵。"
    if value_type == "tree_node<int>":
        return f"- `{name}`（`tree_node<int>`）：先输入层序序列长度，下一行输入节点；空节点写作 `null`。"
    raise AssertionError(value_type)


def output_rule(value_type: str) -> str:
    if value_type == "string[]":
        return "输出结果字符串序列，元素使用空格分隔；本题结果元素不含空格，空序列输出空行。"
    if value_type in {"int[]", "long[]", "double[]", "boolean[]", "list<int>", "list_node<int>", "tree_node<int>"}:
        return "输出结果序列，元素使用空格分隔；空树或空序列输出空行。"
    if value_type == "boolean":
        return "输出 `true` 或 `false`。"
    return "输出计算结果。"


def add_problem(batch: str, title: str, tags: list[str], method: str,
                parameters: list[tuple[str, str]], return_type: str,
                summary: str, idea: str, solver, cases: list[dict],
                constraints: str) -> None:
    assert batch in BATCHES
    assert len(cases) == 5
    difficulty = DIFFICULTIES[len(PROBLEMS) % len(DIFFICULTIES)]
    tag_list = list(dict.fromkeys([difficulty, *tags]))
    signature = ", ".join(f"{name}: {value_type}" for name, value_type in parameters)
    input_rules = "\n".join(input_rule(name, value_type) for name, value_type in parameters)
    content = f"""## 题目描述

{summary}

## ACM 模式输入

按下列参数顺序输入：

{input_rules}

## ACM 模式输出

{output_rule(return_type)}

## 核心代码模式

实现 `Solution.{method}({signature}) -> {return_type}`。

## 数据范围

{constraints}
"""
    acm_cases, function_cases = [], []
    for index, arguments in enumerate(cases):
        expected = solver(*(arguments[name] for name, _ in parameters))
        stdin = "".join(encode_input_value(arguments[name], value_type) for name, value_type in parameters)
        acm_cases.append(acm_case(stdin, encode_output(expected, return_type), index >= 2))
        function_cases.append(function_case(arguments, expected, index >= 2))
    problem = {
        "title": title, "tags": tag_list, "content": content, "answer": idea,
        "acm": acm_cases, "spec": spec(method, parameters, return_type, function_cases),
        "solver": solver, "batch": batch,
    }
    PROBLEMS.append(problem)
    BATCHES[batch].append(problem)


def tree_from_level(values):
    if not values or values[0] is None:
        return None
    root = [values[0], None, None]
    queue = deque([root])
    index = 1
    while queue and index < len(values):
        node = queue.popleft()
        for child_index in (1, 2):
            if index >= len(values):
                break
            value = values[index]
            index += 1
            if value is not None:
                node[child_index] = [value, None, None]
                queue.append(node[child_index])
    return root


def tree_to_level(root):
    if root is None:
        return []
    values, queue = [], deque([root])
    while queue:
        node = queue.popleft()
        if node is None:
            values.append(None)
        else:
            values.append(node[0])
            queue.extend((node[1], node[2]))
    while values and values[-1] is None:
        values.pop()
    return values


# Batch 1: linked-list problems.  Lists are serialized as value arrays at the API boundary.
def remove_nth(values, n):
    result = list(values); result.pop(len(result) - n); return result


def rotate_right(values, k):
    if not values: return []
    k %= len(values)
    return list(values[-k:] + values[:-k]) if k else list(values)


def swap_pairs(values):
    result = list(values)
    for index in range(0, len(result) - 1, 2): result[index], result[index + 1] = result[index + 1], result[index]
    return result


def reverse_between(values, left, right):
    result = list(values); result[left - 1:right] = reversed(result[left - 1:right]); return result


def reorder_list(values):
    result, left, right = [], 0, len(values) - 1
    while left <= right:
        result.append(values[left]); left += 1
        if left <= right: result.append(values[right]); right -= 1
    return result


def add_numbers(first, second):
    result, carry, index = [], 0, 0
    while index < len(first) or index < len(second) or carry:
        total = carry + (first[index] if index < len(first) else 0) + (second[index] if index < len(second) else 0)
        result.append(total % 10); carry = total // 10; index += 1
    return result


def component_count(values, selected):
    selected = set(selected); total = 0; active = False
    for value in values:
        if value in selected and not active: total += 1
        active = value in selected
    return total


def next_greater_nodes(values):
    answer, stack = [0] * len(values), []
    for index, value in enumerate(values):
        while stack and values[stack[-1]] < value: answer[stack.pop()] = value
        stack.append(index)
    return answer


def reverse_even_groups(values):
    result = list(values); start = 0; group = 1
    while start < len(result):
        size = min(group, len(result) - start)
        if size % 2 == 0: result[start:start + size] = reversed(result[start:start + size])
        start += size; group += 1
    return result


def double_forward_number(values):
    number = int("".join(map(str, values))) * 2
    return list(map(int, str(number)))


LL = "- `1 ≤ n ≤ 10^5`\n- 节点值在 32 位有符号整数范围内\n- 测试数据保证参数位置合法"
add_problem("linked_list", "链表中点的值", ["链表", "双指针", "数组"], "middleValue", [("head", "list_node<int>")], "int", "给定一个非空单链表。链表长度为偶数时取两个中间节点中的后一个，返回该节点的值。", "使用快慢指针；快指针每次走两步，慢指针每次走一步。", lambda head: head[len(head)//2], [{"head":[1,2,3,4,5]},{"head":[1,2,3,4]},{"head":[7]},{"head":[-2,8]},{"head":[9,8,7,6,5,4]}], LL)
add_problem("linked_list", "链表倒数第 K 个节点", ["链表", "双指针"], "kthFromEnd", [("head","list_node<int>"),("k","int")], "int", "返回单链表倒数第 `k` 个节点的值。", "让前指针先走 k 步，再同步移动前后指针。", lambda head,k: head[-k], [{"head":[1,2,3,4,5],"k":2},{"head":[4,9],"k":1},{"head":[7],"k":1},{"head":[-3,0,6],"k":3},{"head":[8,6,4,2],"k":2}], LL)
add_problem("linked_list", "回文链表判定", ["链表", "双指针", "栈"], "isPalindromeList", [("head","list_node<int>")], "boolean", "判断链表从前向后和从后向前读取时是否完全相同。", "找到中点并反转后半部分，随后逐项比较两半。", lambda head: head == list(reversed(head)), [{"head":[1,2,2,1]},{"head":[1,2]},{"head":[5]},{"head":[1,2,3,2,1]},{"head":[0,0,1,0]}], LL)
add_problem("linked_list", "删除链表倒数第 N 个节点", ["链表", "双指针"], "removeNthFromEnd", [("head","list_node<int>"),("n","int")], "list_node<int>", "删除单链表倒数第 `n` 个节点并返回新链表。", "使用哑节点和间隔为 n 的快慢指针定位待删除节点的前驱。", remove_nth, [{"head":[1,2,3,4,5],"n":2},{"head":[1],"n":1},{"head":[1,2],"n":1},{"head":[1,2],"n":2},{"head":[4,3,2,1],"n":3}], LL)
add_problem("linked_list", "链表向右轮转", ["链表", "双指针", "数学"], "rotateRight", [("head","list_node<int>"),("k","int")], "list_node<int>", "将链表整体向右轮转 `k` 次。", "先计算长度并对 k 取模，把链表连成环后在新尾部断开。", rotate_right, [{"head":[1,2,3,4,5],"k":2},{"head":[0,1,2],"k":4},{"head":[1],"k":9},{"head":[1,2],"k":2},{"head":[3,8,9],"k":1}], LL)
add_problem("linked_list", "两两交换链表节点", ["链表", "递归", "双指针"], "swapPairs", [("head","list_node<int>")], "list_node<int>", "每两个相邻节点为一组交换位置；末尾单独节点保持不变。", "保存每组的两个节点与后继，调整连接关系后继续处理下一组。", swap_pairs, [{"head":[1,2,3,4]},{"head":[1]},{"head":[1,2,3]},{"head":[-1,0]},{"head":[5,4,3,2,1]}], LL)
add_problem("linked_list", "奇偶位置链表重排", ["链表", "双指针"], "oddEvenList", [("head","list_node<int>")], "list_node<int>", "先连接所有奇数位置节点，再连接所有偶数位置节点，位置从 1 开始。", "分别维护奇数链和偶数链的尾指针，最后拼接。", lambda head: list(head[::2] + head[1::2]), [{"head":[1,2,3,4,5]},{"head":[2,1,3,5,6,4,7]},{"head":[1]},{"head":[1,2]},{"head":[9,8,7,6]}], LL)
add_problem("linked_list", "按阈值分隔链表", ["链表", "双指针", "排序"], "partitionList", [("head","list_node<int>"),("x","int")], "list_node<int>", "保持相对顺序，将所有小于 `x` 的节点放到其他节点之前。", "构造小于 x 与不小于 x 两条链，遍历结束后连接。", lambda head,x: [v for v in head if v < x] + [v for v in head if v >= x], [{"head":[1,4,3,2,5,2],"x":3},{"head":[2,1],"x":2},{"head":[1,1],"x":0},{"head":[5,4,3],"x":6},{"head":[3,1,2,3],"x":3}], LL)
add_problem("linked_list", "删除有序链表重复值", ["链表", "哈希表", "排序"], "deleteDuplicates", [("head","list_node<int>")], "list_node<int>", "给定非递减链表，每个数只保留第一次出现的节点。", "比较当前节点与结果链尾部的值，跳过连续重复节点。", lambda head: list(dict.fromkeys(head)), [{"head":[1,1,2]},{"head":[1,1,2,3,3]},{"head":[0]},{"head":[-2,-2,-2]},{"head":[1,2,3,4]}], LL)
add_problem("linked_list", "移除链表指定值", ["链表", "递归"], "removeElements", [("head","list_node<int>"),("value","int")], "list_node<int>", "删除链表中所有值等于 `value` 的节点。", "使用哑节点统一处理头节点删除，逐个跳过命中节点。", lambda head,value: [v for v in head if v != value], [{"head":[1,2,6,3,4,5,6],"value":6},{"head":[7,7,7],"value":7},{"head":[1],"value":2},{"head":[1,2,1],"value":1},{"head":[-1,0,-1],"value":-1}], LL)
add_problem("linked_list", "反转链表指定区间", ["链表", "递归", "双指针"], "reverseBetween", [("head","list_node<int>"),("left","int"),("right","int")], "list_node<int>", "反转链表中从 `left` 到 `right` 的连续区间，位置从 1 开始。", "定位区间前驱后使用头插法完成局部反转。", reverse_between, [{"head":[1,2,3,4,5],"left":2,"right":4},{"head":[5],"left":1,"right":1},{"head":[1,2],"left":1,"right":2},{"head":[3,4,5],"left":2,"right":3},{"head":[1,2,3,4],"left":1,"right":3}], LL)
add_problem("linked_list", "首尾交替重排链表", ["链表", "双指针", "栈"], "reorderList", [("head","list_node<int>")], "list_node<int>", "将链表重排为首节点、尾节点、第二个节点、倒数第二个节点的交替顺序。", "找到中点，反转后半链表，再交替合并两部分。", reorder_list, [{"head":[1,2,3,4]},{"head":[1,2,3,4,5]},{"head":[1]},{"head":[1,2]},{"head":[9,8,7,6,5,4]}], LL)
add_problem("linked_list", "链表归并排序", ["链表", "排序", "递归", "双指针"], "sortLinkedList", [("head","list_node<int>")], "list_node<int>", "将链表按非递减顺序排序。", "快慢指针分割链表，递归排序两半并线性归并。", lambda head: sorted(head), [{"head":[4,2,1,3]},{"head":[-1,5,3,4,0]},{"head":[1]},{"head":[2,2,1]},{"head":[9,7,5,3,1]}], LL)
add_problem("linked_list", "链表表示的两数相加", ["链表", "数学", "递归"], "addTwoNumbers", [("first","list_node<int>"),("second","list_node<int>")], "list_node<int>", "两个非负整数以逆序数字链表表示，返回它们的和，结果也使用逆序数字链表。", "同步遍历两条链表，将对应位与进位相加。", add_numbers, [{"first":[2,4,3],"second":[5,6,4]},{"first":[0],"second":[0]},{"first":[9,9,9],"second":[1]},{"first":[1,8],"second":[0]},{"first":[5,5],"second":[5,5]}], LL)
add_problem("linked_list", "合并多条有序链表", ["链表", "排序", "二分查找"], "mergeKLists", [("lists","int[][]")], "list_node<int>", "给定若干条非递减链表，合并为一条非递减链表。矩阵的每一行表示一条链表。", "使用优先队列保存每条链表的当前最小节点，或分治归并。", lambda lists: sorted(v for row in lists for v in row), [{"lists":[[1,4,5],[1,3,4],[2,6]]},{"lists":[[],[1]]},{"lists":[[1],[0]]},{"lists":[[-3,-1],[-2,4]]},{"lists":[[1,2],[3,4],[5,6]]}], LL)
add_problem("linked_list", "链表最大孪生和", ["链表", "双指针", "数学"], "pairSum", [("head","list_node<int>")], "int", "偶数长度链表中，第 i 个节点与倒数第 i 个节点组成孪生对，返回最大的孪生和。", "找到中点并反转后半部分，同时遍历两半计算最大和。", lambda head: max(head[i] + head[-1-i] for i in range(len(head)//2)), [{"head":[5,4,2,1]},{"head":[4,2,2,3]},{"head":[1,100000]},{"head":[-2,3,4,-1]},{"head":[1,2,3,4,5,6]}], LL)
add_problem("linked_list", "链表选定节点连通分量", ["链表", "哈希表", "数组"], "numComponents", [("head","list_node<int>"),("selected","int[]")], "int", "给定链表和一个值集合，统计由集合中节点组成的连续片段数量。", "把选定值放入哈希集合，扫描链表并统计每个片段的起点。", component_count, [{"head":[0,1,2,3],"selected":[0,1,3]},{"head":[0,1,2,3,4],"selected":[0,3,1,4]},{"head":[1],"selected":[1]},{"head":[1,2,3],"selected":[2]},{"head":[5,6,7,8],"selected":[5,6,7,8]}], LL)
add_problem("linked_list", "链表中的下一个更大节点", ["链表", "单调栈", "栈", "数组"], "nextLargerNodes", [("head","list_node<int>")], "int[]", "对链表中每个节点，返回它右侧第一个严格更大节点的值；不存在时返回 0。", "按顺序扫描节点值，用单调递减栈保存尚未找到答案的位置。", next_greater_nodes, [{"head":[2,1,5]},{"head":[2,7,4,3,5]},{"head":[1,7,5,1,9,2,5,1]},{"head":[4]},{"head":[3,3,3]}], LL)
add_problem("linked_list", "按偶数长度分组反转链表", ["链表", "递归", "数学"], "reverseEvenLengthGroups", [("head","list_node<int>")], "list_node<int>", "依次取长度为 1、2、3……的节点组，最后一组可不足；仅反转实际长度为偶数的组。", "逐组确定实际长度，偶数组原地反转并重新连接边界。", reverse_even_groups, [{"head":[5,2,6,3,9,1,7,3,8,4]},{"head":[1,1,0,6]},{"head":[1,2,3,4,5]},{"head":[1]},{"head":[1,2,3,4,5,6]}], LL)
add_problem("linked_list", "链表数字翻倍", ["链表", "数学", "栈"], "doubleLinkedNumber", [("head","list_node<int>")], "list_node<int>", "链表按正序保存一个不含前导零的非负整数，请返回该整数乘以 2 后的正序数字链表。", "从链表尾部向前处理每一位并维护进位，可用栈或先反转链表。", double_forward_number, [{"head":[1,8,9]},{"head":[9,9,9]},{"head":[0]},{"head":[5]},{"head":[4,0,5]}], LL)


# Batch 2: binary-search-tree problems.
def bst_insert(root, value):
    if root is None: return [value, None, None]
    if value < root[0]: root[1] = bst_insert(root[1], value)
    elif value > root[0]: root[2] = bst_insert(root[2], value)
    return root


def bst_delete(root, value):
    if root is None: return None
    if value < root[0]: root[1] = bst_delete(root[1], value)
    elif value > root[0]: root[2] = bst_delete(root[2], value)
    elif root[1] is None: return root[2]
    elif root[2] is None: return root[1]
    else:
        successor = root[2]
        while successor[1] is not None: successor = successor[1]
        root[0] = successor[0]; root[2] = bst_delete(root[2], successor[0])
    return root


def inorder(root):
    return [] if root is None else inorder(root[1]) + [root[0]] + inorder(root[2])


def preorder(root):
    return [] if root is None else [root[0]] + preorder(root[1]) + preorder(root[2])


def postorder(root):
    return [] if root is None else postorder(root[1]) + postorder(root[2]) + [root[0]]


def tree_height(root):
    return 0 if root is None else 1 + max(tree_height(root[1]), tree_height(root[2]))


def bst_lca(root, first, second):
    low, high = sorted((first, second))
    while root:
        if root[0] < low: root = root[2]
        elif root[0] > high: root = root[1]
        else: return root[0]
    return -1


def balanced_bst(values):
    if not values: return None
    middle = len(values)//2
    return [values[middle], balanced_bst(values[:middle]), balanced_bst(values[middle+1:])]


BST_CASES = [[4,2,7,1,3,6,9],[5,3,8,2,4,7,9],[2,1,3],[1],[6,2,8,0,4,7,9,None,None,3,5]]
TREE = "- `1 ≤ 节点数 ≤ 10^5`\n- 输入树满足二叉搜索树性质\n- 节点值互不相同且在 32 位有符号整数范围内"
tree_tags = ["二叉树", "二分查找", "深度优先搜索", "广度优先搜索", "递归"]
add_problem("binary_tree", "二叉搜索树查找节点", tree_tags, "searchBST", [("root","tree_node<int>"),("target","int")], "boolean", "判断目标值是否存在于二叉搜索树中。", "根据目标值与当前节点值的大小关系，只进入一侧子树。", lambda root,target: target in set(v for v in root if v is not None), [{"root":BST_CASES[0],"target":6},{"root":BST_CASES[1],"target":1},{"root":BST_CASES[2],"target":3},{"root":BST_CASES[3],"target":1},{"root":BST_CASES[4],"target":5}], TREE)
add_problem("binary_tree", "二叉搜索树插入节点", tree_tags, "insertIntoBST", [("root","tree_node<int>"),("value","int")], "tree_node<int>", "向二叉搜索树插入一个不存在的新值并返回根节点。", "递归寻找空位置插入，新值只可能进入一条搜索路径。", lambda root,value: tree_to_level(bst_insert(tree_from_level(root),value)), [{"root":BST_CASES[0],"value":5},{"root":BST_CASES[1],"value":6},{"root":BST_CASES[2],"value":4},{"root":BST_CASES[3],"value":0},{"root":BST_CASES[4],"value":1}], TREE)
add_problem("binary_tree", "二叉搜索树删除节点", tree_tags, "deleteNode", [("root","tree_node<int>"),("value","int")], "tree_node<int>", "删除二叉搜索树中等于给定值的节点并保持搜索树性质。", "搜索节点后分三种子树情况处理；双子树时用中序后继替换。", lambda root,value: tree_to_level(bst_delete(tree_from_level(root),value)), [{"root":BST_CASES[0],"value":2},{"root":BST_CASES[1],"value":8},{"root":BST_CASES[2],"value":2},{"root":BST_CASES[3],"value":1},{"root":BST_CASES[4],"value":4}], TREE)
add_problem("binary_tree", "二叉搜索树第 K 小元素", tree_tags + ["栈"], "kthSmallestBST", [("root","tree_node<int>"),("k","int")], "int", "返回二叉搜索树中第 `k` 小的节点值。", "中序遍历二叉搜索树可得到严格递增序列。", lambda root,k: inorder(tree_from_level(root))[k-1], [{"root":BST_CASES[0],"k":3},{"root":BST_CASES[1],"k":5},{"root":BST_CASES[2],"k":1},{"root":BST_CASES[3],"k":1},{"root":BST_CASES[4],"k":6}], TREE)
add_problem("binary_tree", "二叉搜索树第 K 大元素", tree_tags + ["栈"], "kthLargestBST", [("root","tree_node<int>"),("k","int")], "int", "返回二叉搜索树中第 `k` 大的节点值。", "执行逆中序遍历，在访问第 k 个节点时结束。", lambda root,k: inorder(tree_from_level(root))[-k], [{"root":BST_CASES[0],"k":2},{"root":BST_CASES[1],"k":4},{"root":BST_CASES[2],"k":3},{"root":BST_CASES[3],"k":1},{"root":BST_CASES[4],"k":5}], TREE)
add_problem("binary_tree", "二叉搜索树区间和", tree_tags + ["数学"], "rangeSumBST", [("root","tree_node<int>"),("low","int"),("high","int")], "long", "计算值位于闭区间 `[low, high]` 内的所有节点之和。", "利用搜索树有序性剪去完全落在区间外的子树。", lambda root,low,high: sum(v for v in inorder(tree_from_level(root)) if low <= v <= high), [{"root":BST_CASES[0],"low":2,"high":7},{"root":BST_CASES[1],"low":4,"high":8},{"root":BST_CASES[2],"low":1,"high":1},{"root":BST_CASES[3],"low":0,"high":2},{"root":BST_CASES[4],"low":3,"high":7}], TREE)
add_problem("binary_tree", "二叉搜索树最小差值", tree_tags + ["双指针", "数学"], "minDiffInBST", [("root","tree_node<int>")], "int", "返回二叉搜索树中任意两个不同节点值的最小绝对差。", "中序遍历后最小差一定出现在相邻元素之间。", lambda root: min(b-a for a,b in zip(inorder(tree_from_level(root)),inorder(tree_from_level(root))[1:])), [{"root":BST_CASES[0]},{"root":BST_CASES[1]},{"root":BST_CASES[2]},{"root":[2,1]},{"root":BST_CASES[4]}], TREE)
add_problem("binary_tree", "二叉搜索树向下取整", tree_tags, "floorInBST", [("root","tree_node<int>"),("target","int")], "int", "返回树中不大于 `target` 的最大值；不存在时返回 `-1`。", "沿搜索路径维护最后一个不大于目标的候选值。", lambda root,target: max((v for v in inorder(tree_from_level(root)) if v <= target),default=-1), [{"root":BST_CASES[0],"target":5},{"root":BST_CASES[1],"target":1},{"root":BST_CASES[2],"target":9},{"root":BST_CASES[3],"target":0},{"root":BST_CASES[4],"target":6}], TREE)
add_problem("binary_tree", "二叉搜索树向上取整", tree_tags, "ceilInBST", [("root","tree_node<int>"),("target","int")], "int", "返回树中不小于 `target` 的最小值；不存在时返回 `-1`。", "沿搜索路径维护最后一个不小于目标的候选值。", lambda root,target: min((v for v in inorder(tree_from_level(root)) if v >= target),default=-1), [{"root":BST_CASES[0],"target":5},{"root":BST_CASES[1],"target":10},{"root":BST_CASES[2],"target":0},{"root":BST_CASES[3],"target":1},{"root":BST_CASES[4],"target":6}], TREE)
add_problem("binary_tree", "二叉搜索树最近公共祖先", tree_tags, "lowestCommonAncestorBST", [("root","tree_node<int>"),("first","int"),("second","int")], "int", "给定树中两个节点值，返回它们最近公共祖先的值。", "若两值都小于当前节点则进入左子树，都大于则进入右子树，否则当前节点即答案。", lambda root,first,second: bst_lca(tree_from_level(root),first,second), [{"root":BST_CASES[0],"first":1,"second":3},{"root":BST_CASES[1],"first":2,"second":9},{"root":BST_CASES[2],"first":1,"second":3},{"root":BST_CASES[0],"first":6,"second":9},{"root":BST_CASES[4],"first":3,"second":5}], TREE)
add_problem("binary_tree", "验证二叉搜索树边界", tree_tags, "validateBST", [("root","tree_node<int>")], "boolean", "判断给定二叉树是否满足严格二叉搜索树性质。", "递归传递每个节点允许的开区间上下界。", lambda root: inorder(tree_from_level(root)) == sorted(set(inorder(tree_from_level(root)))), [{"root":BST_CASES[0]},{"root":[5,1,4,None,None,3,6]},{"root":BST_CASES[2]},{"root":[2,2,3]},{"root":BST_CASES[4]}], TREE)
add_problem("binary_tree", "有序数组构造平衡搜索树", tree_tags + ["数组"], "sortedArrayToBST", [("nums","int[]")], "tree_node<int>", "把严格递增数组转换为一棵高度平衡的二叉搜索树。", "每次选择区间中点作为根节点，递归构造左右子树。", lambda nums: tree_to_level(balanced_bst(nums)), [{"nums":[-10,-3,0,5,9]},{"nums":[1,3]},{"nums":[1]},{"nums":[1,2,3,4]},{"nums":[-2,-1,0,1,2,3]}], TREE)
add_problem("binary_tree", "统计搜索树节点数量", tree_tags + ["数学"], "countBSTNodes", [("root","tree_node<int>")], "int", "返回二叉搜索树中的节点总数。", "递归统计左右子树节点数并加一，也可使用层序遍历。", lambda root: len(inorder(tree_from_level(root))), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树所有节点之和", tree_tags + ["数学"], "sumBSTNodes", [("root","tree_node<int>")], "long", "计算二叉搜索树中所有节点值之和。", "遍历每个节点并累加，任一种深度或广度优先遍历均可。", lambda root: sum(inorder(tree_from_level(root))), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树高度", tree_tags + ["动态规划"], "heightOfBST", [("root","tree_node<int>")], "int", "返回二叉搜索树的节点高度，只有根节点时高度为 1。", "树高等于左右子树高度最大值加一。", lambda root: tree_height(tree_from_level(root)), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树叶子数量", tree_tags, "countBSTLeaves", [("root","tree_node<int>")], "int", "统计二叉搜索树中没有子节点的叶子节点数量。", "遍历树；左右孩子都为空时贡献一个叶子。", lambda root: (lambda f:f(f,tree_from_level(root)))(lambda self,node:0 if node is None else 1 if node[1] is None and node[2] is None else self(self,node[1])+self(self,node[2])), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树先序遍历", tree_tags + ["栈"], "preorderBST", [("root","tree_node<int>")], "int[]", "返回二叉搜索树的先序遍历序列。", "按根、左、右顺序递归访问，或使用显式栈。", lambda root: preorder(tree_from_level(root)), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树后序遍历", tree_tags + ["栈"], "postorderBST", [("root","tree_node<int>")], "int[]", "返回二叉搜索树的后序遍历序列。", "按左、右、根顺序递归访问，或使用两个栈。", lambda root: postorder(tree_from_level(root)), [{"root":tree} for tree in BST_CASES], TREE)
add_problem("binary_tree", "搜索树右侧视图", tree_tags + ["栈"], "rightSideViewBST", [("root","tree_node<int>")], "int[]", "从树的右侧观察，返回每一层最右节点的值。", "层序遍历每层记录最后一个节点，或优先访问右子树。", lambda root: (lambda node:([] if node is None else (lambda q,out:out)(deque([node]),[])))(tree_from_level(root)), [{"root":tree} for tree in BST_CASES], TREE)


def right_view(values):
    root = tree_from_level(values)
    if root is None: return []
    queue, answer = deque([root]), []
    while queue:
        for index in range(len(queue)):
            node = queue.popleft()
            if index == 0: answer.append(node[0])
            if node[2] is not None: queue.append(node[2])
            if node[1] is not None: queue.append(node[1])
    return answer


# Replace the compact placeholder solver above before validation.
PROBLEMS[-1]["solver"] = right_view
for index, arguments in enumerate([{"root":tree} for tree in BST_CASES]):
    expected = right_view(arguments["root"])
    PROBLEMS[-1]["acm"][index] = acm_case(encode_input_value(arguments["root"], "tree_node<int>"), encode_output(expected, "int[]"), index >= 2)
    PROBLEMS[-1]["spec"]["judgeCases"][index] = function_case(arguments, expected, index >= 2)


def level_order_flat(values):
    root = tree_from_level(values)
    if root is None: return []
    queue, answer = deque([root]), []
    while queue:
        node = queue.popleft(); answer.append(node[0])
        if node[1] is not None: queue.append(node[1])
        if node[2] is not None: queue.append(node[2])
    return answer


add_problem("binary_tree", "搜索树层序展开", tree_tags, "levelOrderBST", [("root","tree_node<int>")], "int[]", "按从上到下、每层从左到右的顺序返回所有节点值。", "使用队列进行广度优先遍历。", level_order_flat, [{"root":tree} for tree in BST_CASES], TREE)


# Batch 3: matrix search, backtracking, DFS and memoized recursion.
DIR4 = ((1,0),(-1,0),(0,1),(0,-1))


def word_paths(board, word, stop_after_one=False):
    rows, columns = len(board), len(board[0]); total = 0
    def search(row, column, index, used):
        nonlocal total
        if board[row][column] != word[index]: return False
        if index + 1 == len(word): total += 1; return True
        used.add((row,column)); found = False
        for dr,dc in DIR4:
            nr,nc=row+dr,column+dc
            if 0<=nr<rows and 0<=nc<columns and (nr,nc) not in used:
                if search(nr,nc,index+1,used):
                    found = True
                    if stop_after_one: break
        used.remove((row,column)); return found
    for row in range(rows):
        for column in range(columns):
            if search(row,column,0,set()) and stop_after_one: return 1
    return total


def unique_paths_obstacles(grid):
    dp=[0]*len(grid[0]);dp[0]=1
    for row in grid:
        for column,value in enumerate(row):
            dp[column]=0 if value else dp[column]+(dp[column-1] if column else 0)
    return dp[-1]


def simple_path_count(grid):
    rows,columns=len(grid),len(grid[0]);used={(0,0)}
    def dfs(row,column):
        if (row,column)==(rows-1,columns-1): return 1
        total=0
        for dr,dc in DIR4:
            nr,nc=row+dr,column+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==0 and (nr,nc) not in used:
                used.add((nr,nc));total+=dfs(nr,nc);used.remove((nr,nc))
        return total
    return 0 if grid[0][0] or grid[-1][-1] else dfs(0,0)


def longest_increasing_path(matrix):
    rows,columns=len(matrix),len(matrix[0])
    @lru_cache(None)
    def dfs(row,column):
        return 1+max((dfs(nr,nc) for dr,dc in DIR4 if 0<=(nr:=row+dr)<rows and 0<=(nc:=column+dc)<columns and matrix[nr][nc]>matrix[row][column]),default=0)
    return max(dfs(r,c) for r in range(rows) for c in range(columns))


def island_areas(grid):
    rows,columns=len(grid),len(grid[0]);seen=set();areas=[]
    for row in range(rows):
        for column in range(columns):
            if grid[row][column]!=1 or (row,column) in seen:continue
            area=0;stack=[(row,column)];seen.add((row,column))
            while stack:
                r,c=stack.pop();area+=1
                for dr,dc in DIR4:
                    nr,nc=r+dr,c+dc
                    if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==1 and (nr,nc) not in seen:seen.add((nr,nc));stack.append((nr,nc))
            areas.append(area)
    return areas


def closed_islands(grid):
    rows,columns=len(grid),len(grid[0]);seen=set();answer=0
    for row in range(rows):
        for column in range(columns):
            if grid[row][column]!=0 or (row,column) in seen:continue
            closed=True;stack=[(row,column)];seen.add((row,column))
            while stack:
                r,c=stack.pop()
                if r in (0,rows-1) or c in (0,columns-1):closed=False
                for dr,dc in DIR4:
                    nr,nc=r+dr,c+dc
                    if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==0 and (nr,nc) not in seen:seen.add((nr,nc));stack.append((nr,nc))
            answer+=closed
    return answer


def island_perimeter(grid):
    answer=0
    for r,row in enumerate(grid):
        for c,value in enumerate(row):
            if value:
                answer+=4
                if r and grid[r-1][c]:answer-=2
                if c and grid[r][c-1]:answer-=2
    return answer


def enclave_count(grid):
    rows,columns=len(grid),len(grid[0]);seen=set();stack=[]
    for r in range(rows):
        for c in range(columns):
            if (r in (0,rows-1) or c in (0,columns-1)) and grid[r][c]==1 and (r,c) not in seen:seen.add((r,c));stack.append((r,c))
    while stack:
        r,c=stack.pop()
        for dr,dc in DIR4:
            nr,nc=r+dr,c+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==1 and (nr,nc) not in seen:seen.add((nr,nc));stack.append((nr,nc))
    return sum(value==1 and (r,c) not in seen for r,row in enumerate(grid) for c,value in enumerate(row))


def shortest_binary_path(grid):
    rows,columns=len(grid),len(grid[0])
    if grid[0][0] or grid[-1][-1]:return -1
    queue=deque([(0,0,1)]);seen={(0,0)}
    while queue:
        r,c,d=queue.popleft()
        if (r,c)==(rows-1,columns-1):return d
        for dr,dc in DIR4:
            nr,nc=r+dr,c+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==0 and (nr,nc) not in seen:seen.add((nr,nc));queue.append((nr,nc,d+1))
    return -1


def exact_step_paths(grid, steps):
    rows,columns=len(grid),len(grid[0]);used={(0,0)}
    def dfs(r,c,left):
        if left==0:return int((r,c)==(rows-1,columns-1))
        total=0
        for dr,dc in DIR4:
            nr,nc=r+dr,c+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==0 and (nr,nc) not in used:used.add((nr,nc));total+=dfs(nr,nc,left-1);used.remove((nr,nc))
        return total
    return 0 if grid[0][0] else dfs(0,0,steps)


def max_gold(grid):
    rows,columns=len(grid),len(grid[0]);used=set()
    def dfs(r,c):
        used.add((r,c));best=0
        for dr,dc in DIR4:
            nr,nc=r+dr,c+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]>0 and (nr,nc) not in used:best=max(best,dfs(nr,nc))
        used.remove((r,c));return grid[r][c]+best
    return max((dfs(r,c) for r in range(rows) for c in range(columns) if grid[r][c]>0),default=0)


def diagonal_paths(rows,columns):
    return math.comb(rows+columns-2,rows-1)


def largest_square(grid):
    dp=[0]*(len(grid[0])+1);best=0
    for row in grid:
        previous=0
        for c,value in enumerate(row,1):
            above=dp[c]
            dp[c]=1+min(dp[c],dp[c-1],previous) if value else 0
            best=max(best,dp[c]);previous=above
    return best*best


def min_path_sum(grid):
    dp=[10**18]*len(grid[0]);dp[0]=0
    for row in grid:
        for c,value in enumerate(row):dp[c]=value+min(dp[c],dp[c-1] if c else 10**18)
    return dp[-1]


def monotone_target_paths(grid,target):
    rows,columns=len(grid),len(grid[0])
    @lru_cache(None)
    def dfs(r,c,total):
        total+=grid[r][c]
        if total>target:return 0
        if (r,c)==(rows-1,columns-1):return int(total==target)
        return (dfs(r+1,c,total) if r+1<rows else 0)+(dfs(r,c+1,total) if c+1<columns else 0)
    return dfs(0,0,0)


def n_queens(n):
    mask=(1<<n)-1
    def dfs(cols,left,right):
        if cols==mask:return 1
        available=mask&~(cols|left|right);total=0
        while available:
            bit=available&-available;available-=bit
            total+=dfs(cols|bit,(left|bit)<<1&mask,(right|bit)>>1)
        return total
    return dfs(0,0,0)


def rook_placements(board,k):
    rows,columns=len(board),len(board[0]);used=set()
    def dfs(row,left):
        if left==0:return 1
        if row==rows:return 0
        total=dfs(row+1,left)
        for c in range(columns):
            if board[row][c]==1 and c not in used:used.add(c);total+=dfs(row+1,left-1);used.remove(c)
        return total
    return dfs(0,k)


def color_grid(rows,columns,colors):
    board=[[-1]*columns for _ in range(rows)]
    def dfs(index):
        if index==rows*columns:return 1
        r,c=divmod(index,columns);total=0
        for color in range(colors):
            if r and board[r-1][c]==color:continue
            if c and board[r][c-1]==color:continue
            board[r][c]=color;total+=dfs(index+1)
        board[r][c]=-1;return total
    return dfs(0)


def hamiltonian_paths(grid):
    rows,columns=len(grid),len(grid[0]);walkable=sum(v==0 for row in grid for v in row);used={(0,0)}
    def dfs(r,c):
        if (r,c)==(rows-1,columns-1):return int(len(used)==walkable)
        total=0
        for dr,dc in DIR4:
            nr,nc=r+dr,c+dc
            if 0<=nr<rows and 0<=nc<columns and grid[nr][nc]==0 and (nr,nc) not in used:used.add((nr,nc));total+=dfs(nr,nc);used.remove((nr,nc))
        return total
    return 0 if grid[0][0] or grid[-1][-1] else dfs(0,0)


def count_present_words(board,words):
    return sum(bool(word_paths(board,word,True)) for word in words)


MATRIX_CASES=[[[0,0,0],[0,1,0],[0,0,0]],[[0,1],[0,0]],[[0]],[[0,0],[0,0]],[[0,0,1],[0,0,0],[1,0,0]]]
GRID = "- `1 ≤ rows, columns ≤ 8`\n- 回溯类用例规模受控，答案在 32 位有符号整数范围内\n- 矩阵各行长度相同"
matrix_tags=["矩阵","回溯","深度优先搜索","递归","动态规划"]
add_problem("matrix_backtracking","矩阵单词搜索",matrix_tags+["字符串"],"existWord",[("board","string[]"),("word","string")],"boolean","在字符矩阵中从任意格开始，上下左右移动且同一格不能重复，判断能否拼出目标单词。","从每个匹配首字符的位置开始深度优先搜索，并在回溯时撤销访问标记。",lambda board,word:bool(word_paths(board,word,True)),[{"board":["ABCE","SFCS","ADEE"],"word":"ABCCED"},{"board":["ABCE","SFCS","ADEE"],"word":"ABCB"},{"board":["A"],"word":"A"},{"board":["CAT","DOG","MAP"],"word":"COD"},{"board":["AAA","ABA","AAA"],"word":"ABAA"}],GRID)
add_problem("matrix_backtracking","矩阵单词路径数量",matrix_tags+["字符串"],"countWordPaths",[("board","string[]"),("word","string")],"int","统计字符矩阵中能够拼出目标单词的不同简单路径数量。","枚举起点后进行回溯；路径中的格子不可重复使用。",lambda board,word:word_paths(board,word),[{"board":["AA","AA"],"word":"AA"},{"board":["AB","BA"],"word":"ABA"},{"board":["A"],"word":"B"},{"board":["ABC","DEF"],"word":"BE"},{"board":["AAA"],"word":"AAA"}],GRID)
add_problem("matrix_backtracking","障碍网格单调路径",matrix_tags,"uniquePathsWithObstacles",[("grid","int[][]")],"int","从左上角走到右下角，每次只能向右或向下；1 表示障碍，统计路径数量。","使用二维动态规划，当前格路径数来自上方与左方。",lambda grid:unique_paths_obstacles(grid),[{"grid":g} for g in MATRIX_CASES],GRID)
add_problem("matrix_backtracking","网格简单路径计数",matrix_tags,"countSimpleGridPaths",[("grid","int[][]")],"int","0 可通行、1 为障碍，统计从左上到右下且不重复经过格子的四方向路径数。","用访问集合进行深度优先搜索，离开格子时撤销标记。",lambda grid:simple_path_count(grid),[{"grid":g} for g in MATRIX_CASES],GRID)
add_problem("matrix_backtracking","矩阵最长递增路径",matrix_tags,"longestIncreasingPath",[("matrix","int[][]")],"int","在整数矩阵中四方向移动，每一步必须严格增大，返回最长路径的节点数。","对每个格子记忆化搜索其出发的最长路径。",lambda matrix:longest_increasing_path(matrix),[{"matrix":[[9,9,4],[6,6,8],[2,1,1]]},{"matrix":[[3,4,5],[3,2,6],[2,2,1]]},{"matrix":[[1]]},{"matrix":[[1,2],[4,3]]},{"matrix":[[7,6,5],[2,3,4]]}],GRID)
add_problem("matrix_backtracking","二进制矩阵岛屿数量",matrix_tags+["广度优先搜索"],"countMatrixIslands",[("grid","int[][]")],"int","1 表示陆地、0 表示水，四方向相邻陆地属于同一岛屿，返回岛屿数量。","从每块未访问陆地开始洪泛搜索。",lambda grid:len(island_areas(grid)),[{"grid":[[1,1,0],[0,1,0],[1,0,1]]},{"grid":[[1,1],[1,1]]},{"grid":[[0]]},{"grid":[[1]]},{"grid":[[1,0,1,0,1]]}],GRID)
add_problem("matrix_backtracking","矩阵最大岛屿面积",matrix_tags+["广度优先搜索"],"maxIslandArea",[("grid","int[][]")],"int","返回二进制矩阵中最大四连通陆地区域的格子数。","逐个洪泛搜索岛屿并统计面积。",lambda grid:max(island_areas(grid),default=0),[{"grid":[[1,1,0],[0,1,0],[1,0,1]]},{"grid":[[1,1],[1,1]]},{"grid":[[0]]},{"grid":[[1]]},{"grid":[[1,0,1,1,1]]}],GRID)
add_problem("matrix_backtracking","封闭岛屿数量",matrix_tags,"closedIslandCount",[("grid","int[][]")],"int","0 表示陆地、1 表示水，统计完全不接触矩阵边界的四连通陆地区域数量。","搜索每片陆地并记录是否触及边界。",lambda grid:closed_islands(grid),[{"grid":[[1,1,1,1],[1,0,0,1],[1,1,1,1]]},{"grid":[[0,0],[0,0]]},{"grid":[[1,1,1],[1,0,1],[1,1,1]]},{"grid":[[1]]},{"grid":[[1,0,1],[1,0,1],[1,1,1]]}],GRID)
add_problem("matrix_backtracking","岛屿海岸线长度",matrix_tags+["数学"],"islandPerimeter",[("grid","int[][]")],"int","计算所有陆地格与水域或矩阵外部接触的边总数。","每个陆地先贡献四条边，再扣除与上方、左方陆地共享的两条边。",lambda grid:island_perimeter(grid),[{"grid":[[0,1,0],[1,1,1],[0,1,0]]},{"grid":[[1]]},{"grid":[[1,1]]},{"grid":[[1,0],[0,1]]},{"grid":[[1,1],[1,1]]}],GRID)
add_problem("matrix_backtracking","飞地陆地格数量",matrix_tags+["广度优先搜索"],"numEnclaves",[("grid","int[][]")],"int","删除所有能通过陆地走到边界的格子后，返回剩余陆地格数量。","从边界陆地反向洪泛，统计未被访问的陆地。",lambda grid:enclave_count(grid),[{"grid":[[0,0,0],[1,0,1],[0,1,0]]},{"grid":[[0,0],[0,0]]},{"grid":[[1,1],[1,1]]},{"grid":[[1,0,1],[0,1,0],[1,0,1]]},{"grid":[[0,0,0,0],[0,1,1,0],[0,0,0,0]]}],GRID)
add_problem("matrix_backtracking","二进制网格最短路径",matrix_tags+["广度优先搜索"],"shortestBinaryPath",[("grid","int[][]")],"int","0 可通行、1 为障碍，四方向移动，返回左上到右下的最少格子数，不可达返回 -1。","使用广度优先搜索，首次到达终点时的距离即最短。",lambda grid:shortest_binary_path(grid),[{"grid":g} for g in MATRIX_CASES],GRID)
add_problem("matrix_backtracking","限定步数的网格路径",matrix_tags,"countExactStepPaths",[("grid","int[][]"),("steps","int")],"int","0 可通行、1 为障碍，统计恰好走 `steps` 步从左上到右下且不重复格子的路径数。","回溯枚举路径，并在剩余步数为零时判断位置。",lambda grid,steps:exact_step_paths(grid,steps),[{"grid":[[0,0],[0,0]],"steps":2},{"grid":[[0,0],[0,0]],"steps":3},{"grid":[[0]],"steps":0},{"grid":[[0,1],[0,0]],"steps":2},{"grid":[[0,0,0]],"steps":2}],GRID)
add_problem("matrix_backtracking","网格采集最大黄金",matrix_tags,"getMaximumGold",[("grid","int[][]")],"int","正数格表示黄金数量、0 表示不可进入；可从任意正数格出发，四方向移动且格子不可重复，返回最多黄金。","从每个起点回溯搜索，进入与离开时维护访问状态。",lambda grid:max_gold(grid),[{"grid":[[0,6,0],[5,8,7],[0,9,0]]},{"grid":[[1,0,7],[2,0,6],[3,4,5]]},{"grid":[[0]]},{"grid":[[10]]},{"grid":[[1,2],[3,4]]}],GRID)
add_problem("matrix_backtracking","矩形网格单调路径公式",matrix_tags+["数学"],"countMonotonePaths",[("rows","int"),("columns","int")],"long","在无障碍矩形网格中从左上到右下，每次向右或向下，返回路径数量。","总共需要固定数量的向下和向右移动，答案是组合数。",lambda rows,columns:diagonal_paths(rows,columns),[{"rows":3,"columns":7},{"rows":3,"columns":2},{"rows":1,"columns":5},{"rows":4,"columns":4},{"rows":5,"columns":6}],GRID)
add_problem("matrix_backtracking","全一最大正方形面积",matrix_tags+["单调栈"],"maximalSquareArea",[("grid","int[][]")],"int","在 0/1 矩阵中寻找只包含 1 的最大正方形，返回面积。","动态规划记录以每个格为右下角的最大正方形边长。",lambda grid:largest_square(grid),[{"grid":[[1,0,1],[1,1,1],[1,1,1]]},{"grid":[[0,1],[1,0]]},{"grid":[[1]]},{"grid":[[0]]},{"grid":[[1,1],[1,1]]}],GRID)
add_problem("matrix_backtracking","矩阵最小代价路径",matrix_tags,"minimumPathCost",[("grid","int[][]")],"long","从左上到右下，每次向右或向下，路径代价为经过格子值之和，返回最小代价。","滚动数组动态规划保存到达每个格子的最小代价。",lambda grid:min_path_sum(grid),[{"grid":[[1,3,1],[1,5,1],[4,2,1]]},{"grid":[[1,2,3],[4,5,6]]},{"grid":[[7]]},{"grid":[[1,9],[1,1]]},{"grid":[[5,1,2],[4,3,1]]}],GRID)
add_problem("matrix_backtracking","目标和单调路径数量",matrix_tags+["数学"],"countTargetSumPaths",[("grid","int[][]"),("target","int")],"int","从左上到右下仅向右或向下，统计路径元素和恰好等于目标值的路径数量。矩阵元素非负。","深度优先搜索当前路径和；也可用状态为位置与和的记忆化搜索。",lambda grid,target:monotone_target_paths(grid,target),[{"grid":[[1,2],[3,4]],"target":7},{"grid":[[1,2],[3,4]],"target":8},{"grid":[[5]],"target":5},{"grid":[[0,0],[0,0]],"target":0},{"grid":[[1,1,1],[1,1,1]],"target":4}],GRID)
add_problem("matrix_backtracking","棋盘 N 皇后方案数",matrix_tags+["位运算"],"countNQueensBoards",[("n","int")],"int","在 n×n 棋盘放置 n 个皇后，使任意两个皇后不同行、不同列、不同对角线，返回方案数。","逐行回溯并用位掩码记录被占用的列和对角线。",lambda n:n_queens(n),[{"n":1},{"n":4},{"n":5},{"n":6},{"n":7}],GRID)
add_problem("matrix_backtracking","障碍棋盘车的放置",matrix_tags,"countRookPlacements",[("board","int[][]"),("k","int")],"int","1 表示可放置格，0 表示障碍；放置 k 个车且任意两个不同列、不同行，返回方案数。","逐行决定跳过或在未占用列放置棋子。",lambda board,k:rook_placements(board,k),[{"board":[[1,1],[1,1]],"k":2},{"board":[[1,0],[0,1]],"k":1},{"board":[[1]],"k":1},{"board":[[1,1,1],[1,1,1]],"k":2},{"board":[[1,0,1],[0,1,0],[1,0,1]],"k":3}],GRID)
add_problem("matrix_backtracking","相邻异色网格涂色",matrix_tags+["数学"],"countGridColorings",[("rows","int"),("columns","int"),("colors","int")],"long","使用给定数量的颜色涂满网格，要求上下左右相邻格颜色不同，返回方案数。","按行展开格子回溯，当前颜色只需与上方和左方比较。",lambda rows,columns,colors:color_grid(rows,columns,colors),[{"rows":1,"columns":1,"colors":3},{"rows":1,"columns":3,"colors":2},{"rows":2,"columns":2,"colors":2},{"rows":2,"columns":3,"colors":3},{"rows":3,"columns":3,"colors":2}],GRID)
add_problem("matrix_backtracking","覆盖全部可通行格的路径",matrix_tags,"countHamiltonianGridPaths",[("grid","int[][]")],"int","0 可通行、1 为障碍，统计从左上到右下且每个可通行格恰好经过一次的路径数。","回溯维护已访问格数量，到达终点时检查是否覆盖全部可通行格。",lambda grid:hamiltonian_paths(grid),[{"grid":[[0,0],[0,0]]},{"grid":[[0,0,0]]},{"grid":[[0]]},{"grid":[[0,1],[0,0]]},{"grid":[[0,0,0],[1,1,0]]}],GRID)
add_problem("matrix_backtracking","矩阵可找到的词语数量",matrix_tags+["字符串", "哈希表"],"countExistingWords",[("board","string[]"),("words","string[]")],"int","对给定单词列表，统计能够在字符矩阵中通过不重复格子的四方向路径拼出的单词数量。", "逐个单词执行带回溯的矩阵搜索；可先按字符频率剪枝。",lambda board,words:count_present_words(board,words),[{"board":["ABCE","SFCS","ADEE"],"words":["ABCCED","SEE","ABCB"]},{"board":["AA"],"words":["A","AA","AAA"]},{"board":["X"],"words":["X","Y"]},{"board":["CAT","DOG"],"words":["CAT","COG","DOG"]},{"board":["AB","CD"],"words":["AB","AC","BD","ABCD"]}],GRID)


# Batch 4: bit manipulation and integer mathematics.
def xor_to(n):
    return (n,1,n+1,0)[n%4]


def reverse_bits_32(value):
    answer=0
    for _ in range(32):answer=(answer<<1)|(value&1);value>>=1
    return answer


def swap_odd_even_bits(value):
    return ((value & 0xAAAAAAAA)>>1)|((value & 0x55555555)<<1)


def subset_xor_sum(nums):
    total=0
    for mask in range(1<<len(nums)):
        value=0
        for index,item in enumerate(nums):
            if mask>>index&1:value^=item
        total+=value
    return total


def max_xor_pair(nums):
    return max((a^b for i,a in enumerate(nums) for b in nums[i+1:]),default=0)


def total_hamming(nums):
    return sum(sum((value>>bit)&1 for value in nums)*(len(nums)-sum((value>>bit)&1 for value in nums)) for bit in range(31))


def add_without_plus(first,second):
    while second:first,second=first^second,(first&second)<<1
    return first


def range_and(left,right):
    shift=0
    while left<right:left>>=1;right>>=1;shift+=1
    return left<<shift


def count_bits_to(n):
    answer=[0]*(n+1)
    for value in range(1,n+1):answer[value]=answer[value>>1]+(value&1)
    return answer


def gray_code(n):
    return [value^(value>>1) for value in range(1<<n)]


BIT = "- 所有整数均为非负 32 位有符号整数\n- 数组长度不超过 `10^5`\n- 结果保证在返回类型范围内"
bit_tags=["位运算","数学"]
add_problem("bit_math","整数二进制一的数量",bit_tags,"hammingWeight",[("value","int")],"int","返回整数二进制表示中 1 的个数。","不断清除最低位的 1，操作次数即答案。",lambda value:value.bit_count(),[{"value":11},{"value":128},{"value":0},{"value":7},{"value":1023}],BIT)
add_problem("bit_math","整数奇偶校验位",bit_tags,"bitParity",[("value","int")],"boolean","当整数二进制表示中 1 的数量为奇数时返回 true。","逐步异或各二进制位，或计算位计数的奇偶性。",lambda value:value.bit_count()%2==1,[{"value":3},{"value":7},{"value":0},{"value":8},{"value":15}],BIT)
add_problem("bit_math","判断二的整数次幂",bit_tags,"isPowerOfTwo",[("value","int")],"boolean","判断正整数是否恰好是 2 的整数次幂；0 返回 false。","二的幂只有一个二进制位为 1，可用 n&(n-1) 判断。",lambda value:value>0 and value&(value-1)==0,[{"value":1},{"value":16},{"value":3},{"value":0},{"value":1024}],BIT)
add_problem("bit_math","不超过目标的最大二次幂",bit_tags,"highestPowerOfTwo",[("value","int")],"int","返回不超过正整数 value 的最大 2 的整数次幂。","最高有效位对应的权值就是答案。",lambda value:1<<(value.bit_length()-1),[{"value":1},{"value":19},{"value":64},{"value":1000},{"value":7}],BIT)
add_problem("bit_math","数组中的唯一元素",bit_tags+["数组","哈希表"],"singleNumberXor",[("nums","int[]")],"int","数组中除一个元素出现一次外，其余元素均出现两次，返回唯一元素。","所有元素异或，成对元素会相互抵消。",lambda nums:math.prod([1]) and (lambda a:(__import__('functools').reduce(lambda x,y:x^y,a,0)))(nums),[{"nums":[2,2,1]},{"nums":[4,1,2,1,2]},{"nums":[7]},{"nums":[0,9,9]},{"nums":[3,5,3,6,6]}],BIT)
add_problem("bit_math","零到 N 中缺失的数字",bit_tags+["数组","哈希表"],"missingNumberXor",[("nums","int[]")],"int","长度为 n 的数组包含 0 到 n 中除一个数外的所有数字，返回缺失数字。","把数组元素与 0..n 全部异或，重复值抵消后留下缺失值。",lambda nums:(lambda result:[(result:=result^i^v) for i,v in enumerate(nums)] and result^len(nums))(0),[{"nums":[3,0,1]},{"nums":[0,1]},{"nums":[9,6,4,2,3,5,7,0,1]},{"nums":[0]},{"nums":[1]}],BIT)
add_problem("bit_math","找出新增字符",bit_tags+["字符串","哈希表"],"findExtraCharacter",[("source","string"),("target","string")],"string","target 由 source 打乱后额外加入一个字符形成，返回新增字符。","异或两个字符串的全部字符编码，成对字符抵消。",lambda source,target:chr(__import__('functools').reduce(lambda x,ch:x^ord(ch),source+target,0)),[{"source":"abcd","target":"abcde"},{"source":"","target":"y"},{"source":"a","target":"aa"},{"source":"xyz","target":"xzyq"},{"source":"hello","target":"olehlz"}],BIT)
add_problem("bit_math","整数区间异或值",bit_tags,"xorRange",[("left","int"),("right","int")],"int","计算闭区间 [left,right] 内所有整数的异或值。","0 到 n 的前缀异或以 4 为周期，两个前缀异或即可得到区间答案。",lambda left,right:xor_to(right)^xor_to(left-1),[{"left":3,"right":9},{"left":0,"right":0},{"left":1,"right":4},{"left":10,"right":20},{"left":7,"right":7}],BIT)
add_problem("bit_math","反转三十二位二进制",bit_tags,"reverseBits32",[("value","int")],"long","把整数视作 32 位无符号二进制，反转所有位后返回结果。","循环 32 次取出最低位并追加到答案右端。",lambda value:reverse_bits_32(value),[{"value":43261596},{"value":0},{"value":1},{"value":4294967295},{"value":10}],BIT)
add_problem("bit_math","交换奇偶二进制位",bit_tags,"swapOddEvenBits",[("value","int")],"long","交换 32 位二进制中的相邻位：第 0 位与第 1 位、第 2 位与第 3 位，依此类推。","用奇偶位掩码分别取出两组位，向相反方向移动后合并。",lambda value:swap_odd_even_bits(value),[{"value":10},{"value":23},{"value":0},{"value":1},{"value":255}],BIT)
add_problem("bit_math","清除最低有效一位",bit_tags,"clearLowestSetBit",[("value","int")],"int","将整数二进制中最低位置的 1 清零并返回结果；0 仍返回 0。","使用 value & (value-1)。",lambda value:value&(value-1),[{"value":12},{"value":7},{"value":0},{"value":8},{"value":10}],BIT)
add_problem("bit_math","两个整数的位差",bit_tags,"hammingDistance",[("first","int"),("second","int")],"int","返回两个整数二进制表示中不同位的数量。","先异或得到不同位掩码，再统计其中的 1。",lambda first,second:(first^second).bit_count(),[{"first":1,"second":4},{"first":3,"second":1},{"first":0,"second":0},{"first":15,"second":0},{"first":10,"second":7}],BIT)
add_problem("bit_math","所有子集异或和",bit_tags+["数组","回溯","动态规划"],"subsetXorSum",[("nums","int[]")],"long","枚举数组所有子集，返回每个子集元素异或值的总和，空集异或值为 0。","可回溯选择元素；也可利用每个出现过的二进制位会贡献给一半子集。",lambda nums:subset_xor_sum(nums),[{"nums":[1,3]},{"nums":[5,1,6]},{"nums":[0]},{"nums":[2,2]},{"nums":[1,2,3,4]}],BIT)
add_problem("bit_math","二进制子掩码数量",bit_tags+["动态规划"],"countSubmasks",[("mask","int")],"long","统计一个二进制掩码的所有子掩码数量，包含 0 和自身。","每个为 1 的位都可以独立选择保留或清除，答案为 2 的位计数次幂。",lambda mask:1<<mask.bit_count(),[{"mask":5},{"mask":7},{"mask":0},{"mask":8},{"mask":15}],BIT)
add_problem("bit_math","数组最大异或对",bit_tags+["数组","哈希表"],"maximumXorPair",[("nums","int[]")],"int","返回数组中两个不同元素能够得到的最大异或值。","按高位到低位构造答案，用前缀集合判断候选位是否可行。",lambda nums:max_xor_pair(nums),[{"nums":[3,10,5,25,2,8]},{"nums":[0,1]},{"nums":[2,4]},{"nums":[8,10,2]},{"nums":[7,7,7]}],BIT)
add_problem("bit_math","数组总汉明距离",bit_tags+["数组","数学"],"totalHammingDistance",[("nums","int[]")],"long","对数组所有无序数对，累加它们的二进制位差。","逐位统计 0 和 1 的数量，该位贡献为两者乘积。",lambda nums:total_hamming(nums),[{"nums":[4,14,2]},{"nums":[4,14,4]},{"nums":[0]},{"nums":[0,1]},{"nums":[1,2,3]}],BIT)
add_problem("bit_math","不用加号计算非负整数和",bit_tags,"addWithoutPlus",[("first","int"),("second","int")],"long","不使用加法运算符，返回两个非负整数之和。","异或得到无进位和，与后左移一位得到进位，重复直到进位为零。",lambda first,second:add_without_plus(first,second),[{"first":1,"second":2},{"first":9,"second":11},{"first":0,"second":0},{"first":1024,"second":2048},{"first":7,"second":8}],BIT)
add_problem("bit_math","整数区间按位与",bit_tags+["二分查找"],"rangeBitwiseAnd",[("left","int"),("right","int")],"int","计算闭区间 [left,right] 内所有整数的按位与结果。","不断右移两端直到公共前缀相同，再把前缀移回原位。",lambda left,right:range_and(left,right),[{"left":5,"right":7},{"left":0,"right":0},{"left":1,"right":2147483647},{"left":12,"right":15},{"left":8,"right":8}],BIT)
add_problem("bit_math","零到 N 的位计数",bit_tags+["动态规划","数组"],"countBits",[("n","int")],"int[]","返回长度 n+1 的数组，第 i 项是整数 i 的二进制中 1 的数量。","利用 dp[i]=dp[i>>1]+(i&1) 递推。",lambda n:count_bits_to(n),[{"n":2},{"n":5},{"n":0},{"n":1},{"n":10}],BIT)
add_problem("bit_math","生成格雷码序列",bit_tags+["数组","递归"],"grayCode",[("n","int")],"int[]","返回 n 位格雷码序列，序列从 0 开始且相邻数只有一个二进制位不同。","第 i 个格雷码可由 i 异或 i 右移一位直接得到。",lambda n:gray_code(n),[{"n":1},{"n":2},{"n":3},{"n":4},{"n":5}],BIT)


# Batch 5: monotonic stack and deque problems.
def nearest(values, direction, greater):
    answer=[-1]*len(values);stack=[];indices=range(len(values)) if direction=="right" else range(len(values)-1,-1,-1)
    for i in indices:
        while stack and ((values[stack[-1]]<=values[i]) if greater else (values[stack[-1]]>=values[i])):stack.pop()
        answer[i]=values[stack[-1]] if stack else -1;stack.append(i)
    return answer


def distance_greater(values, previous=False):
    answer=[0]*len(values);stack=[];indices=range(len(values)) if previous else range(len(values)-1,-1,-1)
    for i in indices:
        while stack and values[stack[-1]]<=values[i]:stack.pop()
        answer[i]=abs(stack[-1]-i) if stack else 0;stack.append(i)
    return answer


def stock_span(values):
    answer=[];stack=[]
    for i,value in enumerate(values):
        while stack and values[stack[-1]]<=value:stack.pop()
        answer.append(i+1 if not stack else i-stack[-1]);stack.append(i)
    return answer


def visible_people(values):
    answer=[0]*len(values);stack=[]
    for i in range(len(values)-1,-1,-1):
        while stack and values[i]>stack[-1]:stack.pop();answer[i]+=1
        if stack:answer[i]+=1
        stack.append(values[i])
    return answer


def largest_rectangle_area(heights):
    stack=[];best=0
    for i,height in enumerate(heights+[0]):
        while stack and heights[stack[-1]]>height:
            bar=stack.pop();width=i if not stack else i-stack[-1]-1;best=max(best,heights[bar]*width)
        stack.append(i)
    return best


def maximal_rectangle(grid):
    heights=[0]*len(grid[0]);best=0
    for row in grid:
        heights=[heights[i]+1 if value else 0 for i,value in enumerate(row)]
        best=max(best,largest_rectangle_area(heights))
    return best


def trap_water(values):
    left,right,left_max,right_max,total=0,len(values)-1,0,0,0
    while left<=right:
        if left_max<=right_max:left_max=max(left_max,values[left]);total+=left_max-values[left];left+=1
        else:right_max=max(right_max,values[right]);total+=right_max-values[right];right-=1
    return total


def sum_subarray_extreme(values, minimum=True):
    total=0
    for left in range(len(values)):
        current=values[left]
        for right in range(left,len(values)):
            current=min(current,values[right]) if minimum else max(current,values[right]);total+=current
    return total


def max_width_ramp(values):
    stack=[]
    for i,value in enumerate(values):
        if not stack or value<values[stack[-1]]:stack.append(i)
    best=0
    for j in range(len(values)-1,-1,-1):
        while stack and values[stack[-1]]<=values[j]:best=max(best,j-stack.pop())
    return best


def shortest_unsorted(values):
    sorted_values=sorted(values);different=[i for i,(a,b) in enumerate(zip(values,sorted_values)) if a!=b]
    return 0 if not different else different[-1]-different[0]+1


def remove_k_digits(number,k):
    stack=[]
    for digit in number:
        while k and stack and stack[-1]>digit:stack.pop();k-=1
        stack.append(digit)
    if k:stack=stack[:-k]
    return "".join(stack).lstrip("0") or "0"


def has_132(values):
    stack=[];middle=-10**30
    for value in reversed(values):
        if value<middle:return True
        while stack and value>stack[-1]:middle=stack.pop()
        stack.append(value)
    return False


def asteroid_collision(values):
    stack=[]
    for value in values:
        alive=True
        while alive and value<0 and stack and stack[-1]>0:
            if stack[-1]<-value:stack.pop();continue
            if stack[-1]==-value:stack.pop()
            alive=False
        if alive:stack.append(value)
    return stack


def next_greater_circular(values):
    answer=[-1]*len(values);stack=[]
    for i in range(2*len(values)):
        index=i%len(values)
        while stack and values[stack[-1]]<values[index]:answer[stack.pop()]=values[index]
        if i<len(values):stack.append(index)
    return answer


MONO_CASES=[[2,1,2,4,3],[1,3,2,4],[5,4,3,2,1],[1],[2,2,2]]
MONO="- `1 ≤ n ≤ 10^5`\n- 数组元素在 32 位有符号整数范围内\n- 需要在线性或近线性时间内完成"
mono_tags=["单调栈","栈","数组"]
add_problem("monotonic_stack","右侧第一个更大值",mono_tags,"nextGreaterRight",[("nums","int[]")],"int[]","返回每个元素右侧第一个严格更大的值，不存在时为 -1。","维护单调递减栈，遇到更大值时结算栈顶。",lambda nums:nearest(nums,"left",True),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","左侧第一个更大值",mono_tags,"nextGreaterLeft",[("nums","int[]")],"int[]","返回每个元素左侧第一个严格更大的值，不存在时为 -1。","从左到右维护单调递减栈。",lambda nums:nearest(nums,"right",True),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","右侧第一个更小值",mono_tags,"nextSmallerRight",[("nums","int[]")],"int[]","返回每个元素右侧第一个严格更小的值，不存在时为 -1。","从右向左维护单调递增栈。",lambda nums:nearest(nums,"left",False),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","左侧第一个更小值",mono_tags,"nextSmallerLeft",[("nums","int[]")],"int[]","返回每个元素左侧第一个严格更小的值，不存在时为 -1。","从左向右维护单调递增栈。",lambda nums:nearest(nums,"right",False),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","到右侧更大元素的距离",mono_tags,"distanceToNextGreater",[("nums","int[]")],"int[]","返回每个位置到右侧第一个严格更大元素的下标距离，不存在时为 0。","用单调递减栈保存尚未找到更大值的下标。",lambda nums:distance_greater(nums),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","到左侧更大元素的距离",mono_tags,"distanceToPreviousGreater",[("nums","int[]")],"int[]","返回每个位置到左侧第一个严格更大元素的距离，不存在时为 0。","反向扫描并维护单调递减栈。",lambda nums:distance_greater(nums,True),[{"nums":v} for v in MONO_CASES],MONO)
add_problem("monotonic_stack","等待更高温度的天数",mono_tags,"dailyTemperaturesPlus",[("temperatures","int[]")],"int[]","对每天温度，返回还需等待几天才会出现更高温度；不存在时为 0。","单调递减栈保存温度尚未升高的日期。",lambda temperatures:distance_greater(temperatures),[{"temperatures":[73,74,75,71,69,72,76,73]},{"temperatures":[30,40,50,60]},{"temperatures":[30,60,90]},{"temperatures":[90]},{"temperatures":[50,50,50]}],MONO)
add_problem("monotonic_stack","股票价格跨度",mono_tags,"stockSpan",[("prices","int[]")],"int[]","每天的价格跨度是向左连续不高于当天价格的天数，返回所有跨度。","单调递减栈保存上一个严格更高价格的下标。",lambda prices:stock_span(prices),[{"prices":[100,80,60,70,60,75,85]},{"prices":[10,20,30]},{"prices":[30,20,10]},{"prices":[5]},{"prices":[10,10,10]}],MONO)
add_problem("monotonic_stack","右侧可见人数",mono_tags,"canSeePersonsCount",[("heights","int[]")],"int[]","队列中身高互不相同，返回每个人向右能看见的人数。中间所有人必须矮于两端较矮者。","从右向左用单调栈弹出所有更矮者，并额外看到第一个更高者。",lambda heights:visible_people(heights),[{"heights":[10,6,8,5,11,9]},{"heights":[5,1,2,3,10]},{"heights":[1]},{"heights":[4,3,2,1]},{"heights":[1,2,3,4]}],MONO)
add_problem("monotonic_stack","直方图最大矩形扩展",mono_tags,"largestHistogramRectangle",[("heights","int[]")],"long","每个柱宽为 1，返回直方图内最大轴对齐矩形面积。","单调递增栈在遇到更矮柱时结算被弹出柱的最大宽度。",lambda heights:largest_rectangle_area(heights),[{"heights":[2,1,5,6,2,3]},{"heights":[2,4]},{"heights":[1]},{"heights":[2,2,2]},{"heights":[4,3,2,1]}],MONO)
add_problem("monotonic_stack","二进制矩阵最大矩形",mono_tags+["矩阵","动态规划"],"maximalRectangleArea",[("grid","int[][]")],"long","在 0/1 矩阵中寻找只包含 1 的最大矩形并返回面积。","逐行累计柱高，把每一行转换为直方图最大矩形问题。",lambda grid:maximal_rectangle(grid),[{"grid":[[1,0,1,0],[1,0,1,1],[1,1,1,1]]},{"grid":[[0]]},{"grid":[[1]]},{"grid":[[1,1],[1,1]]},{"grid":[[1,0],[1,0]]}],MONO)
add_problem("monotonic_stack","柱形地面蓄水量",mono_tags+["双指针"],"trappedWater",[("height","int[]")],"long","柱宽为 1，计算下雨后能够接住的水量。","双指针维护两侧最高柱；也可用单调栈逐层结算凹槽。",lambda height:trap_water(height),[{"height":[0,1,0,2,1,0,1,3,2,1,2,1]},{"height":[4,2,0,3,2,5]},{"height":[1]},{"height":[2,0,2]},{"height":[3,2,1]}],MONO)
add_problem("monotonic_stack","所有子数组最小值之和",mono_tags+["动态规划"],"sumSubarrayMinimums",[("nums","int[]")],"long","枚举所有非空连续子数组，返回它们最小值的总和。","计算每个元素作为最小值时能向左右扩展的边界，并累加贡献。",lambda nums:sum_subarray_extreme(nums,True),[{"nums":[3,1,2,4]},{"nums":[11,81,94,43,3]},{"nums":[5]},{"nums":[2,2]},{"nums":[1,2,3]}],MONO)
add_problem("monotonic_stack","所有子数组最大值之和",mono_tags+["动态规划"],"sumSubarrayMaximums",[("nums","int[]")],"long","枚举所有非空连续子数组，返回它们最大值的总和。","用单调栈求每个元素作为最大值的左右边界并计算贡献。",lambda nums:sum_subarray_extreme(nums,False),[{"nums":[3,1,2,4]},{"nums":[1,3,3]},{"nums":[5]},{"nums":[2,2]},{"nums":[1,2,3]}],MONO)
add_problem("monotonic_stack","数组最大宽度坡",mono_tags+["排序","双指针"],"maxWidthRamp",[("nums","int[]")],"int","寻找 i≤j 且 nums[i]≤nums[j] 的最大 j-i。","先建立前缀严格递减下标栈，再从右向左弹栈扩展最大宽度。",lambda nums:max_width_ramp(nums),[{"nums":[6,0,8,2,1,5]},{"nums":[9,8,1,0,1,9,4,0,4,1]},{"nums":[1]},{"nums":[3,2,1]},{"nums":[1,2,3]}],MONO)
add_problem("monotonic_stack","最短无序连续子数组",mono_tags+["排序","双指针"],"findUnsortedSubarray",[("nums","int[]")],"int","返回最短连续区间长度，使得只排序该区间就能令整个数组非递减；已有序时返回 0。","从两端扫描维护区间外最大值和最小值，定位越界位置。",lambda nums:shortest_unsorted(nums),[{"nums":[2,6,4,8,10,9,15]},{"nums":[1,2,3,4]},{"nums":[1]},{"nums":[1,3,2,2,2]},{"nums":[3,2,1]}],MONO)
add_problem("monotonic_stack","删除 K 位得到最小数",mono_tags+["字符串"],"removeKDigits",[("number","string"),("k","int")],"string","从十进制数字字符串中删除恰好 k 位，返回无前导零的最小结果。","维护单调递增数字栈，当前数字更小时删除栈顶较大数字。",lambda number,k:remove_k_digits(number,k),[{"number":"1432219","k":3},{"number":"10200","k":1},{"number":"10","k":2},{"number":"12345","k":2},{"number":"10001","k":1}],MONO)
add_problem("monotonic_stack","一三二模式检测",mono_tags,"find132Pattern",[("nums","int[]")],"boolean","判断是否存在 i<j<k 且 nums[i]<nums[k]<nums[j]。","从右向左维护递减栈和已弹出的最大中间值。",lambda nums:has_132(nums),[{"nums":[1,2,3,4]},{"nums":[3,1,4,2]},{"nums":[-1,3,2,0]},{"nums":[1]},{"nums":[3,5,0,3,4]}],MONO)
add_problem("monotonic_stack","小行星碰撞模拟",mono_tags,"asteroidCollision",[("asteroids","int[]")],"int[]","正数向右、负数向左，绝对值表示大小；相向碰撞时较小者消失，相等则都消失，返回最终序列。","用栈保存稳定的小行星，仅处理栈顶向右且当前向左的冲突。",lambda asteroids:asteroid_collision(asteroids),[{"asteroids":[5,10,-5]},{"asteroids":[8,-8]},{"asteroids":[10,2,-5]},{"asteroids":[-2,-1,1,2]},{"asteroids":[1,-2,-2,-2]}],MONO)
add_problem("monotonic_stack","循环数组下一个更大值",mono_tags,"nextGreaterCircular",[("nums","int[]")],"int[]","数组首尾相接，对每个元素返回向右循环遇到的第一个严格更大值，不存在时为 -1。","扫描两遍数组，用下标取模模拟循环，并用单调栈结算答案。",lambda nums:next_greater_circular(nums),[{"nums":[1,2,1]},{"nums":[1,2,3,4,3]},{"nums":[5]},{"nums":[3,2,1]},{"nums":[2,2,2]}],MONO)


# Batch 6: sliding window and hash-frequency problems.
def longest_unique(text):
    last={};left=0;best=0
    for right,ch in enumerate(text):
        left=max(left,last.get(ch,-1)+1);last[ch]=right;best=max(best,right-left+1)
    return best


def max_vowels(text,k):
    values=[ch in "aeiou" for ch in text];window=sum(values[:k]);best=window
    for i in range(k,len(text)):window+=values[i]-values[i-k];best=max(best,window)
    return best


def anagram_windows(text,pattern):
    need=Counter(pattern);window=Counter(text[:len(pattern)]);answer=int(window==need)
    for right in range(len(pattern),len(text)):
        old=text[right-len(pattern)];window[old]-=1
        if window[old]==0:del window[old]
        window[text[right]]+=1;answer+=window==need
    return answer


def minimum_window(text,pattern):
    need=Counter(pattern);missing=len(pattern);left=0;best=(0,len(text)+1)
    for right,ch in enumerate(text,1):
        if need[ch]>0:missing-=1
        need[ch]-=1
        if missing==0:
            while need[text[left]]<0:need[text[left]]+=1;left+=1
            if right-left<best[1]-best[0]:best=(left,right)
            need[text[left]]+=1;missing+=1;left+=1
    return text[best[0]:best[1]] if best[1]<=len(text) else ""


def character_replacement(text,k):
    counts=Counter();left=0;best=0;highest=0
    for right,ch in enumerate(text):
        counts[ch]+=1;highest=max(highest,counts[ch])
        while right-left+1-highest>k:counts[text[left]]-=1;left+=1
        best=max(best,right-left+1)
    return best


def longest_k_distinct(text,k):
    counts=Counter();left=0;best=0
    for right,ch in enumerate(text):
        counts[ch]+=1
        while len(counts)>k:
            counts[text[left]]-=1
            if counts[text[left]]==0:del counts[text[left]]
            left+=1
        best=max(best,right-left+1)
    return best


def smallest_all_distinct(text):
    return len(minimum_window(text,"".join(dict.fromkeys(text))))


def count_contains_abc(text):
    last={"a":-1,"b":-1,"c":-1};answer=0
    for i,ch in enumerate(text):
        if ch in last:last[ch]=i
        answer+=min(last.values())+1
    return answer


def longest_balanced_binary(text):
    first={0:-1};balance=0;best=0
    for i,ch in enumerate(text):
        balance+=1 if ch=="1" else -1
        if balance in first:best=max(best,i-first[balance])
        else:first[balance]=i
    return best


def count_fixed_distinct(text,k):
    if k>len(text):return 0
    counts=Counter(text[:k]);answer=int(len(counts)==k)
    for right in range(k,len(text)):
        old=text[right-k];counts[old]-=1
        if counts[old]==0:del counts[old]
        counts[text[right]]+=1;answer+=len(counts)==k
    return answer


def repeated_dna_count(text):
    counts=Counter(text[i:i+10] for i in range(max(0,len(text)-9)))
    return sum(value>1 for value in counts.values())


def max_binary_ones(text,k):
    left=zeros=best=0
    for right,ch in enumerate(text):
        zeros+=ch=="0"
        while zeros>k:zeros-=text[left]=="0";left+=1
        best=max(best,right-left+1)
    return best


def min_subarray_len(nums,target):
    left=total=0;best=len(nums)+1
    for right,value in enumerate(nums):
        total+=value
        while total>=target:best=min(best,right-left+1);total-=nums[left];left+=1
    return 0 if best>len(nums) else best


def max_fixed_sum(nums,k):
    current=sum(nums[:k]);best=current
    for i in range(k,len(nums)):current+=nums[i]-nums[i-k];best=max(best,current)
    return best


def product_less_than(nums,k):
    if k<=1:return 0
    product=1;left=0;answer=0
    for right,value in enumerate(nums):
        product*=value
        while product>=k:product//=nums[left];left+=1
        answer+=right-left+1
    return answer


def at_most_odds(nums,k):
    if k<0:return 0
    left=answer=0
    for right,value in enumerate(nums):
        k-=value%2
        while k<0:k+=nums[left]%2;left+=1
        answer+=right-left+1
    return answer


def subarray_sum_count(nums,target):
    counts=Counter({0:1});prefix=answer=0
    for value in nums:
        prefix+=value;answer+=counts[prefix-target];counts[prefix]+=1
    return answer


WINDOW="- `1 ≤ 输入长度 ≤ 10^5`\n- 字符串由可见 ASCII 字符组成\n- 数组窗口题中的元素满足题面所述正数或 0/1 条件"
window_tags=["滑动窗口","哈希表"]
add_problem("sliding_window","无重复字符最长子串",window_tags+["字符串","双指针"],"longestUniqueSubstring",[("text","string")],"int","返回不包含重复字符的最长连续子串长度。","滑动窗口记录字符最后出现位置，重复时移动左边界。",lambda text:longest_unique(text),[{"text":"abcabcbb"},{"text":"bbbbb"},{"text":"pwwkew"},{"text":"a"},{"text":"dvdf"}],WINDOW)
add_problem("sliding_window","固定窗口最多元音",window_tags+["字符串"],"maxVowelsInWindow",[("text","string"),("k","int")],"int","返回长度恰好为 k 的连续子串中元音字母数量的最大值。","维护固定长度窗口的元音计数，每次加入右端并移除左端。",lambda text,k:max_vowels(text,k),[{"text":"abciiidef","k":3},{"text":"aeiou","k":2},{"text":"leetcode","k":3},{"text":"rhythms","k":4},{"text":"a","k":1}],WINDOW)
add_problem("sliding_window","字符串异位词窗口数量",window_tags+["字符串"],"countAnagramWindows",[("text","string"),("pattern","string")],"int","统计 text 中与 pattern 字符频率完全相同的连续窗口数量。","维护固定窗口与目标字符串的频率表。",lambda text,pattern:anagram_windows(text,pattern),[{"text":"cbaebabacd","pattern":"abc"},{"text":"abab","pattern":"ab"},{"text":"a","pattern":"a"},{"text":"abc","pattern":"d"},{"text":"aaaa","pattern":"aa"}],WINDOW)
add_problem("sliding_window","字符串排列包含判断",window_tags+["字符串"],"containsPermutation",[("pattern","string"),("text","string")],"boolean","判断 text 是否包含 pattern 的某个排列作为连续子串。","检查长度等于 pattern 的每个滑动窗口频率。",lambda pattern,text:anagram_windows(text,pattern)>0,[{"pattern":"ab","text":"eidbaooo"},{"pattern":"ab","text":"eidboaoo"},{"pattern":"a","text":"a"},{"pattern":"abc","text":"bbbca"},{"pattern":"xyz","text":"xy"}],WINDOW)
add_problem("sliding_window","最短覆盖子串长度",window_tags+["字符串","双指针"],"minimumCoverLength",[("text","string"),("pattern","string")],"int","返回 text 中覆盖 pattern 全部字符及其次数的最短子串长度；不存在返回 0。","右端扩展直到覆盖需求，再收缩左端获得最短窗口。",lambda text,pattern:len(minimum_window(text,pattern)),[{"text":"ADOBECODEBANC","pattern":"ABC"},{"text":"a","pattern":"a"},{"text":"a","pattern":"aa"},{"text":"aaab","pattern":"ab"},{"text":"cabefgecdaecf","pattern":"cae"}],WINDOW)
add_problem("sliding_window","替换字符后的最长重复子串",window_tags+["字符串","双指针"],"characterReplacement",[("text","string"),("k","int")],"int","最多替换 k 个字符，使某个连续子串中的字符全部相同，返回最大长度。","窗口长度减去最高字符频次即所需替换数。",lambda text,k:character_replacement(text,k),[{"text":"ABAB","k":2},{"text":"AABABBA","k":1},{"text":"A","k":0},{"text":"ABCDE","k":1},{"text":"BAAA","k":0}],WINDOW)
add_problem("sliding_window","至多 K 种字符的最长子串",window_tags+["字符串","双指针"],"longestSubstringKDistinct",[("text","string"),("k","int")],"int","返回最多包含 k 种不同字符的最长连续子串长度。","频率表超过 k 种字符时移动左端直至重新合法。",lambda text,k:longest_k_distinct(text,k),[{"text":"eceba","k":2},{"text":"aa","k":1},{"text":"abc","k":3},{"text":"aabbcc","k":2},{"text":"abcd","k":1}],WINDOW)
add_problem("sliding_window","覆盖全部不同字符的最短长度",window_tags+["字符串"],"smallestDistinctCover",[("text","string")],"int","返回包含 text 中所有不同字符的最短连续子串长度。","先统计不同字符总数，再用窗口维护当前覆盖数量。",lambda text:smallest_all_distinct(text),[{"text":"aabcbcdbca"},{"text":"aaaa"},{"text":"abc"},{"text":"abca"},{"text":"xyyzyzyx"}],WINDOW)
add_problem("sliding_window","含 ABC 的子串数量",window_tags+["字符串","数学"],"countABCSubstrings",[("text","string")],"long","字符串只包含 a、b、c，统计同时至少包含三种字符的连续子串数量。","记录三种字符最近出现位置，每个右端点贡献最小最近位置加一。",lambda text:count_contains_abc(text),[{"text":"abcabc"},{"text":"aaacb"},{"text":"abc"},{"text":"aaaa"},{"text":"cabca"}],WINDOW)
add_problem("sliding_window","两个不同字符的最长子串",window_tags+["字符串","双指针"],"longestTwoDistinct",[("text","string")],"int","返回最多包含两种不同字符的最长连续子串长度。","这是至多 k 种字符窗口在 k=2 时的特例。",lambda text:longest_k_distinct(text,2),[{"text":"eceba"},{"text":"ccaabbb"},{"text":"a"},{"text":"abcabc"},{"text":"aabbccaa"}],WINDOW)
add_problem("sliding_window","零一字符最长平衡区间",window_tags+["字符串","数学"],"longestBalancedBinaryString",[("text","string")],"int","字符串只含 0 和 1，返回 0 与 1 数量相等的最长连续子串长度。","把 0 视作 -1、1 视作 1，记录每个前缀和第一次出现位置。",lambda text:longest_balanced_binary(text),[{"text":"01"},{"text":"0011"},{"text":"0"},{"text":"01010"},{"text":"111000"}],WINDOW)
add_problem("sliding_window","固定长度全异字符窗口",window_tags+["字符串"],"countDistinctCharacterWindows",[("text","string"),("k","int")],"int","统计长度为 k 且所有字符互不相同的连续子串数量。","维护固定窗口频率表，字符种类数等于 k 时计数。",lambda text,k:count_fixed_distinct(text,k),[{"text":"havefunonleetcode","k":5},{"text":"home","k":5},{"text":"abcabc","k":3},{"text":"aaaa","k":1},{"text":"abca","k":4}],WINDOW)
add_problem("sliding_window","最短覆盖子串内容",window_tags+["字符串","双指针"],"minimumCoverSubstring",[("text","string"),("pattern","string")],"string","返回 text 中覆盖 pattern 全部字符及次数的最短子串；不存在返回空字符串，长度相同取最靠左者。","用缺失字符计数控制窗口扩张和收缩。",lambda text,pattern:minimum_window(text,pattern),[{"text":"ADOBECODEBANC","pattern":"ABC"},{"text":"a","pattern":"a"},{"text":"a","pattern":"aa"},{"text":"aaab","pattern":"ab"},{"text":"cabefgecdaecf","pattern":"cae"}],WINDOW)
add_problem("sliding_window","重复 DNA 片段种数",window_tags+["字符串"],"countRepeatedDNA",[("text","string")],"int","DNA 字符串只含 A、C、G、T，统计出现至少两次的长度 10 连续片段种数。","滑动固定窗口并用哈希表统计每个片段的出现次数。",lambda text:repeated_dna_count(text),[{"text":"AAAAACCCCCAAAAACCCCCCAAAAAGGGTTT"},{"text":"AAAAAAAAAAAAA"},{"text":"ACGT"},{"text":"ACGTACGTACGT"},{"text":"CCCCCCCCCC"}],WINDOW)
add_problem("sliding_window","翻转后的最长连续一",window_tags+["字符串","双指针"],"longestOnesAfterFlips",[("bits","string"),("k","int")],"int","二进制字符串中最多把 k 个 0 翻转为 1，返回最长连续 1 的长度。","窗口中 0 的数量超过 k 时移动左端。",lambda bits,k:max_binary_ones(bits,k),[{"bits":"110100110","k":2},{"bits":"000","k":1},{"bits":"1111","k":0},{"bits":"10101","k":2},{"bits":"0","k":0}],WINDOW)
add_problem("sliding_window","正数数组最短达标区间",window_tags+["数组","双指针"],"minimumSubarrayLength",[("nums","int[]"),("target","int")],"int","数组元素均为正数，返回和至少为 target 的最短连续子数组长度；不存在返回 0。","右端扩张增加总和，满足条件时持续移动左端。",lambda nums,target:min_subarray_len(nums,target),[{"nums":[2,3,1,2,4,3],"target":7},{"nums":[1,4,4],"target":4},{"nums":[1,1,1],"target":5},{"nums":[5],"target":5},{"nums":[1,2,3,4,5],"target":11}],WINDOW)
add_problem("sliding_window","固定长度最大区间和",window_tags+["数组","数学"],"maximumFixedWindowSum",[("nums","int[]"),("k","int")],"long","返回长度恰好为 k 的连续子数组最大元素和。","维护固定长度窗口和，每次只加入新元素并移除最左元素。",lambda nums,k:max_fixed_sum(nums,k),[{"nums":[1,4,2,10,2,3,1,0,20],"k":4},{"nums":[2,3],"k":2},{"nums":[-1,-2,-3],"k":2},{"nums":[5],"k":1},{"nums":[1,2,3,4],"k":3}],WINDOW)
add_problem("sliding_window","乘积小于 K 的子数组",window_tags+["数组","双指针"],"countProductLessThanK",[("nums","int[]"),("k","int")],"long","数组元素为正数，统计乘积严格小于 k 的连续子数组数量。","维护窗口乘积，过大时从左端除去元素。",lambda nums,k:product_less_than(nums,k),[{"nums":[10,5,2,6],"k":100},{"nums":[1,2,3],"k":0},{"nums":[1,1,1],"k":2},{"nums":[5],"k":6},{"nums":[2,3,4],"k":10}],WINDOW)
add_problem("sliding_window","恰含 K 个奇数的子数组",window_tags+["数组","数学"],"countNiceSubarrays",[("nums","int[]"),("k","int")],"long","统计恰好包含 k 个奇数的连续子数组数量。","恰好 k 等于至多 k 减去至多 k-1，可分别用滑动窗口计算。",lambda nums,k:at_most_odds(nums,k)-at_most_odds(nums,k-1),[{"nums":[1,1,2,1,1],"k":3},{"nums":[2,4,6],"k":1},{"nums":[2,2,2,1,2,2,1,2,2,2],"k":2},{"nums":[1],"k":1},{"nums":[1,2,3],"k":1}],WINDOW)
add_problem("sliding_window","连续子数组目标和计数",window_tags+["数组","动态规划"],"countSubarraySum",[("nums","int[]"),("target","int")],"long","数组可含负数，统计元素和恰好等于 target 的连续子数组数量。","扫描前缀和，用哈希表查询当前前缀减 target 出现过多少次。",lambda nums,target:subarray_sum_count(nums,target),[{"nums":[1,1,1],"target":2},{"nums":[1,2,3],"target":3},{"nums":[-1,-1,1],"target":0},{"nums":[0,0,0],"target":0},{"nums":[3,4,7,2,-3,1,4,2],"target":7}],WINDOW)


# Batch 7: sorting, two pointers and ordered-array problems.
def pair_sum_exists(nums,target):
    seen=set()
    for value in nums:
        if target-value in seen:return True
        seen.add(value)
    return False


def count_pair_sum(nums,target):
    counts=Counter();answer=0
    for value in nums:answer+=counts[target-value];counts[value]+=1
    return answer


def closest_pair_gap(nums,target):
    values=sorted(nums);left,right=0,len(values)-1;best=10**30
    while left<right:
        total=values[left]+values[right];best=min(best,abs(total-target))
        if total<target:left+=1
        else:right-=1
    return best


def max_pair_below(nums,limit):
    values=sorted(nums);left,right=0,len(values)-1;best=-1
    while left<right:
        total=values[left]+values[right]
        if total<limit:best=max(best,total);left+=1
        else:right-=1
    return best


def triplet_sum_exists(nums,target):
    values=sorted(nums)
    for i in range(len(values)-2):
        left,right=i+1,len(values)-1
        while left<right:
            total=values[i]+values[left]+values[right]
            if total==target:return True
            if total<target:left+=1
            else:right-=1
    return False


def three_sum_count(nums,target):
    values=sorted(nums);answers=set()
    for i in range(len(values)-2):
        left,right=i+1,len(values)-1
        while left<right:
            total=values[i]+values[left]+values[right]
            if total==target:
                answers.add((values[i],values[left],values[right]));left+=1;right-=1
            elif total<target:left+=1
            else:right-=1
    return len(answers)


def max_container(height):
    left,right,best=0,len(height)-1,0
    while left<right:
        best=max(best,(right-left)*min(height[left],height[right]))
        if height[left]<height[right]:left+=1
        else:right-=1
    return best


def move_zeroes(nums):
    return [value for value in nums if value!=0]+[0]*nums.count(0)


def merge_sorted(first,second):
    answer=[];i=j=0
    while i<len(first) or j<len(second):
        if j==len(second) or (i<len(first) and first[i]<=second[j]):answer.append(first[i]);i+=1
        else:answer.append(second[j]);j+=1
    return answer


def twice_median(first,second):
    values=merge_sorted(first,second);n=len(values)
    return values[n//2]*2 if n%2 else values[n//2-1]+values[n//2]


def max_nonoverlap(intervals):
    answer=0;end=-10**30
    for start,finish in sorted(intervals,key=lambda item:item[1]):
        if start>=end:answer+=1;end=finish
    return answer


def interval_union_length(intervals):
    total=0;current=None
    for start,end in sorted(intervals):
        if current is None or start>current[1]:
            if current:total+=current[1]-current[0]
            current=[start,end]
        else:current[1]=max(current[1],end)
    return total+(current[1]-current[0] if current else 0)


def minimum_arrows(intervals):
    arrows=0;end=None
    for start,finish in sorted(intervals,key=lambda item:item[1]):
        if end is None or start>end:arrows+=1;end=finish
    return arrows


def inversion_count(nums):
    def sort_count(values):
        if len(values)<2:return values,0
        middle=len(values)//2;left,a=sort_count(values[:middle]);right,b=sort_count(values[middle:]);merged=[];i=j=0;total=a+b
        while i<len(left) and j<len(right):
            if left[i]<=right[j]:merged.append(left[i]);i+=1
            else:merged.append(right[j]);j+=1;total+=len(left)-i
        return merged+left[i:]+right[j:],total
    return sort_count(nums)[1]


def min_swaps_sorted(nums):
    indexed=sorted((value,index) for index,value in enumerate(nums));seen=[False]*len(nums);answer=0
    for i in range(len(nums)):
        if seen[i] or indexed[i][1]==i:continue
        size=0;j=i
        while not seen[j]:seen[j]=True;j=indexed[j][1];size+=1
        answer+=size-1
    return answer


SORT="- `2 ≤ n ≤ 10^5`\n- 整数在 32 位有符号范围内\n- 区间均满足 `start ≤ end`"
sort_tags=["数组","排序","双指针"]
add_problem("sorting_two_pointers","数组目标二元组存在性",sort_tags+["哈希表"],"hasPairWithSum",[("nums","int[]"),("target","int")],"boolean","判断数组中是否存在两个不同位置的元素之和等于 target。","可用哈希集合线性查找补数，也可排序后双指针。",lambda nums,target:pair_sum_exists(nums,target),[{"nums":[2,7,11,15],"target":9},{"nums":[1,2,3],"target":8},{"nums":[3,3],"target":6},{"nums":[-2,5,1],"target":3},{"nums":[0,0],"target":0}],SORT)
add_problem("sorting_two_pointers","目标和下标对数量",sort_tags+["哈希表","数学"],"countPairsWithSum",[("nums","int[]"),("target","int")],"long","统计 i<j 且 nums[i]+nums[j]=target 的下标对数量。","扫描数组，用频率表统计此前出现过多少个补数。",lambda nums,target:count_pair_sum(nums,target),[{"nums":[1,1,1,1],"target":2},{"nums":[1,5,7,-1,5],"target":6},{"nums":[1,2,3],"target":9},{"nums":[0,0,0],"target":0},{"nums":[-2,-1,1,2],"target":0}],SORT)
add_problem("sorting_two_pointers","最接近目标的二元和差",sort_tags+["数学"],"closestPairDifference",[("nums","int[]"),("target","int")],"int","从数组选择两个不同元素，返回它们的和与 target 的最小绝对差。","排序后根据当前和与目标的关系移动左右指针。",lambda nums,target:closest_pair_gap(nums,target),[{"nums":[-1,2,1,-4],"target":4},{"nums":[1,2],"target":8},{"nums":[0,0,0],"target":1},{"nums":[5,-2,3],"target":0},{"nums":[10,20,30],"target":45}],SORT)
add_problem("sorting_two_pointers","小于上限的最大二元和",sort_tags,"maximumPairBelow",[("nums","int[]"),("limit","int")],"int","返回严格小于 limit 的最大二元和；不存在合法数对时返回 -1。","排序后用双指针缩小搜索范围。",lambda nums,limit:max_pair_below(nums,limit),[{"nums":[34,23,1,24,75,33,54,8],"limit":60},{"nums":[10,20,30],"limit":15},{"nums":[-5,-2,4],"limit":0},{"nums":[1,2],"limit":4},{"nums":[5,5,5],"limit":11}],SORT)
add_problem("sorting_two_pointers","数组目标三元组存在性",sort_tags,"hasTripletWithSum",[("nums","int[]"),("target","int")],"boolean","判断数组中是否存在三个不同位置的元素之和等于 target。","排序后枚举第一个元素，对剩余区间使用双指针。",lambda nums,target:triplet_sum_exists(nums,target),[{"nums":[12,3,4,1,6,9],"target":24},{"nums":[1,2,3],"target":7},{"nums":[0,0,0],"target":0},{"nums":[-1,0,1,2],"target":0},{"nums":[5,5,5,5],"target":15}],SORT)
add_problem("sorting_two_pointers","不同目标三元组数量",sort_tags+["哈希表"],"countDistinctTriplets",[("nums","int[]"),("target","int")],"int","统计值组合不同且元素和为 target 的三元组数量。","排序、枚举首元素并用双指针，同时跳过重复值。",lambda nums,target:three_sum_count(nums,target),[{"nums":[-1,0,1,2,-1,-4],"target":0},{"nums":[0,0,0,0],"target":0},{"nums":[1,2,3],"target":9},{"nums":[1,1,2,2,3,3],"target":6},{"nums":[-2,0,1,1,2],"target":0}],SORT)
add_problem("sorting_two_pointers","盛水容器最大容量",sort_tags+["数学"],"maxContainerArea",[("height","int[]")],"long","竖线高度由数组给出，选择两条线与底边构成容器，返回最大容量。","从两端开始，移动较矮的一侧才可能提高容量。",lambda height:max_container(height),[{"height":[1,8,6,2,5,4,8,3,7]},{"height":[1,1]},{"height":[4,3,2,1,4]},{"height":[1,2,1]},{"height":[2,3,4,5,18,17,6]}],SORT)
add_problem("sorting_two_pointers","有序数组平方排序",sort_tags,"sortedSquares",[("nums","int[]")],"int[]","给定非递减数组，返回每个元素平方后的非递减序列。","最大平方来自两端，双指针从结果尾部向前填写。",lambda nums:sorted(value*value for value in nums),[{"nums":[-4,-1,0,3,10]},{"nums":[-7,-3,2,3,11]},{"nums":[0]},{"nums":[-2,-1]},{"nums":[1,2,3]}],SORT)
add_problem("sorting_two_pointers","稳定移动所有零",sort_tags,"moveZeroesStable",[("nums","int[]")],"int[]","保持非零元素相对顺序，将所有 0 移到数组末尾。","使用写指针依次放置非零元素，剩余位置补零。",lambda nums:move_zeroes(nums),[{"nums":[0,1,0,3,12]},{"nums":[0]},{"nums":[1,2]},{"nums":[0,0,1]},{"nums":[1,0,2,0,3]}],SORT)
add_problem("sorting_two_pointers","有序数组去重长度",sort_tags,"deduplicatedLength",[("nums","int[]")],"int","给定非递减数组，返回去重后的元素数量。","读指针扫描，写指针仅在值变化时前进。",lambda nums:len(set(nums)),[{"nums":[1,1,2]},{"nums":[0,0,1,1,1,2,2,3,3,4]},{"nums":[1]},{"nums":[-1,-1,-1]},{"nums":[1,2,3]}],SORT)
add_problem("sorting_two_pointers","合并两组有序数据",sort_tags,"mergeTwoSortedArrays",[("first","int[]"),("second","int[]")],"int[]","合并两个非递减数组并返回新的非递减数组。","双指针比较两个数组当前元素，每次取较小者。",lambda first,second:merge_sorted(first,second),[{"first":[1,2,4],"second":[1,3,4]},{"first":[],"second":[1]},{"first":[2],"second":[]},{"first":[-3,0],"second":[-2,5]},{"first":[1,1],"second":[1,1]}],SORT)
add_problem("sorting_two_pointers","两个数组的唯一交集",sort_tags+["哈希表"],"uniqueIntersection",[("first","int[]"),("second","int[]")],"int[]","返回两个数组共同出现的不同元素，结果按升序排列。","可用两个集合求交，也可排序后双指针并跳过重复值。",lambda first,second:sorted(set(first)&set(second)),[{"first":[1,2,2,1],"second":[2,2]},{"first":[4,9,5],"second":[9,4,9,8,4]},{"first":[],"second":[1]},{"first":[1,1],"second":[2,2]},{"first":[-1,0,1],"second":[0,2]}],SORT)
add_problem("sorting_two_pointers","两有序数组中位数的两倍",sort_tags+["二分查找","数学"],"twiceMedian",[("first","int[]"),("second","int[]")],"int","两个非递减数组总长度至少为 1，返回合并后中位数的两倍，避免小数表示。","可二分划分两个数组；直接归并也能得到答案。",lambda first,second:twice_median(first,second),[{"first":[1,3],"second":[2]},{"first":[1,2],"second":[3,4]},{"first":[],"second":[1]},{"first":[0,0],"second":[0,0]},{"first":[-5,-1],"second":[2,8,10]}],SORT)
add_problem("sorting_two_pointers","两有序数组第 K 小元素",sort_tags+["二分查找"],"kthInSortedArrays",[("first","int[]"),("second","int[]"),("k","int")],"int","返回两个非递减数组合并序列中的第 k 小元素，k 从 1 开始。","比较两个数组的第 k/2 个候选并排除不可能的一段。",lambda first,second,k:merge_sorted(first,second)[k-1],[{"first":[1,3,5],"second":[2,4,6],"k":4},{"first":[],"second":[1,2],"k":1},{"first":[1],"second":[],"k":1},{"first":[1,1],"second":[1,1],"k":3},{"first":[-3,0,7],"second":[-2,5],"k":2}],SORT)
add_problem("sorting_two_pointers","最多不重叠区间数量",sort_tags,"maximumNonOverlappingIntervals",[("intervals","int[][]")],"int","选择尽可能多的两两不重叠半开区间 [start,end)，返回最大数量。","按结束位置升序排序，优先选择最早结束且与已选区间不重叠的区间。",lambda intervals:max_nonoverlap(intervals),[{"intervals":[[1,2],[2,3],[3,4],[1,3]]},{"intervals":[[1,2],[1,2],[1,2]]},{"intervals":[[1,2]]},{"intervals":[[1,5],[2,3],[3,4]]},{"intervals":[[-3,-1],[-1,1],[0,2]]}],SORT)
add_problem("sorting_two_pointers","区间并集总长度",sort_tags+["数学"],"intervalUnionLength",[("intervals","int[][]")],"long","给定若干闭区间端点，按连续长度计算它们并集的总长度。端点接触不会产生额外长度。","按起点排序并合并所有重叠区间，累加合并后的长度。",lambda intervals:interval_union_length(intervals),[{"intervals":[[1,3],[2,6],[8,10]]},{"intervals":[[1,4],[4,5]]},{"intervals":[[0,0]]},{"intervals":[[-3,-1],[1,3]]},{"intervals":[[1,10],[2,3]]}],SORT)
add_problem("sorting_two_pointers","刺破所有区间的最少点数",sort_tags,"minimumIntervalArrows",[("intervals","int[][]")],"int","每个闭区间都必须包含至少一个选定点，返回覆盖全部区间所需的最少点数。","按右端点排序，在当前区间不包含上一个点时选择其右端点。",lambda intervals:minimum_arrows(intervals),[{"intervals":[[10,16],[2,8],[1,6],[7,12]]},{"intervals":[[1,2],[3,4],[5,6]]},{"intervals":[[1,2]]},{"intervals":[[1,2],[2,3]]},{"intervals":[[-3,-1],[-2,2],[1,4]]}],SORT)
add_problem("sorting_two_pointers","数组逆序对数量",sort_tags+["递归","动态规划"],"countInversions",[("nums","int[]")],"long","统计 i<j 且 nums[i]>nums[j] 的下标对数量。","归并排序合并两段时，右侧较小元素会与左侧剩余全部元素形成逆序对。",lambda nums:inversion_count(nums),[{"nums":[2,4,1,3,5]},{"nums":[5,4,3,2,1]},{"nums":[1]},{"nums":[1,1,1]},{"nums":[1,3,2,3,1]}],SORT)
add_problem("sorting_two_pointers","三色数组原地排序结果",sort_tags,"sortThreeColors",[("nums","int[]")],"int[]","数组只含 0、1、2，返回按 0、1、2 顺序排列的结果。","荷兰国旗算法用三个指针划分已排好、待处理和尾部区域。",lambda nums:sorted(nums),[{"nums":[2,0,2,1,1,0]},{"nums":[2,0,1]},{"nums":[0]},{"nums":[1,1,1]},{"nums":[2,2,0,0]}],SORT)
add_problem("sorting_two_pointers","不同元素数组排序最少交换",sort_tags+["哈希表"],"minimumSwapsToSort",[("nums","int[]")],"int","数组元素互不相同，每次可交换任意两个位置，返回升序排列所需的最少交换次数。","排序后得到位置置换，每个长度为 m 的置换环需要 m-1 次交换。",lambda nums:min_swaps_sorted(nums),[{"nums":[4,3,2,1]},{"nums":[1,5,4,3,2]},{"nums":[1]},{"nums":[1,2,3]},{"nums":[10,-1,3,2]}],SORT)


def validate_catalog() -> dict:
    assert len(PROBLEMS) == 142, len(PROBLEMS)
    assert {name: len(BATCHES[name]) for name in BATCH_NAMES} == {
        "linked_list": 20, "binary_tree": 20, "matrix_backtracking": 22,
        "bit_math": 20, "monotonic_stack": 20, "sliding_window": 20,
        "sorting_two_pointers": 20,
    }
    assert len({problem["title"] for problem in PROBLEMS}) == len(PROBLEMS)
    assert len({problem["spec"]["methodName"] for problem in PROBLEMS}) == len(PROBLEMS)
    for problem in PROBLEMS:
        assert 1 <= len(problem["title"]) <= 80
        assert 1 <= len(problem["content"]) <= 8192
        assert len(problem["acm"]) >= 5
        assert len(problem["spec"]["judgeCases"]) >= 5
        assert problem["spec"]["returnType"] in SUPPORTED_TYPES
        assert any(case["isHidden"] is False for case in problem["acm"])
        for parameter in problem["spec"]["parameters"]:
            assert parameter["type"] in SUPPORTED_TYPES
        for case in problem["spec"]["judgeCases"]:
            arguments = json.loads(case["input"])
            expected = json.loads(case["output"])
            names = [parameter["name"] for parameter in problem["spec"]["parameters"]]
            actual = problem["solver"](*(arguments[name] for name in names))
            assert actual == expected, (problem["title"], arguments, expected, actual)

    from generate_algorithm_seed import PROBLEMS as base_problems
    from generate_extended_algorithm_seed import PROBLEMS as extended_problems
    all_problems = [*base_problems, *extended_problems, *PROBLEMS]
    counts = Counter(tag for problem in all_problems for tag in problem["tags"])
    original_tags = {tag for problem in [*base_problems, *extended_problems] for tag in problem["tags"]}
    assert len(all_problems) == 166
    assert set(counts) == original_tags, sorted(set(counts) - original_tags)
    assert min(counts[tag] for tag in original_tags) >= 20, counts
    return {"totalProblems": len(all_problems), "newProblems": len(PROBLEMS), "tagCounts": dict(sorted(counts.items()))}


def generate_batch_sql(batch: str, problems: list[dict], start_number: int) -> str:
    lines = [
        f"-- Generated by deploy/data/generate_hundred_plus_catalog.py ({batch}); do not edit.",
        "SET NAMES utf8mb4;", "START TRANSACTION;",
        "SET @admin_id = (SELECT id FROM user WHERE userAccount='nemo_admin' AND userRole='admin' AND isDelete=0 LIMIT 1);",
    ]
    judge_config = compact_json({"timeLimit": 3000, "memoryLimit": 262144, "outputLimitBytes": 1048576})
    for offset, problem in enumerate(problems):
        problem_no = start_number + offset
        values = [
            sql_string(problem["title"]), sql_string(problem["content"].strip()),
            sql_string(compact_json(problem["tags"])), sql_string(problem["answer"]),
            "0", "0", sql_string(compact_json(problem["acm"])), sql_string(judge_config),
            sql_string(compact_json(problem["spec"])), "1", "1", "0", "0", "@admin_id", "0", "0", str(problem_no),
        ]
        lines.extend([
            f"-- {problem_no}. {problem['title']}",
            "INSERT INTO question (title,content,tags,answer,submitNum,acceptedNum,judgeCase,judgeConfig,problemSpec,problemVersion,isPublished,thumbNum,favourNum,userId,isDelete,rowVersion,problemNo)",
            "SELECT " + ",".join(values) + f" FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question_public_number WHERE problemNo={problem_no});",
            "SET @qid = IF(ROW_COUNT()=1,LAST_INSERT_ID(),NULL);",
            "INSERT INTO question_version (questionId,version,title,content,tags,answer,judgeCase,judgeConfig,problemSpec,harnessVersion,publishedBy)",
            "SELECT id,1,title,content,tags,answer,judgeCase,judgeConfig,problemSpec,1,@admin_id FROM question WHERE id=@qid;",
            "INSERT INTO question_public_number (problemNo,questionId,currentQuestionId,status,lifecycleVersion,lastOperatorId,offlineReason)",
            f"SELECT {problem_no},@qid,@qid,'ONLINE',1,@admin_id,NULL FROM DUAL WHERE @qid IS NOT NULL;",
        ])
    lines.extend(["COMMIT;", ""])
    return "\n".join(lines)


def main() -> None:
    from catalog_quality_review import apply_reviewed_cases
    apply_reviewed_cases(PROBLEMS,25)
    summary = validate_catalog()
    directory = Path(__file__).parent
    combined = []
    manifest_problems = []
    next_number = 25
    for batch_index, batch in enumerate(BATCH_NAMES, start=1):
        problems = BATCHES[batch]
        sql = generate_batch_sql(batch, problems, next_number)
        target = directory / f"catalog_batch_{batch_index:02d}_{batch}.sql"
        target.write_text(sql, encoding="utf-8", newline="\n")
        combined.append(sql)
        for offset, problem in enumerate(problems):
            manifest_problems.append({
                "problemNo": next_number + offset, "title": problem["title"],
                "tags": problem["tags"], "methodName": problem["spec"]["methodName"],
            })
        next_number += len(problems)
    (directory / "catalog_expansion_all.sql").write_text("\n".join(combined), encoding="utf-8", newline="\n")
    (directory / "catalog_expansion_manifest.json").write_text(
        json.dumps({**summary, "problems": manifest_problems}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
