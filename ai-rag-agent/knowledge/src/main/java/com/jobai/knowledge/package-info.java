/**
 * 知识库模块
 *
 * 文档解析 → LangChain4j 切片 → Embedding 向量化 → ES 入库
 *
 * 依赖 LangChain4j 管线：
 *   DocumentSplitters.recursive()  — 递归文本切片
 *   SiliconFlowEmbeddingModel      — EmbeddingModel 实现
 *   ElasticsearchEmbeddingStoreAdapter — EmbeddingStore 实现
 *   HeadingPathTransformer         — 标题路径上下文传播
 *
 * 业务状态追踪与 MySQL 存储独立于 LangChain4j 管线之外。
 *
 * 核心包结构：
 *   parser       - 文档解析（Markdown / PDF）
 *   splitter     - LangChain4j 文本切片 + 标题路径上下文传播
 *   embedding    - EmbeddingModel / 向量化实现
 *   store        - ES 向量入库 / 删除管理
 *   dto          - 传输对象
 *   service      - 文档业务处理与状态管理
 */
package com.jobai.knowledge;
