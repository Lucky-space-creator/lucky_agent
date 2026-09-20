# agent-model 模块实现文档

> 对应需求文档：§4.14 大模型配置模块、§6.3 分层模型

## 一、模块定位

`agent-model` 负责模型端点接入、路由策略、参数配置、Key 加密管理、fallback 与健康探测。纯接入，不收费；本地配置直连（不引入平台 ID / 不做激活配对）。

**责任边界**：
- 读取 `<frameworkRoot>/.config/` 的端点 URL + Key 去调用 LLM，模型访问经 Spring AI `ChatModel`。
- Key/URL 本机加密存储，防泄露。
- 不向平台上报 Key/URL、不引入平台 ID、不做 token 校验。

---

## 二、实现框架（分层）

```
agent-model
├── api/
│   ├── ModelEndpoint.java      # 模型端点接入契约
│   ├── ModelRouter.java        # 路由策略契约
│   └── dto/ ModelConfig, ChatRequest, ChatResponse
├── endpoint/
│   ├── EndpointAccessCenter.java  # 端点接入中心
│   └── HealthProbe.java           # fallback 与健康探测
├── route/
│   └── ModelRouterImpl.java       # 多模型分层 / 单模型直通
├── crypto/
│   └── KeyCipher.java          # PBKDF2 / OS 密钥链加密
├── prompt/
│   ├── SystemPromptAssembler.java  # 静态/动态分界组装
│   └── PromptBoundary.java         # 静态可缓存前缀 + 动态边界
├── config/
│   └── ModelConfigStore.java   # 配置存 <frameworkRoot>/.config/
└── config/ ModelModuleConfig.java
```

**目录落盘（§3.0）**：模型 Key 与端点 URL 加密存 `<frameworkRoot>/.config/`。

---

## 三、实现思路（核心设计）

### 3.1 本地配置直连（§4.14，无平台 ID）

- 用户在本地配置模型端点与 Key，框架仅**读取 `<frameworkRoot>/.config/` 配置去调用 LLM**。
- 全程不向平台上报 Key/URL、不引入平台专属 ID、不做激活配对/token 校验。
- 用户本地存 Key 与 URL 即可，框架职责是**保障其不被泄露**。

### 3.2 Key 加密（防泄露手段）

- `KeyCipher` 用**用户口令派生（PBKDF2）或 OS 密钥链**加密存 `<frameworkRoot>/.config/`，禁止随包硬编码。
- 口令丢失走"重填 Key"应急。只要本机落盘加密、内存不外泄即满足"Key 不泄露"（R 已明确，非独立风险项）。
- 泄露防护边界：框架自身不存储、不回传用户 Key；第三方模型风险由用户 Key 责任方承担（明示）。

### 3.3 路由策略（§6.3）

- 多模型时分层：压缩/匹配用小模型，主推理用大模型。
- 单模型 ToC 下退化为**直通**（无轻量模型），降本主手段转为压缩/缓存/Prompt 精简/步数上限。
- `ModelRouterImpl` 按策略选端点。

### 3.4 提示词静态/动态分界（八项差距 D17）

- `SystemPromptAssembler` 将提示词拆为静态可缓存部分与动态部分，边界标记固定。
- 静态部分保持字节级稳定，动态部分（记忆/会话/环境/MCP 配置）在边界后实时组装。
- 五级覆盖优先级固定：Override > Coordinator > Agent > Custom > Default。
- `cache_control` 断点按模型供应商能力在 Phase 2 接入；子代理 `inherit` 缓存共享后续支持。

### 3.5 健康探测与 fallback

- `HealthProbe` 周期探活，主端点失效自动切备用（fallback）。

---

## 四、关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| model.config.dir | .config | 加密配置落盘 |
| model.cipher | PBKDF2 | 加密方式 |
| model.fallback.enabled | true | 备用端点切换 |
| model.healthProbeSec | 60 | 探活周期 |
| model.context.threshold | 0.9 | 上下文占用率自动压缩阈值（默认 90%，可配置） |
| model.prompt.staticBoundary | __SYSTEM_PROMPT_DYNAMIC_BOUNDARY__ | 静态/动态提示词分界 |

