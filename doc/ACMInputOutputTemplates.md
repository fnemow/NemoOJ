> 已撤回（2026-10-15）：线上 ACM 默认代码已恢复简洁入口骨架，以下保留为历史实施记录。

# ACM 输入输出默认模板

适用运行环境：Java 8、Python 3.12、C++17。公开题和私人题共用模板；核心代码模式继续由 Runner 处理输入输出。

默认模板只初始化标准输入输出，读取和输出示例均写成注释。按题面取消相应注释并实现算法，不会默认读取两个整数或默认循环到 EOF。每个沙箱测试用例独立运行；题面若包含测试组数 T 或 EOF 多组输入，再自行组织循环。

## 三种语言

Java 使用 `Scanner` 配合 `BufferedInputStream` 读取，以及 `PrintWriter` 输出。入口类固定为 `Main`，不写 package。默认保留末尾 `out.flush()`，确保使用 `out` 写出的结果完整输出。模板提供 `nextInt`、`nextLong`、数组、单词、整行的注释示例；从按词读取切换到下一整行时，先处理未读的行尾。

C++ 使用 `cin`、`cout`，初始化 `ios::sync_with_stdio(false)` 和 `cin.tie(nullptr)`，并保留 `using namespace std;`。本平台使用 GNU C++，默认包含 `<bits/stdc++.h>`。提供数组、单词及 `getline` 的注释示例；从 `cin >>` 切换到下一整行前，可用 `cin.ignore(numeric_limits<streamsize>::max(), '\n')` 消耗行尾。整数默认 `int`，较大整数使用 `long long`。

Python 初始化 `read = sys.stdin.readline`、`write = sys.stdout.write`，保留 `main()` 入口。示例覆盖单个整数、同一行多个整数、一行数组，以及 `read().rstrip('\r\n')` 读取保留空格的字符串。数组跨多行时，按题面继续读取；`write` 输出时自行添加换行，也可以使用 `print`。

AI 私人题的 ACM 文本协议可能将每个值分别放在一行；数组长度和元素、字符串空行、二叉树 null 等具体约定仍以该题「ACM 输入与输出约定」为准。

模板源文件：

- `OJ-backend-microservice/OJ-backend-question-service/src/main/resources/templates/acm/Main.java.txt`
- `OJ-backend-microservice/OJ-backend-question-service/src/main/resources/templates/acm/main.cpp.txt`
- `OJ-backend-microservice/OJ-backend-question-service/src/main/resources/templates/acm/main.py.txt`

## 草稿兼容

ACM 模板版本改为 `<runtimeId>-acm-io-v3`，核心代码模板仍为 `<runtimeId>-v2`。前端优先恢复当前版本草稿，再按同一用户、题目、版本、语言和模式查找旧 ACM 草稿。

只有与旧默认骨架逐字相同的草稿会替换成新模板；已编辑代码保留。若旧浏览器保存了未编辑的新默认模板，而旧版本中仍有已编辑解法，前端会优先恢复该解法。旧草稿不删除。私人题在相同题目、版本、语言和模式下刷新时，也只更新未编辑的模板，保留当前已编辑代码。

## 验证

题目服务相关 7 项测试、前端草稿/工作区相关 13 项测试、类型检查、Lint 和生产构建通过。在现有隔离沙箱中运行 9 个程序、15 个用例，覆盖三语言原始空模板、跨行数组、负数、64 位整数、保留空格的整行文本及中文。测试程序不会创建正式提交或测试账号。
