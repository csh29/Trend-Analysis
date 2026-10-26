package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 때 색인과 DB 양쪽 맞추기 — DB에 없는 문서의 색인은 지우고(안전장치 안에서), 색인이 빠진 분석 완료 문서는 저장된 결과로 다시 색인한다.
 * 테스트 설정은 index.sync.enabled=false라 기동 때는 돌지 않고, 여기서 sync()를 직접 부른다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:indexsynctest")
class IndexSyncServiceTest {

    @MockBean
    private AiClient aiClient;
    @Autowired
    private IndexSyncService indexSyncService;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;
    @Autowired
    private TrendItemRepository trendItemRepository;

    @BeforeEach
    void clear() {
        trendItemRepository.deleteAll();
        sourceDocumentRepository.deleteAll();
    }

    private SourceDocument document(String docId, String status) {
        return sourceDocumentRepository.save(SourceDocument.builder().docType(SourceDocument.TYPE_TEXT).docId(docId).title(docId)
                .body("본문 " + docId).media("m").publishedAt(LocalDate.of(2026, 10, 20)).status(status)
                .registeredBy("admin").registeredAt(LocalDateTime.now()).build());
    }

    @Test
    void DB에_없는_문서의_색인만_지운다() {
        document("IMG-S-1", SourceDocument.DONE);
        document("IMG-S-2", SourceDocument.PENDING); // 재분석 대기 — 새 결과로 다시 색인되므로 둔다
        document("IMG-S-3", SourceDocument.DONE);
        when(aiClient.indexedDocIds()).thenReturn(List.of("IMG-S-1", "IMG-S-2", "IMG-S-3", "IMG-GONE"));

        IndexSyncService.Result result = indexSyncService.sync();

        assertThat(result.deleted()).containsExactly("IMG-GONE");
        verify(aiClient).deleteIndex("IMG-GONE");
        verify(aiClient, never()).deleteIndex("IMG-S-1");
        verify(aiClient, never()).deleteIndex("IMG-S-2");
    }

    @Test
    void DB가_비었거나_지울_대상이_절반을_넘으면_지우지_않는다() {
        // 2026-10-26 사고 재현 — 메모리 DB(문서 없음)로 실제 색인 56건을 보고 모두 지웠다
        when(aiClient.indexedDocIds()).thenReturn(IntStream.range(0, 56).mapToObj(i -> "TXT-" + i).toList());
        IndexSyncService.Result empty = indexSyncService.sync();
        assertThat(empty.deleted()).isEmpty();
        assertThat(empty.deleteSkipped()).hasSize(56);

        document("TXT-0", SourceDocument.DONE); // 1건만 맞고 55건이 DB에 없다 — 절반이 넘는다
        IndexSyncService.Result most = indexSyncService.sync();
        assertThat(most.deleted()).isEmpty();
        assertThat(most.deleteSkipped()).hasSize(55);
        verify(aiClient, never()).deleteIndex(any());
    }

    @Test
    void 색인이_빠진_분석_완료_문서는_저장된_결과로_다시_색인한다() {
        SourceDocument done = document("TXT-RE-1", SourceDocument.DONE);
        document("TXT-RE-2", SourceDocument.FAILED); // 분석 실패 — 결과가 없어 색인하지 않는다
        trendItemRepository.save(TrendItem.builder().documentId(done.getId()).category("OUTER").summary("요약")
                .resultJson("""
                        {"doc_id": "TXT-RE-1", "items": [{"category": "OUTER", "item": "레더 재킷", "type": "OUTER_LEATHER_JACKET",
                          "materials": ["LEATHER"], "colors": ["BLACK"], "silhouettes": []}],
                         "keywords": ["레더"], "seasons": ["FALL"], "summary": "요약", "source": {"media": "m"}}""")
                .createdAt(LocalDateTime.now()).build());
        when(aiClient.indexedDocIds()).thenReturn(List.of());

        IndexSyncService.Result result = indexSyncService.sync();

        assertThat(result.reindexed()).containsExactly("TXT-RE-1");
        assertThat(result.failed()).isEmpty();
        ArgumentCaptor<AiClient.IndexRequest> sent = ArgumentCaptor.forClass(AiClient.IndexRequest.class);
        verify(aiClient).index(sent.capture());
        AiClient.IndexRequest request = sent.getValue();
        assertThat(request.docId()).isEqualTo("TXT-RE-1");
        assertThat(request.text()).isEqualTo("본문 TXT-RE-1");
        // 분석 때 보낸 키만(doc_id·source 빼고), 코드 이름도 같이
        assertThat(request.structured()).containsOnlyKeys("items", "keywords", "seasons", "summary");
        assertThat(request.names()).containsEntry("LEATHER", "레더(가죽)");
        assertThat(request.meta()).containsEntry("published_at", "2026-10-20");
    }
}
