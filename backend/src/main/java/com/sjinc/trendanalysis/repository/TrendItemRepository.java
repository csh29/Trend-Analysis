package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrendItemRepository extends JpaRepository<TrendItem, Long> {

    @EntityGraph(attributePaths = "values")
    Optional<TrendItem> findByDocumentId(Long documentId);

    @EntityGraph(attributePaths = "values")
    List<TrendItem> findByDocumentIdIn(Collection<Long> documentIds);
}
