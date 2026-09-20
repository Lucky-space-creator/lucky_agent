# agent-prompt 模块实现文档

> 对应需求：待办「Prompt 模块」；八项差距 D17 提示词缓存。

## 一、模块定位
负责系统/用户 Prompt 的模板管理、静态/动态分界、五级覆盖优先级、版本管理与审计。

## 二、实现框架
```
agent-prompt
├── api/
│   ├── PromptService.java       # Prompt 组装契约
│   ├── PromptTemplate.java      # 模板与变量
│   └── dto/ PromptResult, PromptVersion
├── template/
│   ├── TemplateRegistry.java    # 模板注册与版本
│   └── TemplateRenderer.java    # 占位符渲染
├── boundary/
│   ├── StaticSection.java       # 静态可缓存段
│   └── DynamicSection.java      # 动态段（记忆/上下文/工具）
├── priority/
│   └── CoverageResolver.java    # 五级覆盖优先级
└── audit/
    └── PromptAuditLogger.java   # 变更记录/回滚
```

## 三、核心设计
- 静态段保持字节级稳定，动态段每轮组装，边界标记固定。
- 覆盖优先级：Override > Coordinator > Agent > Custom > Default。
- 模板集中管理，变更走版本记录，支持回滚。
- 渲染结果不直接执行，仍需经过权限规则与执行臂校验。

## 四、验收点
- [ ] 静态/动态分界生效，静态前缀多轮稳定。
- [ ] 高优先级 Prompt 覆盖低优先级。
- [ ] 模板变更可回滚，审计有记录。