---

## 五、与其他模块关系

- `agent-core`：提供模型端点，受在途数控制（§2.2 信号量）。
- `agent-common`：并发守卫（在途数信号量）。
- `agent-web`：前端模型接入配置入口。

---

## 六、具体设计

> 核心思路：Spring AI `ChatModel` 是辅助模型接入层；`SpringAiModelAdapter` 实现 LangChain4j `ChatLanguageModel`；模型/嵌入/向量库由 Spring AI 辅助适配，LangChain4j + LangGraph4j 承担编排。直接读 `<frameworkRoot>/.config/` 配置对接 LLM；加密用 common 加密工具；在途数用 common 并发守卫（信号量）。不引入平台身份。本模块只做职责、流程与边界层面的设计，不涉及具体代码实现。

### 6.1 复用 LangChain4j 的方式
- 通过 Spring AI（辅助层）`ChatModel` 适配 DashScope/OpenAI 等端点，`SpringAiModelAdapter` 转成 LangChain4j `ChatLanguageModel`，core 只依赖 LangChain4j 抽象。
- 多模型路由在 `SpringAiModelAdapter`（LangChain4j `ChatLanguageModel`）之上做选择，对下游透明。

### 6.2 核心职责
- **模型路由**：持有经 LangChain4j/LangGraph4j 适配的模型包装列表，对外提供统一 `chat` 能力；调用前经并发守卫获取在途数许可（R6 防护），结束释放。
- **路由选择**：单模型退化为直通；多模型时按请求类型分层（主推理用大模型，压缩/匹配用小模型），对下游透明。
- **端点访问中心**：从 `<frameworkRoot>/.config/` 读取端点配置与 Key（加密）。
- **提示词组装**：`SystemPromptAssembler` 按静态/动态分界组装系统提示词，静态前缀保持稳定，为缓存命中预留断点。
- **健康探测**：周期探活，主端点失效自动切备用（fallback）。

### 6.3 加密读写（接 common 加密工具）
- 保存：用合成密钥（PBKDF2(pass,salt,60000) 或 OS 密钥链）做 AES-GCM 加密 → `<frameworkRoot>/.config/model.enc`。
- 读取：用时解密，调用完置 `null`（内存不外泄）。

### 6.4 设计模式应用
- **策略模式**：路由按单/多模型切换策略，未来加新策略不改调用方。
- **装饰器/代理**：模型包装外面包一层在途数信号量 + fallback，对 core 透明。
- **桥接**：加密工具与"PBKDF2/OS 密钥链"两实现解耦。

### 6.5 验收点（具体）

- [ ] `chat` 发起前无网络请求携带 Key/URL 到平台域名（抓包验证零上报）。
- [ ] `<frameworkRoot>/.config/model.enc` 为密文；解密后对象用毕 `null`，无明文残留文件。
- [ ] 单端点配置时路由退化为直通；配两模型时压缩类请求走小模型。
- [ ] 主端点探活失败自动切备用，返回正常应答。
- [ ] 并发 20 个 `chat`，同时打模型数 ≤ 信号量上限（默认 8），无线程耗尽。
- [ ] 口令丢失走"重填 Key"流程，旧密文可重写。
- [ ] 静态提示词前缀在多轮会话中字节级稳定，动态内容不混入静态前缀。
- [ ] 模型供应商支持时，cache read/write 指标可观测；子代理 inherit 生效。

---

## 七、Token 实时消费监控（问题 5：实时 token 监控）

> 模型模块是 token 计量的唯一权威源——每次 `generate` 都回写用量，经 common 埋点落到本机 `.logs/`，并随 SSE `token` 事件推给前端实时展示。

