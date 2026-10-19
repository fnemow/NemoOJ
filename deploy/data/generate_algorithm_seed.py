#!/usr/bin/env python3
"""Generate the production algorithm-problem seed used by NemoOJ.

The generated SQL replaces acceptance fixtures and their dependent judge data
while preserving user accounts. Every problem supports ACM and FUNCTION mode.
"""

from __future__ import annotations

import json
from bisect import bisect_left
from collections import deque
from pathlib import Path


def acm_case(stdin: str, stdout: str, hidden: bool = True) -> dict:
    return {"input": stdin, "output": stdout, "isHidden": hidden}


def function_case(arguments: dict, expected, hidden: bool = True) -> dict:
    return {
        "input": json.dumps(arguments, ensure_ascii=False, separators=(",", ":")),
        "output": json.dumps(expected, ensure_ascii=False, separators=(",", ":")),
        "isHidden": hidden,
    }


def spec(method: str, parameters: list[tuple[str, str]], return_type: str,
         cases: list[dict], comparator: str = "EXACT") -> dict:
    return {
        "mode": "function",
        "className": "Solution",
        "methodName": method,
        "parameters": [{"name": name, "type": value_type} for name, value_type in parameters],
        "returnType": return_type,
        "resultSource": "return",
        "comparator": {"type": comparator},
        "harnessVersion": 1,
        "judgeCases": cases,
    }


