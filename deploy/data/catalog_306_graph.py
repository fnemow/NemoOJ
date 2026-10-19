"""Twenty graph exercises with explicit edge conventions and deterministic answers."""
from collections import deque
import heapq
from catalog_306_common import add, RNG


def adjacency(n, edges, directed=False):
    graph = [[] for _ in range(n)]
    for edge in edges:
        u, v = edge[:2]; w = edge[2] if len(edge) > 2 else 1
        graph[u].append((v, w))
        if not directed: graph[v].append((u, w))
    return graph


def components(n, edges):
    g=adjacency(n,edges); seen=set(); answer=0
    for start in range(n):
        if start in seen: continue
        answer+=1; stack=[start]; seen.add(start)
        while stack:
            u=stack.pop()
            for v,_ in g[u]:
                if v not in seen: seen.add(v); stack.append(v)
    return answer


def reachable(n, edges, source, target):
    g=adjacency(n,edges,True); seen={source}; queue=deque([source])
    while queue:
        u=queue.popleft()
        for v,_ in g[u]:
            if v not in seen: seen.add(v); queue.append(v)
    return target in seen


def hop_distance(n, edges, source, target):
    g=adjacency(n,edges); dist=[-1]*n;dist[source]=0; queue=deque([source])
    while queue:
        u=queue.popleft()
        for v,_ in g[u]:
            if dist[v]<0:dist[v]=dist[u]+1;queue.append(v)
    return dist[target]


def bipartite(n, edges):
    g=adjacency(n,edges); color=[-1]*n
    for start in range(n):
        if color[start]>=0:continue
        color[start]=0; queue=deque([start])
        while queue:
            u=queue.popleft()
            for v,_ in g[u]:
                if color[v]<0:color[v]=color[u]^1;queue.append(v)
                elif color[v]==color[u]:return False
    return True


def topological_order(n, edges):
    g=adjacency(n,edges,True); degree=[0]*n
    for u,v in edges:degree[v]+=1
    heap=[i for i in range(n) if degree[i]==0];heapq.heapify(heap);order=[]
    while heap:
        u=heapq.heappop(heap);order.append(u)
        for v,_ in g[u]:
            degree[v]-=1
            if degree[v]==0:heapq.heappush(heap,v)
    return order if len(order)==n else []


def course_possible(n, edges): return len(topological_order(n,edges))==n


def semesters(n, edges):
    order=topological_order(n,edges)
    if len(order)!=n:return -1
    g=adjacency(n,edges,True);dp=[1]*n
    for u in order:
        for v,_ in g[u]:dp[v]=max(dp[v],dp[u]+1)
    return max(dp,default=0)


def dag_longest(n, edges):
    order=topological_order(n,edges)
    if len(order)!=n:return -1
    g=adjacency(n,edges,True);dp=[0]*n
    for u in order:
        for v,_ in g[u]:dp[v]=max(dp[v],dp[u]+1)
    return max(dp,default=0)


def dijkstra(n, edges, source):
    g=adjacency(n,edges,True);dist=[10**18]*n;dist[source]=0;heap=[(0,source)]
    while heap:
        d,u=heapq.heappop(heap)
        if d!=dist[u]:continue
        for v,w in g[u]:
            if d+w<dist[v]:dist[v]=d+w;heapq.heappush(heap,(d+w,v))
    return [-1 if d==10**18 else d for d in dist]


def network_delay(n, edges, source):
    distances=dijkstra(n,edges,source)
    return -1 if -1 in distances else max(distances)


def limited_flight(n, edges, source, target, k):
    dist=[10**18]*n;dist[source]=0
    for _ in range(k+1):
        nxt=dist[:]
        for u,v,w in edges:nxt[v]=min(nxt[v],dist[u]+w)
        dist=nxt
    return -1 if dist[target]>=10**18 else dist[target]


def mst(n, edges):
    parent=list(range(n));size=[1]*n
    def find(x):
        while x!=parent[x]:parent[x]=parent[parent[x]];x=parent[x]
        return x
    cost=used=0
    for u,v,w in sorted(edges,key=lambda e:e[2]):
        a,b=find(u),find(v)
        if a==b:continue
        if size[a]<size[b]:a,b=b,a
        parent[b]=a;size[a]+=size[b];cost+=w;used+=1
    return cost if used==n-1 else -1


def tree_centers(n, edges):
    if n==1:return [0]
    g=[set(v for v,_ in row) for row in adjacency(n,edges)];leaves=[i for i in range(n) if len(g[i])==1];remaining=n
    while remaining>2:
        remaining-=len(leaves);nxt=[]
        for u in leaves:
            v=next(iter(g[u]));g[v].remove(u)
            if len(g[v])==1:nxt.append(v)
        leaves=nxt
    return sorted(leaves)


