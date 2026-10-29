package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.SourceDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

    /** 상태가 from일 때만 to로 바꾼다 — 바꾼 행 수(0이면 이미 다른 상태). 분석을 한 요청만 맡게 하는 데 쓴다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SourceDocument d set d.status = :to where d.id = :id and d.status = :from")
    int changeStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** 상태가 from인 문서를 모두 to로 바꾼다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SourceDocument d set d.status = :to where d.status = :from")
    int changeAllStatus(@Param("from") String from, @Param("to") String to);

    List<SourceDocument> findByImageIdIn(Collection<Long> imageIds);

    Optional<SourceDocument> findByImageId(Long imageId);

    List<SourceDocument> findByDocTypeOrderByRegisteredAtDescIdDesc(String docType);

    /** 서버가 분석 도중 내려갔으면 대기·처리 중으로 남는다 — 기동 시 다시 맡긴다. */
    List<SourceDocument> findByStatusIn(Collection<String> statuses);

    /** 샘플 데이터(SampleDataLoader)를 이미 넣었는지. */
    boolean existsByTitleStartingWith(String prefix);

    /** RAG 근거(Chroma 메타의 문서 ID)를 원문으로 되돌린다. */
    List<SourceDocument> findByDocIdIn(Collection<String> docIds);

    Optional<SourceDocument> findByDocId(String docId);

    /** 유행 질문 집계(TrendFactsService) — 기간 안 분석 완료 원문. 집계 날짜 = 게시일(필수 칸). */
    List<SourceDocument> findByStatusAndPublishedAtBetween(String status, java.time.LocalDate from, java.time.LocalDate to);

    List<SourceDocument> findByStatus(String status);

    /** 같은 원문 확인(정규화한 URL — SourceUrls). */
    Optional<SourceDocument> findFirstByUrl(String url);

    /** 분석 로그의 대기·처리 중 건수. */
    long countByStatus(String status);

    /**
     * 분석 결과 조회 화면 — 분석 완료 원문 중 카테고리·아이템 종류(없으면 전체)에 맞는 의류 아이템이 있는 것, 최근 게시일부터 페이지로.
     * 아이템 배열 이전 결과(garment_no 없음)는 문서 카테고리로 본다.
     */
    @Query(value = """
            SELECT d.* FROM source_documents d
            WHERE d.status = 'DONE' AND EXISTS (
                SELECT 1 FROM trend_items t JOIN trend_item_values v ON v.trend_item_id = t.trend_item_id
                WHERE t.document_id = d.document_id
                  AND (:category IS NULL OR v.category = :category OR (v.garment_no IS NULL AND t.category = :category))
                  AND (:itemType IS NULL OR (v.value_type = 'ITEM_TYPE' AND v.item_value = :itemType)))
            ORDER BY d.published_at DESC, d.document_id DESC""",
            countQuery = """
            SELECT COUNT(*) FROM source_documents d
            WHERE d.status = 'DONE' AND EXISTS (
                SELECT 1 FROM trend_items t JOIN trend_item_values v ON v.trend_item_id = t.trend_item_id
                WHERE t.document_id = d.document_id
                  AND (:category IS NULL OR v.category = :category OR (v.garment_no IS NULL AND t.category = :category))
                  AND (:itemType IS NULL OR (v.value_type = 'ITEM_TYPE' AND v.item_value = :itemType)))""",
            nativeQuery = true)
    Page<SourceDocument> findAnalyzed(@Param("category") String category, @Param("itemType") String itemType, Pageable pageable);
}