PROBLEMS = [
    {
        "title": "两数之和",
        "tags": ["简单", "数组", "哈希表"],
        "content": """## 题目描述

给定一个整数数组 `nums` 和一个整数目标值 `target`，请找出数组中和为目标值的两个不同元素，并返回它们的下标。

每组数据保证恰好存在一个答案，同一个元素不能重复使用。下标从 `0` 开始，按升序输出两个下标。

## ACM 模式输入

第一行包含两个整数 `n` 和 `target`。第二行包含 `n` 个整数，表示数组 `nums`。

## ACM 模式输出

输出两个用空格分隔的下标。

## 核心代码模式

实现 `Solution.twoSum(nums: int[], target: int) -> int[]`。

## 数据范围

- `2 ≤ n ≤ 10^5`
- `-10^9 ≤ nums[i], target ≤ 10^9`
""",
        "answer": "使用哈希表记录已经访问过的值及其下标。遍历到 nums[i] 时查找 target-nums[i]，找到后返回两个下标。时间复杂度 O(n)，空间复杂度 O(n)。",
        "acm": [
            acm_case("4 9\n2 7 11 15\n", "0 1\n", False),
            acm_case("3 6\n3 2 4\n", "1 2\n"),
            acm_case("2 6\n3 3\n", "0 1\n"),
            acm_case("5 4\n-1 0 2 5 3\n", "0 3\n"),
            acm_case("6 11\n1 2 3 4 6 8\n", "2 5\n"),
        ],
        "spec": spec("twoSum", [("nums", "int[]"), ("target", "int")], "int[]", [
            function_case({"nums": [2, 7, 11, 15], "target": 9}, [0, 1], False),
            function_case({"nums": [3, 2, 4], "target": 6}, [1, 2]),
            function_case({"nums": [3, 3], "target": 6}, [0, 1]),
            function_case({"nums": [-1, 0, 2, 5, 3], "target": 4}, [0, 3]),
            function_case({"nums": [1, 2, 3, 4, 6, 8], "target": 11}, [2, 5]),
        ]),
    },
    {
        "title": "有效的括号",
        "tags": ["简单", "字符串", "栈"],
        "content": """## 题目描述

给定一个只包含 `(`、`)`、`[`、`]`、`{`、`}` 的字符串，判断括号是否有效。

有效字符串必须满足：左括号由相同类型的右括号闭合，并且闭合顺序正确。

## ACM 模式输入

一行非空字符串 `s`。

## ACM 模式输出

有效输出 `true`，否则输出 `false`。

## 核心代码模式

实现 `Solution.isValid(s: string) -> boolean`。

## 数据范围

- `1 ≤ |s| ≤ 10^5`
""",
        "answer": "用栈保存尚未匹配的左括号。遇到右括号时，栈顶必须是对应的左括号；遍历结束后栈必须为空。时间复杂度 O(n)，空间复杂度 O(n)。",
        "acm": [
            acm_case("()[]{}\n", "true\n", False),
            acm_case("(]\n", "false\n", False),
            acm_case("{[]}\n", "true\n"),
            acm_case("([)]\n", "false\n"),
            acm_case("((()))\n", "true\n"),
        ],
        "spec": spec("isValid", [("s", "string")], "boolean", [
            function_case({"s": "()[]{}"}, True, False),
            function_case({"s": "(]"}, False, False),
            function_case({"s": "{[]}"}, True),
            function_case({"s": "([)]"}, False),
            function_case({"s": "((()))"}, True),
        ]),
    },
    {
        "title": "二分查找",
        "tags": ["简单", "数组", "二分查找"],
        "content": """## 题目描述

给定一个按严格升序排列的整数数组 `nums` 和目标值 `target`。如果目标值存在，返回它的下标；否则返回 `-1`。

要求算法的时间复杂度为 `O(log n)`。

## ACM 模式输入

第一行包含 `n` 和 `target`。第二行包含 `n` 个严格升序整数。

## ACM 模式输出

输出目标值下标，不存在则输出 `-1`。

## 核心代码模式

实现 `Solution.search(nums: int[], target: int) -> int`。

## 数据范围

- `1 ≤ n ≤ 10^5`
- `-10^9 ≤ nums[i], target ≤ 10^9`
""",
        "answer": "维护闭区间 [left,right]，比较中点与 target 后舍弃不可能的一半。时间复杂度 O(log n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("6 9\n-1 0 3 5 9 12\n", "4\n", False),
            acm_case("6 2\n-1 0 3 5 9 12\n", "-1\n", False),
            acm_case("1 5\n5\n", "0\n"),
            acm_case("5 -5\n-9 -5 0 4 8\n", "1\n"),
            acm_case("7 20\n1 4 7 10 13 16 19\n", "-1\n"),
        ],
        "spec": spec("search", [("nums", "int[]"), ("target", "int")], "int", [
            function_case({"nums": [-1, 0, 3, 5, 9, 12], "target": 9}, 4, False),
            function_case({"nums": [-1, 0, 3, 5, 9, 12], "target": 2}, -1, False),
            function_case({"nums": [5], "target": 5}, 0),
            function_case({"nums": [-9, -5, 0, 4, 8], "target": -5}, 1),
            function_case({"nums": [1, 4, 7, 10, 13, 16, 19], "target": 20}, -1),
        ]),
    },
    {
        "title": "最大子数组和",
        "tags": ["中等", "数组", "动态规划"],
        "content": """## 题目描述

给定一个整数数组 `nums`，找出和最大的非空连续子数组，返回该子数组的元素和。

## ACM 模式输入

第一行是数组长度 `n`，第二行是 `n` 个整数。

## ACM 模式输出

输出最大连续子数组和。

## 核心代码模式

实现 `Solution.maxSubArray(nums: int[]) -> int`。

## 数据范围

- `1 ≤ n ≤ 10^5`
- `-10^4 ≤ nums[i] ≤ 10^4`
""",
        "answer": "令 current 表示以当前位置结尾的最大子数组和，转移为 max(nums[i], current+nums[i])，同时维护全局最大值。时间复杂度 O(n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("9\n-2 1 -3 4 -1 2 1 -5 4\n", "6\n", False),
            acm_case("1\n1\n", "1\n", False),
            acm_case("5\n5 4 -1 7 8\n", "23\n"),
            acm_case("6\n-8 -3 -6 -2 -5 -4\n", "-2\n"),
            acm_case("5\n0 -1 2 -3 4\n", "4\n"),
        ],
        "spec": spec("maxSubArray", [("nums", "int[]")], "int", [
            function_case({"nums": [-2, 1, -3, 4, -1, 2, 1, -5, 4]}, 6, False),
            function_case({"nums": [1]}, 1, False),
            function_case({"nums": [5, 4, -1, 7, 8]}, 23),
            function_case({"nums": [-8, -3, -6, -2, -5, -4]}, -2),
            function_case({"nums": [0, -1, 2, -3, 4]}, 4),
        ]),
    },
    {
        "title": "合并两个有序数组",
        "tags": ["简单", "数组", "双指针"],
        "content": """## 题目描述

给定两个按非递减顺序排列的整数数组 `nums1` 和 `nums2`，将它们合并为一个新的非递减数组并返回。

## ACM 模式输入

第一行包含 `n` 和 `m`。第二、三行分别包含 `n` 和 `m` 个整数。

## ACM 模式输出

输出合并后的数组，元素以空格分隔。

## 核心代码模式

实现 `Solution.mergeSorted(nums1: int[], nums2: int[]) -> int[]`。

## 数据范围

- `1 ≤ n,m ≤ 10^5`
- `n+m ≤ 2×10^5`
""",
        "answer": "分别用指针扫描两个数组，每次取较小元素追加到结果，最后追加剩余部分。时间复杂度 O(n+m)，空间复杂度 O(n+m)。",
        "acm": [
            acm_case("3 3\n1 2 4\n1 3 4\n", "1 1 2 3 4 4\n", False),
            acm_case("2 3\n-3 7\n-2 0 8\n", "-3 -2 0 7 8\n", False),
            acm_case("3 3\n-3 -1 2\n-2 2 5\n", "-3 -2 -1 2 2 5\n"),
            acm_case("1 1\n0\n0\n", "0 0\n"),
            acm_case("4 2\n1 1 1 1\n1 1\n", "1 1 1 1 1 1\n"),
        ],
        "spec": spec("mergeSorted", [("nums1", "int[]"), ("nums2", "int[]")], "int[]", [
            function_case({"nums1": [1, 2, 4], "nums2": [1, 3, 4]}, [1, 1, 2, 3, 4, 4], False),
            function_case({"nums1": [-3, 7], "nums2": [-2, 0, 8]}, [-3, -2, 0, 7, 8], False),
            function_case({"nums1": [], "nums2": [0]}, [0]),
            function_case({"nums1": [-3, -1, 2], "nums2": [-2, 2, 5]}, [-3, -2, -1, 2, 2, 5]),
            function_case({"nums1": [1, 1, 1, 1], "nums2": [1, 1]}, [1, 1, 1, 1, 1, 1]),
        ]),
    },
    {
        "title": "爬楼梯",
        "tags": ["简单", "动态规划", "数学"],
        "content": """## 题目描述

你正在爬一段有 `n` 级台阶的楼梯。每次可以爬 `1` 级或 `2` 级，求到达楼顶共有多少种不同的方法。

## ACM 模式输入

输入一个整数 `n`。

## ACM 模式输出

输出不同方法数。

## 核心代码模式

实现 `Solution.climbStairs(n: int) -> int`。

## 数据范围

- `1 ≤ n ≤ 45`
""",
        "answer": "到达第 n 级的最后一步来自 n-1 或 n-2，因此 f(n)=f(n-1)+f(n-2)。滚动保存两个状态即可。时间复杂度 O(n)，空间复杂度 O(1)。",
        "acm": [
            acm_case("2\n", "2\n", False),
            acm_case("5\n", "8\n", False),
            acm_case("1\n", "1\n"),
            acm_case("10\n", "89\n"),
            acm_case("45\n", "1836311903\n"),
        ],
        "spec": spec("climbStairs", [("n", "int")], "int", [
            function_case({"n": 2}, 2, False),
            function_case({"n": 5}, 8, False),
            function_case({"n": 1}, 1),
            function_case({"n": 10}, 89),
            function_case({"n": 45}, 1836311903),
        ]),
    },
    {
        "title": "零钱兑换",
        "tags": ["中等", "数组", "动态规划"],
        "content": """## 题目描述

给定不同面额的硬币数组 `coins` 和总金额 `amount`。每种硬币可以使用任意次，返回凑成总金额所需的最少硬币数；无法凑成时返回 `-1`。

## ACM 模式输入

第一行包含硬币种数 `n` 和金额 `amount`。第二行包含 `n` 个硬币面额。

## ACM 模式输出

输出最少硬币数或 `-1`。

## 核心代码模式

实现 `Solution.coinChange(coins: int[], amount: int) -> int`。

## 数据范围

- `1 ≤ n ≤ 20`
- `1 ≤ coins[i] ≤ 10^4`
- `0 ≤ amount ≤ 10^4`
""",
        "answer": "设 dp[x] 为组成金额 x 的最少硬币数。初始化 dp[0]=0，其余为不可达；对每个金额尝试所有不超过它的硬币。时间复杂度 O(n×amount)，空间复杂度 O(amount)。",
        "acm": [
            acm_case("3 11\n1 2 5\n", "3\n", False),
            acm_case("1 3\n2\n", "-1\n", False),
            acm_case("1 0\n1\n", "0\n"),
            acm_case("4 27\n2 5 10 1\n", "4\n"),
            acm_case("4 6249\n186 419 83 408\n", "20\n"),
        ],
        "spec": spec("coinChange", [("coins", "int[]"), ("amount", "int")], "int", [
            function_case({"coins": [1, 2, 5], "amount": 11}, 3, False),
            function_case({"coins": [2], "amount": 3}, -1, False),
            function_case({"coins": [1], "amount": 0}, 0),
            function_case({"coins": [2, 5, 10, 1], "amount": 27}, 4),
            function_case({"coins": [186, 419, 83, 408], "amount": 6249}, 20),
        ]),
    },
    {
        "title": "最长公共子序列",
        "tags": ["中等", "字符串", "动态规划"],
        "content": """## 题目描述

给定两个字符串 `text1` 和 `text2`，返回它们的最长公共子序列长度。子序列可以删除原字符串中的若干字符，但不能改变剩余字符的相对顺序。

## ACM 模式输入

输入两行，分别为 `text1` 和 `text2`。

## ACM 模式输出

输出最长公共子序列长度。

## 核心代码模式

实现 `Solution.longestCommonSubsequence(text1: string, text2: string) -> int`。

## 数据范围

- `1 ≤ |text1|,|text2| ≤ 1000`
- 字符串仅包含英文字母
""",
        "answer": "用 dp[i][j] 表示两个前缀的最长公共子序列长度。字符相等时取 dp[i-1][j-1]+1，否则取 dp[i-1][j] 与 dp[i][j-1] 的较大值。时间复杂度 O(nm)。",
        "acm": [
            acm_case("abcde\nace\n", "3\n", False),
            acm_case("abc\nabc\n", "3\n", False),
            acm_case("abc\ndef\n", "0\n"),
            acm_case("ezupkr\nubmrapg\n", "2\n"),
            acm_case("AGGTAB\nGXTXAYB\n", "4\n"),
        ],
        "spec": spec("longestCommonSubsequence", [("text1", "string"), ("text2", "string")], "int", [
            function_case({"text1": "abcde", "text2": "ace"}, 3, False),
            function_case({"text1": "abc", "text2": "abc"}, 3, False),
            function_case({"text1": "abc", "text2": "def"}, 0),
            function_case({"text1": "ezupkr", "text2": "ubmrapg"}, 2),
            function_case({"text1": "AGGTAB", "text2": "GXTXAYB"}, 4),
        ]),
    },
    {
        "title": "岛屿数量",
        "tags": ["中等", "矩阵", "深度优先搜索", "广度优先搜索"],
        "content": """## 题目描述

给定一个由字符 `0` 和 `1` 组成的二维网格。`1` 表示陆地，`0` 表示水域；上下左右相邻的陆地属于同一座岛屿。返回网格中的岛屿数量。

## ACM 模式输入

第一行包含行数 `n` 和列数 `m`，随后输入 `n` 行长度为 `m` 的字符串。

## ACM 模式输出

输出岛屿数量。

## 核心代码模式

实现 `Solution.numIslands(grid: string[]) -> int`。

## 数据范围

- `1 ≤ n,m ≤ 300`
""",
        "answer": "扫描网格，每遇到一块尚未访问的陆地就增加答案，并用 DFS 或 BFS 标记与它连通的全部陆地。时间复杂度 O(nm)，空间复杂度 O(nm)。",
        "acm": [
            acm_case("4 5\n11110\n11010\n11000\n00000\n", "1\n", False),
            acm_case("4 5\n11000\n11000\n00100\n00011\n", "3\n", False),
            acm_case("1 1\n1\n", "1\n"),
            acm_case("1 1\n0\n", "0\n"),
            acm_case("3 3\n101\n010\n101\n", "5\n"),
        ],
        "spec": spec("numIslands", [("grid", "string[]")], "int", [
            function_case({"grid": ["11110", "11010", "11000", "00000"]}, 1, False),
            function_case({"grid": ["11000", "11000", "00100", "00011"]}, 3, False),
            function_case({"grid": ["1"]}, 1),
            function_case({"grid": ["0"]}, 0),
            function_case({"grid": ["101", "010", "101"]}, 5),
        ]),
    },
    {
        "title": "合并区间",
        "tags": ["中等", "数组", "排序"],
        "content": """## 题目描述

给定若干闭区间 `intervals[i] = [start, end]`，合并所有重叠或相接的区间，返回按起点升序排列且互不重叠的区间。

## ACM 模式输入

第一行是区间数量 `n`，随后 `n` 行各包含两个整数 `start` 和 `end`。

## ACM 模式输出

第一行输出合并后的区间数量，随后每行输出一个区间。

## 核心代码模式

实现 `Solution.merge(intervals: int[][]) -> int[][]`。

## 数据范围

- `1 ≤ n ≤ 10^5`
- `0 ≤ start ≤ end ≤ 10^9`
""",
        "answer": "按区间起点排序。依次扫描：若当前区间起点不大于结果末尾区间终点，则扩展终点；否则追加新区间。时间复杂度 O(n log n)。",
        "acm": [
            acm_case("4\n1 3\n2 6\n8 10\n15 18\n", "3\n1 6\n8 10\n15 18\n", False),
            acm_case("2\n1 4\n4 5\n", "1\n1 5\n", False),
            acm_case("3\n1 4\n0 2\n3 5\n", "1\n0 5\n"),
            acm_case("1\n7 9\n", "1\n7 9\n"),
            acm_case("5\n1 2\n3 4\n5 8\n6 7\n8 10\n", "3\n1 2\n3 4\n5 10\n"),
        ],
        "spec": spec("merge", [("intervals", "int[][]")], "int[][]", [
            function_case({"intervals": [[1, 3], [2, 6], [8, 10], [15, 18]]}, [[1, 6], [8, 10], [15, 18]], False),
            function_case({"intervals": [[1, 4], [4, 5]]}, [[1, 5]], False),
            function_case({"intervals": [[1, 4], [0, 2], [3, 5]]}, [[0, 5]]),
            function_case({"intervals": [[7, 9]]}, [[7, 9]]),
            function_case({"intervals": [[1, 2], [3, 4], [5, 8], [6, 7], [8, 10]]}, [[1, 2], [3, 4], [5, 10]]),
        ]),
    },
    {
        "title": "最小路径和",
        "tags": ["中等", "矩阵", "动态规划"],
        "content": """## 题目描述

给定一个包含非负整数的 `m × n` 网格，从左上角出发，每次只能向右或向下移动，求到达右下角路径上的最小数字总和。

## ACM 模式输入

第一行包含 `m` 和 `n`，随后输入 `m` 行、每行 `n` 个整数。

## ACM 模式输出

输出最小路径和。

## 核心代码模式

实现 `Solution.minPathSum(grid: int[][]) -> int`。

## 数据范围

- `1 ≤ m,n ≤ 200`
- `0 ≤ grid[i][j] ≤ 100`
""",
        "answer": "令 dp[i][j] 为到达单元格 (i,j) 的最小路径和，它等于当前值加上上方和左方状态中的较小值。可用一维数组压缩空间。时间复杂度 O(mn)。",
        "acm": [
            acm_case("3 3\n1 3 1\n1 5 1\n4 2 1\n", "7\n", False),
            acm_case("2 3\n1 2 3\n4 5 6\n", "12\n", False),
            acm_case("1 1\n5\n", "5\n"),
            acm_case("2 2\n0 0\n0 0\n", "0\n"),
            acm_case("3 4\n1 9 1 1\n1 9 1 9\n1 1 1 1\n", "6\n"),
        ],
        "spec": spec("minPathSum", [("grid", "int[][]")], "int", [
            function_case({"grid": [[1, 3, 1], [1, 5, 1], [4, 2, 1]]}, 7, False),
            function_case({"grid": [[1, 2, 3], [4, 5, 6]]}, 12, False),
            function_case({"grid": [[5]]}, 5),
            function_case({"grid": [[0, 0], [0, 0]]}, 0),
            function_case({"grid": [[1, 9, 1, 1], [1, 9, 1, 9], [1, 1, 1, 1]]}, 6),
        ]),
    },
    {
        "title": "最长递增子序列",
        "tags": ["中等", "数组", "动态规划", "二分查找"],
        "content": """## 题目描述

给定一个整数数组 `nums`，返回其中最长严格递增子序列的长度。子序列不要求连续，但必须保持原数组中的相对顺序。

## ACM 模式输入

第一行是数组长度 `n`，第二行是 `n` 个整数。

## ACM 模式输出

输出最长严格递增子序列长度。

## 核心代码模式

实现 `Solution.lengthOfLIS(nums: int[]) -> int`。

## 数据范围

- `1 ≤ n ≤ 2500`
- `-10^4 ≤ nums[i] ≤ 10^4`
""",
        "answer": "基础解法用 dp[i] 表示以 nums[i] 结尾的最长递增子序列，复杂度 O(n²)。也可维护每个长度对应的最小末尾元素并二分替换，做到 O(n log n)。",
        "acm": [
            acm_case("8\n10 9 2 5 3 7 101 18\n", "4\n", False),
            acm_case("6\n0 1 0 3 2 3\n", "4\n", False),
            acm_case("7\n7 7 7 7 7 7 7\n", "1\n"),
            acm_case("8\n-1 3 4 5 2 2 2 2\n", "4\n"),
            acm_case("5\n5 4 3 2 1\n", "1\n"),
        ],
        "spec": spec("lengthOfLIS", [("nums", "int[]")], "int", [
            function_case({"nums": [10, 9, 2, 5, 3, 7, 101, 18]}, 4, False),
            function_case({"nums": [0, 1, 0, 3, 2, 3]}, 4, False),
            function_case({"nums": [7, 7, 7, 7, 7, 7, 7]}, 1),
            function_case({"nums": [-1, 3, 4, 5, 2, 2, 2, 2]}, 4),
            function_case({"nums": [5, 4, 3, 2, 1]}, 1),
        ]),
    },
]


