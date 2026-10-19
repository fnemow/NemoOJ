#!/usr/bin/env python3
"""Generate the additive linked-list, tree, and advanced-problem catalog."""

from __future__ import annotations

import json
from bisect import bisect_left
from collections import deque
from pathlib import Path

from generate_algorithm_seed import acm_case, compact_json, function_case, spec, sql_string


PROBLEMS = [
    {
        "title": "反转链表",
        "tags": ["简单", "链表", "递归"],
        "content": """## 题目描述

给定一个单链表的头节点 `head`，反转链表并返回新的头节点。

## ACM 模式输入

第一行是节点数 `n`，第二行是按链表顺序给出的 `n` 个整数。

## ACM 模式输出

输出反转后的链表，各节点值以空格分隔。

## 核心代码模式

实现 `Solution.reverseList(head: list_node<int>) -> list_node<int>`。

## 数据范围

- `1 ≤ n ≤ 10^5`
- `-10^4 ≤ Node.val ≤ 10^4`
""",
        "answer": "使用三个指针依次保存前驱、当前节点和后继节点，逐个改变 next 指向。时间复杂度 O(n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("5\n1 2 3 4 5\n", "5 4 3 2 1\n", False),
            acm_case("2\n1 2\n", "2 1\n", False),
            acm_case("1\n42\n", "42\n"),
            acm_case("4\n-1 0 3 5\n", "5 3 0 -1\n"),
            acm_case("6\n1 1 2 3 5 8\n", "8 5 3 2 1 1\n"),
        ],
        "spec": spec("reverseList", [("head", "list_node<int>")], "list_node<int>", [
            function_case({"head": [1, 2, 3, 4, 5]}, [5, 4, 3, 2, 1], False),
            function_case({"head": [1, 2]}, [2, 1], False),
            function_case({"head": [42]}, [42]),
            function_case({"head": [-1, 0, 3, 5]}, [5, 3, 0, -1]),
            function_case({"head": [1, 1, 2, 3, 5, 8]}, [8, 5, 3, 2, 1, 1]),
        ]),
    },
    {
        "title": "合并两个有序链表",
        "tags": ["简单", "链表", "双指针"],
        "content": """## 题目描述

给定两个按非递减顺序排列的单链表，将它们合并为一个新的非递减链表并返回头节点。

## ACM 模式输入

第一行包含两个链表的长度 `n` 和 `m`，第二、三行分别给出两个链表的节点值。

## ACM 模式输出

输出合并后的链表。

## 核心代码模式

实现 `Solution.mergeTwoLists(list1: list_node<int>, list2: list_node<int>) -> list_node<int>`。

## 数据范围

- `1 ≤ n,m ≤ 5×10^4`
- 两个链表均按非递减顺序排列
""",
        "answer": "使用哑节点保存结果头部，比较两个当前节点并连接较小者，最后连接尚未遍历完的链表。时间复杂度 O(n+m)。",
        "acm": [
            acm_case("3 3\n1 2 4\n1 3 4\n", "1 1 2 3 4 4\n", False),
            acm_case("3 3\n-10 -3 0\n-5 2 7\n", "-10 -5 -3 0 2 7\n", False),
            acm_case("1 1\n1\n2\n", "1 2\n"),
            acm_case("3 2\n1 1 1\n1 1\n", "1 1 1 1 1\n"),
            acm_case("3 3\n-2 4 9\n-3 5 10\n", "-3 -2 4 5 9 10\n"),
        ],
        "spec": spec("mergeTwoLists", [("list1", "list_node<int>"), ("list2", "list_node<int>")], "list_node<int>", [
            function_case({"list1": [1, 2, 4], "list2": [1, 3, 4]}, [1, 1, 2, 3, 4, 4], False),
            function_case({"list1": [-10, -3, 0], "list2": [-5, 2, 7]}, [-10, -5, -3, 0, 2, 7], False),
            function_case({"list1": [1], "list2": [2]}, [1, 2]),
            function_case({"list1": [1, 1, 1], "list2": [1, 1]}, [1, 1, 1, 1, 1]),
            function_case({"list1": [-2, 4, 9], "list2": [-3, 5, 10]}, [-3, -2, 4, 5, 9, 10]),
        ]),
    },
    {
        "title": "K 个一组翻转链表",
        "tags": ["困难", "链表", "递归"],
        "content": """## 题目描述

给定一个单链表，每 `k` 个节点一组进行翻转，返回翻转后的链表。如果最后剩余节点不足 `k` 个，则保持原有顺序。不能只交换节点值。

## ACM 模式输入

第一行包含节点数 `n` 和分组大小 `k`，第二行包含链表节点值。

## ACM 模式输出

输出处理后的链表。

## 核心代码模式

实现 `Solution.reverseKGroup(head: list_node<int>, k: int) -> list_node<int>`。

## 数据范围

- `1 ≤ k ≤ n ≤ 10^5`
""",
        "answer": "每次先确认剩余节点不少于 k 个，再原地反转当前区间，并把上一组尾节点连接到新头部。时间复杂度 O(n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("5 2\n1 2 3 4 5\n", "2 1 4 3 5\n", False),
            acm_case("5 3\n1 2 3 4 5\n", "3 2 1 4 5\n", False),
            acm_case("2 2\n1 2\n", "2 1\n"),
            acm_case("3 1\n1 2 3\n", "1 2 3\n"),
            acm_case("6 4\n1 2 3 4 5 6\n", "4 3 2 1 5 6\n"),
        ],
        "spec": spec("reverseKGroup", [("head", "list_node<int>"), ("k", "int")], "list_node<int>", [
            function_case({"head": [1, 2, 3, 4, 5], "k": 2}, [2, 1, 4, 3, 5], False),
            function_case({"head": [1, 2, 3, 4, 5], "k": 3}, [3, 2, 1, 4, 5], False),
            function_case({"head": [1, 2], "k": 2}, [2, 1]),
            function_case({"head": [1, 2, 3], "k": 1}, [1, 2, 3]),
            function_case({"head": [1, 2, 3, 4, 5, 6], "k": 4}, [4, 3, 2, 1, 5, 6]),
        ]),
    },
    {
        "title": "二叉树的最大深度",
        "tags": ["简单", "二叉树", "深度优先搜索", "广度优先搜索"],
        "content": """## 题目描述

给定一棵二叉树，返回从根节点到最远叶子节点的最长路径上的节点数。

## ACM 模式输入

第一行是层序序列的元素数 `n`，第二行给出层序序列；空节点使用 `null`。

## ACM 模式输出

输出二叉树的最大深度。

## 核心代码模式

实现 `Solution.maxDepth(root: tree_node<int>) -> int`。

## 数据范围

- `1 ≤ 节点数 ≤ 10^4`
""",
        "answer": "递归深度等于左右子树深度较大值加一；也可使用层序遍历统计层数。时间复杂度 O(n)。",
        "acm": [
            acm_case("7\n3 9 20 null null 15 7\n", "3\n", False),
            acm_case("3\n1 null 2\n", "2\n", False),
            acm_case("1\n1\n", "1\n"),
            acm_case("5\n1 2 3 4 5\n", "3\n"),
            acm_case("6\n1 2 null 3 null 4\n", "4\n"),
        ],
        "spec": spec("maxDepth", [("root", "tree_node<int>")], "int", [
            function_case({"root": [3, 9, 20, None, None, 15, 7]}, 3, False),
            function_case({"root": [1, None, 2]}, 2, False),
            function_case({"root": [1]}, 1),
            function_case({"root": [1, 2, 3, 4, 5]}, 3),
            function_case({"root": [1, 2, None, 3, None, 4]}, 4),
        ]),
    },
    {
        "title": "二叉树的中序遍历",
        "tags": ["简单", "二叉树", "栈", "递归"],
        "content": """## 题目描述

给定一棵二叉树，按照“左子树、根节点、右子树”的顺序返回所有节点值。

## ACM 模式输入

第一行是层序序列长度 `n`，第二行给出层序序列，空节点使用 `null`。

## ACM 模式输出

输出中序遍历结果。

## 核心代码模式

实现 `Solution.inorderTraversal(root: tree_node<int>) -> int[]`。

## 数据范围

- `1 ≤ 节点数 ≤ 10^4`
""",
        "answer": "可以递归访问左子树、根、右子树；迭代解法使用栈不断压入左链，再弹出节点并转向其右子树。时间复杂度 O(n)。",
        "acm": [
            acm_case("4\n1 null 2 3\n", "1 3 2\n", False),
            acm_case("1\n1\n", "1\n", False),
            acm_case("5\n1 2 3 4 5\n", "4 2 5 1 3\n"),
            acm_case("7\n5 3 8 1 4 7 9\n", "1 3 4 5 7 8 9\n"),
            acm_case("3\n2 1 3\n", "1 2 3\n"),
        ],
        "spec": spec("inorderTraversal", [("root", "tree_node<int>")], "int[]", [
            function_case({"root": [1, None, 2, 3]}, [1, 3, 2], False),
            function_case({"root": [1]}, [1], False),
            function_case({"root": [1, 2, 3, 4, 5]}, [4, 2, 5, 1, 3]),
            function_case({"root": [5, 3, 8, 1, 4, 7, 9]}, [1, 3, 4, 5, 7, 8, 9]),
            function_case({"root": [2, 1, 3]}, [1, 2, 3]),
        ]),
    },
    {
        "title": "验证二叉搜索树",
        "tags": ["中等", "二叉树", "深度优先搜索"],
        "content": """## 题目描述

判断一棵二叉树是否为有效二叉搜索树。每个节点必须严格大于左子树中的所有节点，并严格小于右子树中的所有节点。

## ACM 模式输入

第一行是层序序列长度 `n`，第二行给出层序序列，空节点使用 `null`。

## ACM 模式输出

有效输出 `true`，否则输出 `false`。

## 核心代码模式

实现 `Solution.isValidBST(root: tree_node<int>) -> boolean`。

## 数据范围

- `1 ≤ 节点数 ≤ 10^4`
""",
        "answer": "递归时为每个节点维护允许的开区间，或检查中序遍历结果是否严格递增。边界应使用 long，避免整数极值溢出。",
        "acm": [
            acm_case("3\n2 1 3\n", "true\n", False),
            acm_case("7\n5 1 4 null null 3 6\n", "false\n", False),
            acm_case("1\n1\n", "true\n"),
            acm_case("7\n5 3 7 2 4 6 8\n", "true\n"),
            acm_case("7\n5 3 7 null null 4 8\n", "false\n"),
        ],
        "spec": spec("isValidBST", [("root", "tree_node<int>")], "boolean", [
            function_case({"root": [2, 1, 3]}, True, False),
            function_case({"root": [5, 1, 4, None, None, 3, 6]}, False, False),
            function_case({"root": [1]}, True),
            function_case({"root": [5, 3, 7, 2, 4, 6, 8]}, True),
            function_case({"root": [5, 3, 7, None, None, 4, 8]}, False),
        ]),
    },
    {
        "title": "接雨水",
        "tags": ["困难", "数组", "双指针", "单调栈"],
        "content": """## 题目描述

给定 `n` 个非负整数表示宽度为 1 的柱子高度，计算下雨后这些柱子之间能够接住的雨水总量。

## ACM 模式输入

第一行是 `n`，第二行是 `n` 个柱子高度。

## ACM 模式输出

输出可接雨水总量。

## 核心代码模式

实现 `Solution.trap(height: int[]) -> int`。

## 数据范围

- `1 ≤ n ≤ 2×10^4`
- `0 ≤ height[i] ≤ 10^5`
""",
        "answer": "使用左右指针并维护两侧最大高度。较低一侧当前可接水量已经确定，移动该侧指针即可。时间复杂度 O(n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("12\n0 1 0 2 1 0 1 3 2 1 2 1\n", "6\n", False),
            acm_case("6\n4 2 0 3 2 5\n", "9\n", False),
            acm_case("1\n1\n", "0\n"),
            acm_case("3\n2 0 2\n", "2\n"),
            acm_case("4\n5 4 1 2\n", "1\n"),
        ],
        "spec": spec("trap", [("height", "int[]")], "int", [
            function_case({"height": [0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1]}, 6, False),
            function_case({"height": [4, 2, 0, 3, 2, 5]}, 9, False),
            function_case({"height": [1]}, 0),
            function_case({"height": [2, 0, 2]}, 2),
            function_case({"height": [5, 4, 1, 2]}, 1),
        ]),
    },
    {
        "title": "柱状图中最大的矩形",
        "tags": ["困难", "数组", "单调栈"],
        "content": """## 题目描述

给定各柱子的高度 `heights`，每根柱子宽度均为 1，求柱状图中能够形成的最大矩形面积。

## ACM 模式输入

第一行是柱子数量 `n`，第二行是 `n` 个高度。

## ACM 模式输出

输出最大矩形面积。

## 核心代码模式

实现 `Solution.largestRectangleArea(heights: int[]) -> int`。

## 数据范围

- `1 ≤ n ≤ 10^5`
- `0 ≤ heights[i] ≤ 10^4`
""",
        "answer": "维护高度单调递增的下标栈。遇到更矮柱子时不断弹栈，以被弹高度为矩形高度计算其最大宽度。时间复杂度 O(n)。",
        "acm": [
            acm_case("6\n2 1 5 6 2 3\n", "10\n", False),
            acm_case("2\n2 4\n", "4\n", False),
            acm_case("1\n1\n", "1\n"),
            acm_case("3\n2 2 2\n", "6\n"),
            acm_case("7\n6 2 5 4 5 1 6\n", "12\n"),
        ],
        "spec": spec("largestRectangleArea", [("heights", "int[]")], "int", [
            function_case({"heights": [2, 1, 5, 6, 2, 3]}, 10, False),
            function_case({"heights": [2, 4]}, 4, False),
            function_case({"heights": [1]}, 1),
            function_case({"heights": [2, 2, 2]}, 6),
            function_case({"heights": [6, 2, 5, 4, 5, 1, 6]}, 12),
        ]),
    },
    {
        "title": "编辑距离",
        "tags": ["困难", "字符串", "动态规划"],
        "content": """## 题目描述

给定两个字符串 `word1` 和 `word2`，每次可以插入、删除或替换一个字符，求将 `word1` 转换成 `word2` 所需的最少操作数。

## ACM 模式输入

输入两行，分别是 `word1` 和 `word2`；字符串可能为空。

## ACM 模式输出

输出最少操作数。

## 核心代码模式

实现 `Solution.minDistance(word1: string, word2: string) -> int`。

## 数据范围

- `0 ≤ |word1|,|word2| ≤ 500`
""",
        "answer": "设 dp[i][j] 为两个前缀的编辑距离。末尾字符相同则继承左上状态，否则在插入、删除、替换三个状态的最小值上加一。时间复杂度 O(nm)。",
        "acm": [
            acm_case("horse\nros\n", "3\n", False),
            acm_case("intention\nexecution\n", "5\n", False),
            acm_case("a\na\n", "0\n"),
            acm_case("abc\n\n", "3\n"),
            acm_case("kitten\nsitting\n", "3\n"),
        ],
        "spec": spec("minDistance", [("word1", "string"), ("word2", "string")], "int", [
            function_case({"word1": "horse", "word2": "ros"}, 3, False),
            function_case({"word1": "intention", "word2": "execution"}, 5, False),
            function_case({"word1": "a", "word2": "a"}, 0),
            function_case({"word1": "abc", "word2": ""}, 3),
            function_case({"word1": "kitten", "word2": "sitting"}, 3),
        ]),
    },
    {
        "title": "最小覆盖子串",
        "tags": ["困难", "字符串", "哈希表", "滑动窗口"],
        "content": """## 题目描述

给定字符串 `s` 和 `t`，返回 `s` 中包含 `t` 全部字符（包括重复字符）的最短子串。测试数据保证存在唯一答案。

## ACM 模式输入

输入两行，分别是 `s` 和 `t`。

## ACM 模式输出

输出最小覆盖子串。

## 核心代码模式

实现 `Solution.minWindow(s: string, t: string) -> string`。

## 数据范围

- `1 ≤ |t| ≤ |s| ≤ 10^5`
""",
        "answer": "用计数表记录窗口仍缺少的字符。右指针扩张直到覆盖 t，再移动左指针压缩并更新最短答案。时间复杂度 O(|s|+|t|)。",
        "acm": [
            acm_case("ADOBECODEBANC\nABC\n", "BANC\n", False),
            acm_case("a\na\n", "a\n", False),
            acm_case("aa\naa\n", "aa\n"),
            acm_case("aa\na\n", "a\n"),
            acm_case("cabwefgewcwaefgcf\ncae\n", "cwae\n"),
        ],
        "spec": spec("minWindow", [("s", "string"), ("t", "string")], "string", [
            function_case({"s": "ADOBECODEBANC", "t": "ABC"}, "BANC", False),
            function_case({"s": "a", "t": "a"}, "a", False),
            function_case({"s": "aa", "t": "aa"}, "aa"),
            function_case({"s": "aa", "t": "a"}, "a"),
            function_case({"s": "cabwefgewcwaefgcf", "t": "cae"}, "cwae"),
        ]),
    },
    {
        "title": "正则表达式匹配",
        "tags": ["困难", "字符串", "动态规划", "递归"],
        "content": """## 题目描述

实现支持 `.` 和 `*` 的正则表达式匹配：`.` 匹配任意单个字符，`*` 匹配前一个元素零次或多次。匹配必须覆盖整个字符串。

## ACM 模式输入

输入两行，分别为字符串 `s` 和模式 `p`。

## ACM 模式输出

匹配成功输出 `true`，否则输出 `false`。

## 核心代码模式

实现 `Solution.isMatch(s: string, p: string) -> boolean`。

## 数据范围

- `1 ≤ |s| ≤ 20`
- `1 ≤ |p| ≤ 30`
- `*` 前保证存在有效字符
""",
        "answer": "使用记忆化搜索或二维动态规划。遇到下一个字符为星号时，可以跳过该模式元素，或在当前字符匹配时继续使用它。时间复杂度 O(|s||p|)。",
        "acm": [
            acm_case("aa\na\n", "false\n", False),
            acm_case("aa\na*\n", "true\n", False),
            acm_case("ab\n.*\n", "true\n"),
            acm_case("aab\nc*a*b\n", "true\n"),
            acm_case("mississippi\nmis*is*p*.\n", "false\n"),
        ],
        "spec": spec("isMatch", [("s", "string"), ("p", "string")], "boolean", [
            function_case({"s": "aa", "p": "a"}, False, False),
            function_case({"s": "aa", "p": "a*"}, True, False),
            function_case({"s": "ab", "p": ".*"}, True),
            function_case({"s": "aab", "p": "c*a*b"}, True),
            function_case({"s": "mississippi", "p": "mis*is*p*."}, False),
        ]),
    },
    {
        "title": "N 皇后 II",
        "tags": ["困难", "回溯", "位运算"],
        "content": """## 题目描述

在 `n × n` 棋盘上放置 `n` 个皇后，使任意两个皇后都不在同一行、同一列或同一条对角线上，返回不同摆放方案的数量。

## ACM 模式输入

输入一个整数 `n`。

## ACM 模式输出

输出不同方案数。

## 核心代码模式

实现 `Solution.totalNQueens(n: int) -> int`。

## 数据范围

- `1 ≤ n ≤ 12`
""",
        "answer": "逐行回溯，用列集合和两组对角线集合判断冲突。进一步可以用位掩码表示可放置位置，加速状态枚举。",
        "acm": [
            acm_case("1\n", "1\n", False),
            acm_case("4\n", "2\n", False),
            acm_case("5\n", "10\n"),
            acm_case("8\n", "92\n"),
            acm_case("9\n", "352\n"),
        ],
        "spec": spec("totalNQueens", [("n", "int")], "int", [
            function_case({"n": 1}, 1, False),
            function_case({"n": 4}, 2, False),
            function_case({"n": 5}, 10),
            function_case({"n": 8}, 92),
            function_case({"n": 9}, 352),
        ]),
    },
]