### 7.1 监控数据来源
- **实时消费**：`SpringAiModelAdapter` 在每次生成后解析 `usage`（prompt_tokens + completion_tokens），累加进"本次会话/本用户"计数器。
- **在途数监控**：`ConcurrencyGuard`（信号量）暴露当前在途请求数，用于"流量/并发"监控（呼应问题 5 的流量监控）。
- **额度水位**：本地配置可设"用户额度上限"，实时用量与之比对，临近阈值预警。

### 7.2 展示与告警
- 前端：每个 SSE `token` 事件携带增量，绘制"会话 token 进度条 + 实时速率"。
- 本机：`.logs/` 留存用量序列（不上云），供"风险/流量监控面板"回放（问题 5 的流量与风险监控落地在 web + common 埋点）。
- 预警：用量达额度 80% 时，经 `token` 事件带 `warn` 标志提示用户。

### 7.3 验收点
- [ ] 连续 5 轮对话，前端进度条累计值 = 各轮 `usage` 之和。
- [ ] 在途数监控显示当前并发请求数，压测 20 并发时数值 ≤ 信号量上限（默认 8）。
- [ ] 用量达 80% 阈值时前端收到 `warn` 提示。

### 7.4 上下文占用率 90% 阈值自动压缩（新增）

> 与 7.2 的"额度水位 80% 预警"不同，本阈值针对**单会话上下文占用**：目标是上下文撑爆窗口前主动压缩，而非额度预算告警。

- **占用率计量**：`SpringAiModelAdapter` 每次 `generate` 后回写 usage（prompt+completion），core 按会话累计；`上下文占用率 = 本次会话已用 token ÷ 模型上下文窗口`（窗口来自模型配置 `<frameworkRoot>/.config/model.contextWindow`，缺省按模型名默认表）。
- **阈值判断**：默认 `model.context.threshold = 0.9`（90%，可配置）。**每次模型调用后由 core 检查占用率，达到/超过阈值即自动触发压缩**（经 common 观察者发出 `compress_requested` 事件），不等定时任务。
- **压缩后回落**：压缩（滑动窗口/摘要/下沉，见 memory/cache 模块）完成后占用率回落到阈值以下，会话继续；若保全内容过多单次压缩无法回落，强制转 ASK 告知用户"上下文已接近上限"。
- **保全**：计划/安全约束/关键结果不下沉（与 core §8.2 一致），压缩只丢弃过程类内容。

### 7.5 验收点（新增）
- [ ] 构造占用率 89% 的会话，压缩不触发；补一条消息到 ≥90%，`compress_requested` 事件发出且上下文回落至阈值以下。
- [ ] 压缩后占用率 ≤ `model.context.threshold`（默认 0.9），会话可继续正常推理。
- [ ] 保全内容（计划/约束/关键结果）在压缩后仍可召回；仅压缩无法回落时转 ASK。

---

## 八、额度/调用失败兜底（问题 6：LLM 额度不够）

> 模型层是 LLM 资源的最终守门人，兜底策略分级，保证"一个端点挂了不全崩"。

### 8.1 额度不够
- 检测：生成返回 `429/quota_exceeded` 或本地用量达额度上限。
- 兜底：① 多模型时自动切备用端点（router fallback）；② 单模型/无备用则经 SSE `error` 事件明确告知"额度不足，请前往配置补充 Key"，并暂停自动执行转人工（不静默失败、不伪造结果）。

### 8.2 端点故障
- `HealthProbe` 探活失败 → router 切备用；无备用则抛降级，`core` 把该步转 ASK 告知用户。

### 8.3 其他 LLM 异常
- 超时/限流（429 非额度）：指数退避重试有限次，仍失败则转 ASK。
- 返回内容异常：拒绝把异常体当答案，记 `.logs/` 并由 core 决定重规划或 ASK。

### 8.4 验收点
- [ ] 构造额度耗尽，前端收到明确"额度不足"提示且自动执行暂停，无静默伪造回复。
- [ ] 主端点返回 429，router 切备用成功返回正常应答（多模型配置下）。
- [ ] 模拟超时，重试有限次后仍失败，对话不崩、转 ASK 并记日志。