def solve_two_sum(nums: list[int], target: int) -> list[int]:
    seen = {}
    for index, value in enumerate(nums):
        if target - value in seen:
            return [seen[target - value], index]
        seen[value] = index
    raise AssertionError("catalog case has no two-sum solution")


def solve_valid_parentheses(s: str) -> bool:
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for char in s:
        if char in "([{":
            stack.append(char)
        elif not stack or stack.pop() != pairs[char]:
            return False
    return not stack


def solve_binary_search(nums: list[int], target: int) -> int:
    left, right = 0, len(nums) - 1
    while left <= right:
        middle = (left + right) // 2
        if nums[middle] == target:
            return middle
        if nums[middle] < target:
            left = middle + 1
        else:
            right = middle - 1
    return -1


def solve_max_subarray(nums: list[int]) -> int:
    current = answer = nums[0]
    for value in nums[1:]:
        current = max(value, current + value)
        answer = max(answer, current)
    return answer


def solve_climb_stairs(n: int) -> int:
    previous, current = 1, 1
    for _ in range(n):
        previous, current = current, previous + current
    return previous


def solve_coin_change(coins: list[int], amount: int) -> int:
    dp = [amount + 1] * (amount + 1)
    dp[0] = 0
    for value in range(1, amount + 1):
        for coin in coins:
            if coin <= value:
                dp[value] = min(dp[value], dp[value - coin] + 1)
    return -1 if dp[amount] > amount else dp[amount]


