/**
 * RAG 检索模块
 *
 * 向量检索 + BM25 关键词检索 → 混合召回 → 重排序（待定）
 *
 * 核心包结构：
 * retriever      - 检索器（向量 / BM25 / 混合）
 * rerank         - 重排序（可选）
 * dto            - 传输对象
 * service        - 检索服务（待实现）
 */
package com.jobai.rag;
