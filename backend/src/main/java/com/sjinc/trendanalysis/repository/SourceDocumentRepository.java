package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.SourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, Long> {

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
}
