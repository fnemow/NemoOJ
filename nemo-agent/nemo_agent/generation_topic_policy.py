"""Generation guidance, not an authorization or a replacement for sandbox proof."""
import re

TOPIC_DIFFICULTY_POLICY = (
    '主题复杂不等于所有练习都困难。宽泛学习目标必须先拆解为指定难度的子技能，保持原主题，不能因公共题库或可信模板没有该难度就拒绝。'
    'EASY练单项性质、计数或单个局部操作；MEDIUM组合性质或沿路径检查；HARD练完整维护或更严格复杂度。'
    '用户明确要求完整插入/删除/平衡维护时，不得冒充简单练习；无法满足指定难度须说明具体冲突，不能保证目标可被任意简化。'
    'Runner的ListNode只有val/next，TreeNode只有val/left/right，没有颜色或parent字段。'
    'TreeNode没有color/parent，不得访问不存在字段；额外节点类只有在signature.dataStructures明确声明后才能使用。'
    '带颜色、权值等附加属性的树可以用现有数组表达，不需要新增节点类型。'
    '红黑树推荐签名参数root:int、keys:int[]、colors:int[]、left:int[]、right:int[]；'
    '编号0..n-1，colors的0表示黑、1表示红，left/right的-1表示黑色NIL叶，空树root=-1且数组为空。'
    '非空树四个数组等长，root合法，子节点编号合法，无环、无共享子节点，所有节点从root可达；题面与20组输入都须明确遵守此结构。'
    '如果验证红黑性质，输入可以违反待验证的颜色性质，不能预先保证待验证性质成立，使所有答案一样。'
    '红黑树EASY可统计红色节点与红色子节点相邻的边数，或检测单项颜色性质；'
    'MEDIUM可检查根到NIL路径的黑高一致性；HARD可校验完整红黑树性质或执行明确指定的平衡操作。'
    '仅给出学习红黑树这一宽泛目标时，HARD默认选择验证完整红黑树性质，返回唯一boolean，'
    '检查严格BST次序、根为黑、红色节点没有红色孩子、所有到NIL路径黑高相同；空树合法。'
    '题面明确左右子树分别严格小于和大于当前键，重复键判非法；NIL按黑色计算。'
    '优化解可一次遍历，独立校验解可枚举每个节点的祖先约束和每条到NIL路径，分别检查这些性质。'
    '20组输入保持小规模，每组建议0..12节点，结构合法但颜色和键次序允许不合法，包含真和假两类答案。'
    '除非用户明确指定，不选择完整插入删除，也不返回某个非唯一的平衡树形。'
    '用户明确指定插入删除时，若返回树形必须先规定唯一的旋转和修复规则；'
    '也可在满足其需求的前提下返回确定的查询结果或中序序列，不能仅因内部树形不唯一而拒绝所有红黑树题。'
    '这些是同主题递进练习，不要求每道题都实现整棵红黑树。返回值必须唯一，独立校验解与六组合验证仍不可省略。'
)


def generation_topic_policy(goal):
    # Fixed platform guidance only. Never interpolate user instructions into the system message.
    # Keep the longer encoding example out of unrelated generation requests.
    topic = re.sub(r'[\s_-]+', '', str(goal).casefold())
    custom=(
        '平台还支持题目级自定义节点：参数或返回类型写node<ClassName>，在signature.dataStructures声明'
        '[{"name":"RBNode","fields":[{"name":"value","type":"int"},{"name":"color","type":"int"},{"name":"left","type":"ref"},{"name":"right","type":"ref"},{"name":"parent","type":"ref"}]}]。'
        '类名为大写开头的ASCII字母数字，字段名为普通跨语言标识符，禁止关键字、内置类名、方法或代码。'
        '最多4类，每类1至12字段；字段类型int/long/double/boolean/string/ref/ref[]，ref及ref[]仅引用同类节点。'
        '节点参数输入为null，或{"root":0,"nodes":[{"value":1,"color":0,"left":-1,"right":-1,"parent":-1}]}；'
        'root为有效节点编号，ref为编号或-1，ref[]为至多32个编号，节点最多200且必须从root沿所有引用可达，允许父指针及环。'
        '每节点必须具有且仅具有声明字段，值类型严格匹配；string最多200字且不含换行。'
        '平台自动提供三语言类、构造与序列化，不要在Solution里重新声明这些类；Java/C++创建节点用无参构造后赋字段，Python可用字段关键字构造。'
        '输出node类型采用按声明字段顺序广度遍历重新编号，根编号0，只输出可达节点；共享引用与环保留。'
        '既有模板没有该数据结构不是拒绝理由，选择此节点描述或基础数组建模完成自由生成；用户明确要求节点参数时优先声明自定义节点。'
    )
    if '红黑树' in topic or 'redblack' in topic:
        return TOPIC_DIFFICULTY_POLICY+custom
    return TOPIC_DIFFICULTY_POLICY.split('红黑树推荐签名参数', 1)[0]+custom
