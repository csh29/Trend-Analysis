package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrendItemRepository extends JpaRepository<TrendItem, Long> {

    @EntityGraph(attributePaths = "values")
    Optional<TrendItem> findByDocumentId(Long documentId);

    @EntityGraph(attributePaths = "values")
    List<TrendItem> findByDocumentIdIn(Collection<Long> documentIds);

    /** 근거 원문 찾기 — 값 종류로 좁히고 값 비교(키워드 공백 정리)는 서비스에서 한다. */
    @Query("select distinct i from TrendItem i join i.values v where v.valueType = :valueType")
    List<TrendItem> findHavingValueType(@Param("valueType") String valueType);

}
