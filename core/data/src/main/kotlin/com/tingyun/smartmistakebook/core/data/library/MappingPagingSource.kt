package com.tingyun.smartmistakebook.core.data.library

import androidx.paging.PagingSource
import androidx.paging.PagingState

internal class MappingPagingSource<Value : Any, R : Any>(
    private val delegate: PagingSource<Int, Value>,
    private val transform: (Value) -> R,
) : PagingSource<Int, R>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, R> {
        val result = delegate.load(params)
        return when (result) {
            is LoadResult.Page -> LoadResult.Page(
                data = result.data.map(transform),
                prevKey = result.prevKey,
                nextKey = result.nextKey,
            )

            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, R>): Int? = null
}