def solve_lcs(text1: str, text2: str) -> int:
    dp = [0] * (len(text2) + 1)
    for first in text1:
        diagonal = 0
        for column, second in enumerate(text2, start=1):
            above = dp[column]
            dp[column] = diagonal + 1 if first == second else max(dp[column], dp[column - 1])
            diagonal = above
    return dp[-1]


def solve_islands(grid: list[str]) -> int:
    rows, columns = len(grid), len(grid[0])
    seen = set()
    answer = 0
    for row in range(rows):
        for column in range(columns):
            if grid[row][column] != "1" or (row, column) in seen:
                continue
            answer += 1
            queue = deque([(row, column)])
            seen.add((row, column))
            while queue:
                current_row, current_column = queue.popleft()
                for next_row, next_column in (
                    (current_row - 1, current_column), (current_row + 1, current_column),
                    (current_row, current_column - 1), (current_row, current_column + 1),
                ):
                    if (0 <= next_row < rows and 0 <= next_column < columns
                            and grid[next_row][next_column] == "1"
                            and (next_row, next_column) not in seen):
                        seen.add((next_row, next_column))
                        queue.append((next_row, next_column))
    return answer


def solve_merge_intervals(intervals: list[list[int]]) -> list[list[int]]:
    answer = []
    for start, end in sorted(intervals):
        if answer and start <= answer[-1][1]:
            answer[-1][1] = max(answer[-1][1], end)
        else:
            answer.append([start, end])
    return answer


