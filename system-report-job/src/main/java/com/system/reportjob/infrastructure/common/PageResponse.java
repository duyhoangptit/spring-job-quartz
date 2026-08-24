package com.system.reportjob.infrastructure.common;

import java.util.List;
import java.util.function.Function;

import com.system.reportjob.domain.model.PageResult;

public record PageResponse<T>(List<T> data, int currentPage, int pageSize, long totalElements, int totalPages) {
    public static <S, T> PageResponse<T> from(PageResult<S> pageResult, Function<S, T> mapper) {
        return new PageResponse<>(
                pageResult.content().stream().map(mapper).toList(),
                pageResult.page(),
                pageResult.size(),
                pageResult.totalElements(),
                pageResult.totalPages());
    }
}
