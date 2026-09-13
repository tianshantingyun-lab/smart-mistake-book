package com.tingyun.smartmistakebook.core.data.library

import androidx.paging.PagingSource
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogItem
import com.tingyun.smartmistakebook.core.domain.LibraryQuery

/**
 * 「这张表怎么分页」是**数据层**的事，不是领域的事（审计 R-02）。
 *
 * `core:domain` 的 `LibraryCatalogRepository` 原先直接返回 `PagingSource`，于是领域层
 * 挂上了 `androidx.paging`——一个 UI/分页框架。现在域接口只保留页形状的读
 * （`query(offset, limit)` 与 `totalCount`），而**需要 Paging 的消费方**（feature 的 `Pager`）
 * 由装配点把这里交进去。`LibraryRoute` 的 `catalogPagingSource` 参数因此是一个函数类型，
 * feature 不必依赖 `core:data`。
 *
 * 保留这条路而不是在 feature 里用 `query(offset, limit)` 现造一个 PagingSource，
 * 是因为底下的实现是 **Room 自己的 PagingSource**：它带表失效（删了一题，列表自己刷新）
 * 与计数语义，换成手写的 offset/limit 版本会把这些一起丢掉。
 */
interface LibraryPagingSourceProvider {
    fun pagingSource(query: LibraryQuery): PagingSource<Int, LibraryCatalogItem>
}