def solve_min_path(grid: list[list[int]]) -> int:
    dp = [10**18] * len(grid[0])
    dp[0] = 0
    for row in grid:
        for column, value in enumerate(row):
            dp[column] = min(dp[column], dp[column - 1] if column else 10**18) + value
    return dp[-1]


def solve_lis(nums: list[int]) -> int:
    tails = []
    for value in nums:
        index = bisect_left(tails, value)
        if index == len(tails):
            tails.append(value)
        else:
            tails[index] = value
    return len(tails)


REFERENCE_SOLVERS = {
    "twoSum": solve_two_sum,
    "isValid": solve_valid_parentheses,
    "search": solve_binary_search,
    "maxSubArray": solve_max_subarray,
    "mergeSorted": lambda nums1, nums2: sorted(nums1 + nums2),
    "climbStairs": solve_climb_stairs,
    "coinChange": solve_coin_change,
    "longestCommonSubsequence": solve_lcs,
    "numIslands": solve_islands,
    "merge": solve_merge_intervals,
    "minPathSum": solve_min_path,
    "lengthOfLIS": solve_lis,
}


def sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def compact_json(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def validate_catalog() -> None:
    assert len(PROBLEMS) == 12
    assert len({problem["title"] for problem in PROBLEMS}) == len(PROBLEMS)
    supported_types = {
        "int", "long", "double", "boolean", "string", "int[]", "long[]",
        "double[]", "boolean[]", "string[]", "int[][]", "list<int>",
        "list_node<int>", "tree_node<int>",
    }
    for problem in PROBLEMS:
        assert 1 <= len(problem["title"]) <= 80
        assert 1 <= len(problem["content"]) <= 8192
        assert len(problem["acm"]) >= 5
        assert any(case["isHidden"] is False for case in problem["acm"])
        problem_spec = problem["spec"]
        assert problem_spec["returnType"] in supported_types
        assert len(problem_spec["judgeCases"]) >= 5
        for parameter in problem_spec["parameters"]:
            assert parameter["type"] in supported_types
        for case in problem_spec["judgeCases"]:
            arguments = json.loads(case["input"])
            expected = json.loads(case["output"])
            assert isinstance(arguments, dict)
            solver = REFERENCE_SOLVERS[problem_spec["methodName"]]
            actual = solver(**arguments)
            assert actual == expected, (problem["title"], arguments, expected, actual)


def generate_sql() -> str:
    lines = [
        "-- Generated by deploy/data/generate_algorithm_seed.py; do not edit by hand.",
        "SET NAMES utf8mb4;",
        "START TRANSACTION;",
        "SET @admin_id = (SELECT id FROM user WHERE userAccount='nemo_admin' AND userRole='admin' AND isDelete=0 LIMIT 1);",
        "-- Acceptance submissions and their outbox jobs belong to the removed fixtures.",
        "DELETE FROM outbox_event WHERE bizId IN (SELECT id FROM question_submit);",
        "DELETE FROM question_submit;",
        "DELETE FROM question_version;",
        "UPDATE feedback SET problemNo=NULL WHERE problemNo IS NOT NULL;",
        "DELETE FROM question_public_number;",
        "DELETE FROM question;",
    ]

    judge_config = compact_json({
        "timeLimit": 2000,
        "memoryLimit": 262144,
        "outputLimitBytes": 1048576,
    })
    for problem_no, problem in enumerate(PROBLEMS, start=1):
        values = [
            sql_string(problem["title"]),
            sql_string(problem["content"].strip()),
            sql_string(compact_json(problem["tags"])),
            sql_string(problem["answer"]),
            "0", "0",
            sql_string(compact_json(problem["acm"])),
            sql_string(judge_config),
            sql_string(compact_json(problem["spec"])),
            "1", "1", "0", "0", "@admin_id", "0", "0",
            str(problem_no),
        ]
        lines.extend([
            f"-- {problem_no}. {problem['title']}",
            "INSERT INTO question (title,content,tags,answer,submitNum,acceptedNum,judgeCase,judgeConfig,problemSpec,problemVersion,isPublished,thumbNum,favourNum,userId,isDelete,rowVersion,problemNo)",
            "VALUES (" + ",".join(values) + ");",
            "SET @qid = LAST_INSERT_ID();",
            "INSERT INTO question_version (questionId,version,title,content,tags,answer,judgeCase,judgeConfig,problemSpec,harnessVersion,publishedBy)",
            "SELECT id,1,title,content,tags,answer,judgeCase,judgeConfig,problemSpec,1,@admin_id FROM question WHERE id=@qid;",
            "INSERT INTO question_public_number (problemNo,questionId,currentQuestionId,status,lifecycleVersion,lastOperatorId,offlineReason)",
            f"VALUES ({problem_no},@qid,@qid,'ONLINE',1,@admin_id,NULL);",
        ])

    lines.extend([
        "COMMIT;",
        "SELECT problemNo,title,problemVersion,isPublished FROM question WHERE isDelete=0 ORDER BY problemNo;",
        "SELECT 'questions' AS metric,COUNT(*) AS value FROM question WHERE isDelete=0",
        "UNION ALL SELECT 'versions',COUNT(*) FROM question_version",
        "UNION ALL SELECT 'public_online',COUNT(*) FROM question_public_number WHERE status='ONLINE'",
        "UNION ALL SELECT 'submissions',COUNT(*) FROM question_submit;",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    from catalog_quality_review import apply_reviewed_cases
    apply_reviewed_cases(PROBLEMS,1)
    validate_catalog()
    target = Path(__file__).with_name("algorithm_problems.sql")
    target.write_text(generate_sql(), encoding="utf-8", newline="\n")
    print(f"generated {len(PROBLEMS)} problems -> {target}")


if __name__ == "__main__":
    main()