def reverse_groups(head, k):
    answer = []
    for index in range(0, len(head), k):
        group = head[index:index + k]
        answer.extend(reversed(group) if len(group) == k else group)
    return answer


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


def tree_depth(root):
    return 0 if root is None else 1 + max(tree_depth(root[1]), tree_depth(root[2]))


def tree_inorder(root):
    return [] if root is None else tree_inorder(root[1]) + [root[0]] + tree_inorder(root[2])


def valid_bst(root, low=-10**30, high=10**30):
    return root is None or (low < root[0] < high and valid_bst(root[1], low, root[0]) and valid_bst(root[2], root[0], high))


def trap(height):
    left, right, left_max, right_max, answer = 0, len(height) - 1, 0, 0, 0
    while left <= right:
        if left_max <= right_max:
            left_max = max(left_max, height[left])
            answer += left_max - height[left]
            left += 1
        else:
            right_max = max(right_max, height[right])
            answer += right_max - height[right]
            right -= 1
    return answer


def largest_rectangle(heights):
    stack, answer = [], 0
    for index, height in enumerate(heights + [0]):
        while stack and heights[stack[-1]] > height:
            bar = stack.pop()
            width = index if not stack else index - stack[-1] - 1
            answer = max(answer, heights[bar] * width)
        stack.append(index)
    return answer