def tree_diameter(n, edges):
    g=adjacency(n,edges)
    def farthest(start):
        d=[-1]*n;d[start]=0;q=deque([start])
        while q:
            u=q.popleft()
            for v,_ in g[u]:
                if d[v]<0:d[v]=d[u]+1;q.append(v)
        end=max(range(n),key=lambda i:d[i]);return end,d[end]
    return farthest(farthest(0)[0])[1]


def shortest_count(n, edges, source, target):
    g=adjacency(n,edges);d=[-1]*n;count=[0]*n;d[source]=0;count[source]=1;q=deque([source])
    while q:
        u=q.popleft()
        for v,_ in g[u]:
            if d[v]<0:d[v]=d[u]+1;q.append(v)
            if d[v]==d[u]+1:count[v]=(count[v]+count[u])%1000000007
    return count[target]


def safe_vertices(n, edges):
    reverse=[[] for _ in range(n)];degree=[0]*n
    for u,v in edges:reverse[v].append(u);degree[u]+=1
    q=deque(i for i in range(n) if degree[i]==0);safe=[]
    while q:
        u=q.popleft();safe.append(u)
        for v in reverse[u]:
            degree[v]-=1
            if degree[v]==0:q.append(v)
    return sorted(safe)


def bridges(n, edges):
    g=[[] for _ in range(n)]
    for i,(u,v) in enumerate(edges):g[u].append((v,i));g[v].append((u,i))
    timer=0;tin=[-1]*n;low=[0]*n;answer=0
    def dfs(u,parent_edge):
        nonlocal timer,answer
        tin[u]=low[u]=timer;timer+=1
        for v,e in g[u]:
            if e==parent_edge:continue
            if tin[v]>=0:low[u]=min(low[u],tin[v])
            else:
                dfs(v,e);low[u]=min(low[u],low[v])
                if low[v]>tin[u]:answer+=1
    for u in range(n):
        if tin[u]<0:dfs(u,-1)
    return answer


def scc_count(n, edges):
    g=adjacency(n,edges,True);rev=[[] for _ in range(n)]
    for u,v in edges:rev[v].append(u)
    seen=set();order=[]
    def first(u):
        seen.add(u)
        for v,_ in g[u]:
            if v not in seen:first(v)
        order.append(u)
    for u in range(n):
        if u not in seen:first(u)
    seen=set();count=0
    for start in reversed(order):
        if start in seen:continue
        count+=1;stack=[start];seen.add(start)
        while stack:
            for v in rev[stack.pop()]:
                if v not in seen:seen.add(v);stack.append(v)
    return count


def provinces(matrix):
    n=len(matrix);edges=[[i,j] for i in range(n) for j in range(i) if matrix[i][j]]
    return components(n,edges)


def redundant_edge(edges):
    n=max(max(e) for e in edges)+1;parent=list(range(n));answer=[]
    def find(x):
        while parent[x]!=x:parent[x]=parent[parent[x]];x=parent[x]
        return x
    for u,v in edges:
        a,b=find(u),find(v)
        if a==b:answer=[u,v]
        else:parent[a]=b
    return answer


