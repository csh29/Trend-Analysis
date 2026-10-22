package com.sjinc.trendanalysis.service.analysis;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.dto.analysis.TextRegistrationResult;
import com.sjinc.trendanalysis.dto.analysis.TextDocumentRequest;
import com.sjinc.trendanalysis.service.image.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 텍스트 원문(본문 + 첨부 이미지) 등록·삭제. 원문 1건 = 본문 + 이미지 N장(2026-10-21 사용자 결정) — 기사·잡지·인스타 게시물은
 * 본문과 사진이 한 묶음이라 한 번에 분석해야 같은 트렌드가 여러 번 세지지 않는다.
 * DocumentService(원문)와 ImageService(파일)를 같이 쓰는 순서를 여기서 맞춘다 — ImageService가 DocumentService를 쓰므로
 * DocumentService 안에서는 이미지를 저장할 수 없다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TextRegistrationService {

    /** 원문 하나에 붙이는 이미지 상한. 장마다 Claude 입력 토큰이 늘고 요청 크기도 커진다(AI 서비스 MAX_IMAGES와 같다). */
    public static final int MAX_IMAGES = 5;

    private final DocumentService documentService;
    private final ImageService imageService;

    /**
     * 검증(본문·이미지) → 원문 저장 → 첨부 저장 → 분석 대기열 순. 이미지가 잘못됐으면 원문을 만들지 않는다.
     * 이미 올린 이미지와 같은 파일은 건너뛰고 원문은 등록한다(skippedImages로 알린다).
     */
    public TextRegistrationResult register(TextDocumentRequest request, List<MultipartFile> files, String username) {
        ImageService.Prepared prepared = imageService.prepare(files, MAX_IMAGES);
        SourceDocument doc = documentService.saveText(request, username);
        List<UploadedImage> images;
        try {
            images = imageService.attach(doc.getId(), prepared, username);
        } catch (RuntimeException e) {
            documentService.discard(doc);
            throw e;
        }
        documentService.requestAnalysis(List.of(doc.getId()));
        log.info("텍스트 원문 등록 doc={} 본문 {}자 이미지 {}장(중복 건너뜀 {}장)", doc.getDocId(),
                doc.getBody().length(), images.size(), prepared.duplicates().size());
        return new TextRegistrationResult(documentService.view(doc, images.size()), prepared.duplicates());
    }

    /** 원문과 딸린 트렌드 항목·색인·첨부 이미지를 지운다. */
    public void delete(List<Long> documentIds) {
        documentService.deleteTexts(documentIds);
        imageService.deleteAttachments(documentIds);
    }
}
