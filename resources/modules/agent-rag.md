# agent-rag 模块实现文档

> 对应需求：待办「RAG 模块 p4」；技术底座 LangChain4j RAG 组件（核心）+ Spring AI VectorStore/EmbeddingModel（辅助）。

## 一、模块定位
提供向量库抽象、数据源管道、分块/嵌入、混合召回与评估闭环。

## 二、实现框架
```
agent-rag
├── api/
│   ├── VectorStoreProvider.java # 多向量库抽象
│   ├── Retriever.java           # 召回契约
│   └── dto/ Chunk, RetrievalHit
├── ingest/
│   ├── DataSourcePipeline.java  # 抓取→清洗→分块→嵌入
│   └── SyncManager.java         # 增量同步
├── recall/
│   ├── DenseRetriever.java      # 稠密向量召回
│   ├── SparseRetriever.java     # BM25 稀疏召回
│   └── HybridFusion.java        # RRF 融合
├── adapter/
│   └── SpringAiVectorAdapter.java # Spring AI VectorStore → LangChain4j EmbeddingStore/ContentRetriever
└── eval/
    ├── OfflineEvaluator.java    # 召回率/NDCG
    └── FeedbackLoop.java        # 在线反馈
```

## 三、核心设计
- 形式：Naive RAG / ReAct RAG / Agentic RAG / Graph RAG，按场景选择。
- 向量库由 Spring AI（辅助层）`VectorStore`/`EmbeddingModel` 提供，`SpringAiVectorAdapter` 桥接到 LangChain4j `EmbeddingStore`/`ContentRetriever`；集合按知识库分库，元数据与向量分离。
- 默认混合召回（稠密 + BM25 + RRF），重排序后取 Top-K。
- 数据源接入为管道化处理，支持定时/事件增量更新。
- 评估闭环：离线指标 + 在线反馈 → 调参 → 再评估。

## 四、验收点
- [ ] 向量库引擎可替换，集合隔离正确。
- [ ] 混合召回结果优于单一召回（指标可观测）。
- [ ] 文档变更触发增量同步，索引可重建。
- [ ] 数据与索引仅在本机，无云端存储。