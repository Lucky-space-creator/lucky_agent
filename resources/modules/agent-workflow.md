# agent-workflow 模块实现文档

> 对应需求：待办「工作流模块 p4」；技术底座 LangGraph4j。

## 一、模块定位
基于 LangGraph4j `StateGraph` 提供 DAG 工作流编排，覆盖 LLM 节点、工具节点、条件节点、代码节点。

## 二、实现框架
```
agent-workflow
├── api/
│   ├── WorkflowRegistry.java    # 组件目录
│   ├── WorkflowExecutor.java    # DAG 执行契约
│   └── dto/ WorkflowDef, NodeResult
├── component/
│   ├── LlmNode.java             # LLM 节点
│   ├── ToolNode.java            # 工具节点
│   ├── ConditionNode.java       # 条件节点
│   └── CodeNode.java            # 代码节点（沙箱）
├── dag/
│   ├── DagCompiler.java         # DAG → 可执行计划
│   └── TopoScheduler.java       # 串行/并行/条件调度
├── scope/
│   └── ContextScope.java        # 变量作用域
└── sandbox/
    └── WorkflowSandbox.java     # 执行沙箱
```

## 三、核心设计
- 组件声明 IO 契约与副作用类型，编排时校验兼容性。
- 编译期静态校验权限、变量、死锁；运行期按边条件驱动。
- 每个节点经执行臂校验，失败按策略重试/跳过/终止。
- 代码/工具节点默认进沙箱，资源与文件系统边界受控。

## 四、验收点
- [ ] DAG 可编译、可执行，串行/并行/条件分支正确。
- [ ] 节点越权操作被执行臂阻断。
- [ ] 失败节点按策略降级，不静默吞错。
- [ ] 变量作用域隔离，不污染主上下文。