def edit_distance(word1, word2):
    dp = list(range(len(word2) + 1))
    for row, first in enumerate(word1, start=1):
        previous, dp[0] = dp[0], row
        for column, second in enumerate(word2, start=1):
            above = dp[column]
            dp[column] = previous if first == second else 1 + min(previous, above, dp[column - 1])
            previous = above
    return dp[-1]


def min_window(s, t):
    need = {}
    for character in t:
        need[character] = need.get(character, 0) + 1
    missing, left, best = len(t), 0, (0, len(s) + 1)
    for right, character in enumerate(s, start=1):
        if need.get(character, 0) > 0:
            missing -= 1
        need[character] = need.get(character, 0) - 1
        if missing == 0:
            while need.get(s[left], 0) < 0:
                need[s[left]] += 1
                left += 1
            if right - left < best[1] - best[0]:
                best = (left, right)
            need[s[left]] += 1
            missing += 1
            left += 1
    return s[best[0]:best[1]]


def regex_match(s, p):
    memo = {}
    def match(i, j):
        if (i, j) in memo:
            return memo[i, j]
        if j == len(p):
            answer = i == len(s)
        else:
            first = i < len(s) and p[j] in (s[i], ".")
            answer = match(i, j + 2) or (first and match(i + 1, j)) if j + 1 < len(p) and p[j + 1] == "*" else first and match(i + 1, j + 1)
        memo[i, j] = answer
        return answer
    return match(0, 0)


