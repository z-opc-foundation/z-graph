package com.zifang.z.graph.core.storage;

/**
 * 引擎可见性判定：{@code commitSeq} 是否落在某个 ref 的祖先闭包内。
 *
 * <p>读路径全部经由它解析「该实体在 ref 上可见的最新版本」；Phase 2 的
 * AncestryIndex 是它的增量维护实现，测试可用闭包集合直接实现。</p>
 */
public interface Visibility {

    boolean isVisible(long commitSeq);
}