def register():
    group='graph';tags=['图论']
    bounds='- 顶点编号 `0..n-1`，`1 ≤ n ≤ 200`。\n- `edges` 每行是一个边；无自环、无重复边。空边集合法。'
    def put(title,diff,method,decl,ret,text,fn,examples,extras,idea,extra_tags=(),extra_bounds=''):
        add(group,title,diff,tags+list(extra_tags),method,decl,ret,text,bounds+'\n'+extra_bounds,idea,fn,examples,extras)
    graphs=[];dags=[];weighted=[];trees=[]
    for n in range(2,14):
        edges=[[u,v] for u in range(n) for v in range(u+1,n) if RNG.random()<.23]
        graphs.append((n,edges));dags.append((n,edges));weighted.append((n,[e+[RNG.randint(0,30)] for e in edges]));trees.append((n,[[v,RNG.randrange(v)] for v in range(1,n)]))
    directed=[(n,e+([[n-1,0]] if n%2 else [])) for n,e in graphs]
    put('无向图的连通分量','简单','connectedComponents','n:int,edges:int[][]','int','给定无向图，返回连通分量个数；孤立顶点也算一个分量。',components,[((4,[[0,1],[2,3]]),2),((1,[]),1)],graphs,'逐个未访问顶点启动 DFS/BFS，时间 O(n+m)，空间 O(n+m)。',['并查集'])
    put('有向图两点可达性','简单','directedReachable','n:int,edges:int[][],source:int,target:int','boolean','每行边为 u→v，判断 source 能否到达 target。顶点可通过零条边到达自身。',reachable,[((3,[[0,1]],0,2),False),((1,[],0,0),True)],[(n,e,0,n-1) for n,e in directed],'使用 BFS/DFS；O(n+m)。',extra_bounds='- source、target 是合法顶点。')
    put('无权图最短距离','中等','unweightedDistance','n:int,edges:int[][],source:int,target:int','int','无向边长度均为 1，返回 source 到 target 的最少边数，不可达返回 -1。',hop_distance,[((3,[[0,1],[1,2]],0,2),2),((2,[],0,1),-1)],[(n,e,0,n-1) for n,e in graphs],'BFS 按层扩展，O(n+m)。')
    put('判断图是否为二分图','中等','isBipartiteGraph','n:int,edges:int[][]','boolean','判断无向图顶点能否分成两组，使每条边的两端属于不同组。',bipartite,[((3,[[0,1],[1,2],[2,0]]),False),((1,[]),True)],graphs,'对每个分量 BFS 双色染色，O(n+m)。')
    put('字典序最小拓扑序','中等','lexicographicTopo','n:int,edges:int[][]','int[]','边 u→v 表示 u 必须在 v 前。输出字典序最小拓扑序；存在环输出空数组。',topological_order,[((3,[[0,2],[1,2]]),[0,1,2]),((2,[[0,1],[1,0]]),[])],directed,'Kahn 算法用最小堆选择当前零入度顶点，O((n+m)log n)。',['堆'])
    put('课程依赖能否全部完成','中等','canFinishCourses','n:int,edges:int[][]','boolean','边 u→v 表示学 v 前必须完成 u。判断能否完成全部 n 门课程。',course_possible,[((2,[[0,1]]),True),((2,[[0,1],[1,0]]),False)],directed,'拓扑排序检测环，O(n+m)。')
    put('完成课程的最少学期','困难','minimumSemesters','n:int,edges:int[][]','int','边 u→v 是先修关系。每学期可同时学任意多门满足先修条件的课，同学期不能连续完成先修课。返回最少学期，存在环返回 -1。',semesters,[((3,[[0,2],[1,2]]),2),((2,[[0,1],[1,0]]),-1)],directed,'拓扑序上计算最长先修链节点数，O(n+m)。',['动态规划'])
    put('有向无环图最长路径','中等','dagLongestPath','n:int,edges:int[][]','int','边 u→v 长度为 1，允许从任意顶点出发。返回最长路径边数；存在有向环返回 -1。',dag_longest,[((3,[[0,1],[1,2]]),2),((1,[]),0)],directed,'拓扑序动态规划，O(n+m)。',['动态规划'])
    put('非负权图单源最短路','中等','singleSourceDistances','n:int,edges:int[][],source:int','long[]','有向边每行为 [u,v,w]，返回 source 到编号 0..n-1 各顶点的最短距离。不可达位置为 -1。',dijkstra,[((3,[[0,1,4],[0,2,9],[1,2,2]],0),[0,4,6]),((2,[],0),[0,-1])],[(n,e,0) for n,e in weighted],'Dijkstra 最小堆，O((n+m)log n)。',['堆'], '- `0 ≤ w ≤ 10^6`，source 合法。')
    put('网络信号传播时间','中等','networkSignalTime','n:int,edges:int[][],source:int','long','有向边 [u,v,w] 表示信号耗时 w。source 在时刻 0 发信号，返回所有顶点收到信号的最早时刻；有不可达顶点返回 -1。',network_delay,[((3,[[0,1,2],[1,2,3]],0),5),((2,[],0),-1)],[(n,e,0) for n,e in weighted],'Dijkstra 后取最远距离，O((n+m)log n)。',['堆'],'- `0 ≤ w ≤ 10^6`。')
    put('限制中转次数的航线费用','困难','cheapestLimitedFlight','n:int,edges:int[][],source:int,target:int,k:int','long','有向航线 [u,v,w]，最多中转 k 次即最多经过 k+1 条边。返回最小费用，无航线返回 -1；起终点相同允许零费用。',limited_flight,[((3,[[0,1,2],[1,2,2],[0,2,9]],0,2,0),9),((3,[[0,1,2],[1,2,2],[0,2,9]],0,2,1),4)],[(n,e,0,n-1,n%4) for n,e in weighted],'每轮只读取上一轮最短路数组的 Bellman-Ford，O((k+1)m)。',['动态规划'],'- `0 ≤ k ≤ n`，`0 ≤ w ≤ 10^6`。')
    put('最小生成树费用','中等','minimumSpanningCost','n:int,edges:int[][]','long','无向边每行为 [u,v,w]。连接全部顶点的生成树最小总权重是多少？图不连通返回 -1，单顶点返回 0。',mst,[((3,[[0,1,3],[1,2,2],[0,2,8]]),5),((2,[]),-1)],weighted,'Kruskal 排序边并用并查集避免成环，O(m log m)。',['并查集','排序'],'- `0 ≤ w ≤ 10^6`。')
    put('树的最小高度根节点','中等','minimumHeightRoots','n:int,edges:int[][]','int[]','输入保证是无向树。选择一个根后高度为根到最远顶点的边数。按编号升序输出使高度最小的所有根节点。',tree_centers,[((4,[[0,1],[1,2],[1,3]]),[1]),((2,[[0,1]]),[0,1])],[(1,[])]+trees,'逐层删除叶子，剩下的一个或两个顶点是树中心；O(n)。')
    put('无向树的直径长度','中等','undirectedTreeDiameter','n:int,edges:int[][]','int','输入为无向树，返回任意两点之间最长简单路径的边数。',tree_diameter,[((4,[[0,1],[1,2],[1,3]]),2),((1,[]),0)],trees,'从任意顶点 BFS 找端点，再从端点 BFS；O(n)。')
    put('无权图最短路径方案数','困难','countShortestPaths','n:int,edges:int[][],source:int,target:int','int','无向图，路径长度按边数计算。返回 source 到 target 的最短路径数量，对 1000000007 取模；不可达为 0。',shortest_count,[((4,[[0,1],[0,2],[1,3],[2,3]],0,3),2),((1,[],0,0),1)],[(n,e,0,n-1) for n,e in graphs],'BFS 同时累计前一层路径数；O(n+m)。',['动态规划'])
    put('最终安全的有向图顶点','困难','eventuallySafeVertices','n:int,edges:int[][]','int[]','从某点出发的每条路径最终都会在无出边顶点停止，则该点安全。按编号升序返回全部安全顶点。',safe_vertices,[((4,[[0,1],[1,0],[2,3]]),[2,3]),((2,[]),[0,1])],directed,'在反向图从终点剥离出度为零的顶点；O(n+m)。')
    put('无向图的桥数量','困难','countGraphBridges','n:int,edges:int[][]','int','删除一条边会增加连通分量数时，该边是桥。返回无向简单图中的桥数量。',bridges,[((4,[[0,1],[1,2],[2,0],[2,3]]),1),((2,[]),0)],graphs,'DFS 时间戳与 low 值检测桥；O(n+m)。')
    put('有向图强连通分量数量','困难','strongComponentCount','n:int,edges:int[][]','int','同一强连通分量内任意两顶点相互可达，返回有向图强连通分量数。',scc_count,[((3,[[0,1],[1,0],[1,2]]),2),((1,[]),1)],directed,'Kosaraju 两遍 DFS，O(n+m)。')
    matrices=[([[1 if i==j or [min(i,j),max(i,j)] in e else 0 for j in range(n)] for i in range(n)],) for n,e in graphs]
    add(group,'城市省份数量','中等',['图论','并查集','矩阵'],'provinceCount','matrix:int[][]','int','对称的 0/1 邻接矩阵表示城市直接相连，主对角线全为 1。返回通过直接或间接连接形成的省份数量。','- `1 ≤ n ≤ 200`，矩阵为 n×n。','DFS 或并查集，O(n²)。',provinces,[(([[1,1,0],[1,1,0],[0,0,1]],),2),(([[1]],),1)],matrices)
    cycles=[]
    for n in range(3,14):cycles.append(([[i-1,i] for i in range(1,n)]+[[0,n-1]],))
    add(group,'使树恢复无环的最后一条边','中等',['图论','并查集'],'lastRedundantEdge','edges:int[][]','int[]','一个顶点编号连续从 0 开始的树，额外加入一条不重复无向边。按输入顺序返回最后一条可以删除、使图恢复为树的边，保留该边端点的输入顺序。','- 顶点数 `3..200`，边数等于顶点数，无自环和重复边。','并查集扫描，第一次连接同根端点的边就是最后可删除边；O(n α(n))。',redundant_edge,[(([[0,1],[1,2],[0,2]],),[0,2]),(([[0,1],[0,2],[0,3],[2,3]],),[2,3])],cycles)