def queens(n):
    mask = (1 << n) - 1
    def search(columns, diagonals_left, diagonals_right):
        if columns == mask:
            return 1
        available = mask & ~(columns | diagonals_left | diagonals_right)
        total = 0
        while available:
            bit = available & -available
            available -= bit
            total += search(columns | bit, (diagonals_left | bit) << 1 & mask, (diagonals_right | bit) >> 1)
        return total
    return search(0, 0, 0)


SOLVERS = {
    "reverseList": lambda head: list(reversed(head)),
    "mergeTwoLists": lambda list1, list2: sorted(list1 + list2),
    "reverseKGroup": reverse_groups,
    "maxDepth": lambda root: tree_depth(tree_from_level(root)),
    "inorderTraversal": lambda root: tree_inorder(tree_from_level(root)),
    "isValidBST": lambda root: valid_bst(tree_from_level(root)),
    "trap": trap,
    "largestRectangleArea": largest_rectangle,
    "minDistance": edit_distance,
    "minWindow": min_window,
    "isMatch": regex_match,
    "totalNQueens": queens,
}


def validate_catalog():
    assert len(PROBLEMS) == 12
    assert len({problem["title"] for problem in PROBLEMS}) == 12
    for problem in PROBLEMS:
        assert len(problem["acm"]) >= 5
        problem_spec = problem["spec"]
        assert len(problem_spec["judgeCases"]) >= 5
        solver = SOLVERS[problem_spec["methodName"]]
        for case in problem_spec["judgeCases"]:
            arguments = json.loads(case["input"])
            expected = json.loads(case["output"])
            actual = solver(**arguments)
            assert actual == expected, (problem["title"], arguments, expected, actual)


def generate_sql():
    lines = [
        "-- Generated by deploy/data/generate_extended_algorithm_seed.py; additive and idempotent.",
        "SET NAMES utf8mb4;",
        "START TRANSACTION;",
        "SET @admin_id = (SELECT id FROM user WHERE userAccount='nemo_admin' AND userRole='admin' AND isDelete=0 LIMIT 1);",
    ]
    judge_config = compact_json({"timeLimit": 3000, "memoryLimit": 262144, "outputLimitBytes": 1048576})
    for problem_no, problem in enumerate(PROBLEMS, start=13):
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
    lines.extend([
        "COMMIT;",
        "SELECT p.problemNo,q.title,p.status FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo BETWEEN 13 AND 24 ORDER BY p.problemNo;",
        "",
    ])
    return "\n".join(lines)


def main():
    from catalog_quality_review import apply_reviewed_cases
    apply_reviewed_cases(PROBLEMS,13)
    validate_catalog()
    target = Path(__file__).with_name("extended_algorithm_problems.sql")
    target.write_text(generate_sql(), encoding="utf-8", newline="\n")
    print(f"generated {len(PROBLEMS)} additional problems -> {target}")


if __name__ == "__main__":
    main()
