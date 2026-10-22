package com.sjinc.trendanalysis.service.image;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.dto.analysis.AnalysisSummary;
import com.sjinc.trendanalysis.dto.image.ImageUploadResult;
import com.sjinc.trendanalysis.dto.image.ImageView;
import com.sjinc.trendanalysis.repository.UploadedImageRepository;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이미지 저장·조회·삭제 — 이미지 업로드 화면(따로 올린 이미지 = 본문 없는 원문)과 텍스트 원문 첨부가 같이 쓴다.
 * 파일은 디스크(image.upload.dir)에, 메타정보는 DB(UploadedImage)에 둔다.
 * 디스크 쓰기는 트랜잭션에 묶이지 않으므로, 여러 장을 올릴 때는 모두 검증한 뒤 쓰고 DB 저장이 실패하면 쓴 파일을 지운다.
 */
@Service
@Slf4j
public class ImageService {

    /** 한 번에 올릴 수 있는 장수. 크기 상한(ImageUploadConfig)과 별개로 요청 하나가 너무 커지지 않게 막는다. */
    static final int MAX_FILES_PER_UPLOAD = 50;
    private static final int MAX_NAME_LENGTH = 200;

    private final UploadedImageRepository uploadedImageRepository;
    private final DocumentService documentService;
    private final Path uploadDir;

    public ImageService(UploadedImageRepository uploadedImageRepository, DocumentService documentService,
                        @Value("${image.upload.dir:./uploads/images}") String uploadDir) {
        this.uploadedImageRepository = uploadedImageRepository;
        this.documentService = documentService;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** 목록 — 따로 올린 이미지만(텍스트 원문 첨부는 텍스트 등록 화면에서 본다). 이미지마다 분석(P-03) 상태와 요약을 붙인다. */
    public List<ImageView> getImages() {
        return withAnalysis(uploadedImageRepository.findByDocumentIdIsNullOrderByUploadedAtDescIdDesc());
    }

    private List<ImageView> withAnalysis(List<UploadedImage> images) {
        Map<Long, AnalysisSummary> summaries = documentService.summariesByImageIds(
                images.stream().map(UploadedImage::getId).toList());
        return images.stream().map(image -> ImageView.from(image, summaries.get(image.getId()))).toList();
    }

    /**
     * 여러 장을 한 번에 올린다. 한 장이라도 이미지가 아니면 아무것도 저장하지 않고 그 파일명들을 알려준다.
     * 이미 올린 이미지(파일 내용이 같은 것)와 같은 요청 안의 중복은 건너뛰고 나머지만 저장한다 — 같은 문서가 두 번 집계되지 않게.
     * 한 장마다 본문 없는 원문(IMAGE) 하나가 된다.
     */
    public ImageUploadResult upload(List<MultipartFile> files, String username) {
        Prepared prepared = prepare(files, MAX_FILES_PER_UPLOAD);
        if (prepared.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지를 선택하세요.");
        }
        if (prepared.fresh().isEmpty()) {
            return new ImageUploadResult(List.of(), prepared.duplicates());
        }
        List<UploadedImage> saved = store(prepared, username, null);
        // 저장이 끝난 뒤 분석(P-03·P-04) 대기열에 넣는다. 업로드 응답은 분석을 기다리지 않는다.
        documentService.registerImages(saved, username);
        return new ImageUploadResult(withAnalysis(saved), prepared.duplicates());
    }

    /**
     * 저장 전 검증 — 형식(파일 내용으로 확인)·장수·중복. 원문을 만들기 전에 불러, 잘못된 파일이면 원문도 만들지 않게 한다.
     * 빈 파일 칸은 무시한다. 중복은 오류가 아니라 건너뛸 목록(duplicates)으로 돌려준다.
     */
    public Prepared prepare(List<MultipartFile> files, int maxFiles) {
        List<MultipartFile> targets = files == null ? List.of()
                : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (targets.size() > maxFiles) {
            throw new IllegalArgumentException("이미지는 한 번에 " + maxFiles + "장까지 올릴 수 있습니다.");
        }
        List<ImageType> types = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (MultipartFile file : targets) {
            ImageType.detect(readHeader(file)).ifPresentOrElse(types::add, () -> rejected.add(displayName(file)));
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일(jpg·png·gif·webp·bmp)이 아닙니다: " + String.join(", ", rejected));
        }

        // 중복 거르기: 먼저 DB의 같은 해시(따로 올린 것·첨부 모두), 다음 같은 요청 안의 앞 파일
        List<String> hashes = targets.stream().map(ImageService::sha256).toList();
        Map<String, String> known = new HashMap<>();
        uploadedImageRepository.findByContentHashIn(hashes)
                .forEach(image -> known.putIfAbsent(image.getContentHash(), image.getOriginalName()));
        List<ImageUploadResult.Duplicate> duplicates = new ArrayList<>();
        List<PreparedFile> fresh = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            String existing = known.putIfAbsent(hashes.get(i), displayName(targets.get(i)));
            if (existing == null) {
                fresh.add(new PreparedFile(targets.get(i), types.get(i), hashes.get(i)));
            } else {
                duplicates.add(new ImageUploadResult.Duplicate(displayName(targets.get(i)), existing));
            }
        }
        if (!duplicates.isEmpty()) {
            log.info("이미 올린 이미지 {}장을 건너뜁니다: {}", duplicates.size(),
                    duplicates.stream().map(ImageUploadResult.Duplicate::fileName).toList());
        }
        return new Prepared(fresh, duplicates);
    }

    /** 텍스트 원문에 이미지를 붙인다(prepare를 통과한 것만). 원문 문서가 먼저 저장돼 있어야 한다. */
    public List<UploadedImage> attach(Long documentId, Prepared prepared, String username) {
        if (prepared.fresh().isEmpty()) {
            return List.of();
        }
        return store(prepared, username, documentId);
    }

    /** 디스크에 쓰고 DB에 넣는다. 중간에 실패하면 이번에 쓴 파일을 지운다. */
    private List<UploadedImage> store(Prepared prepared, String username, Long documentId) {
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(uploadDir);
            LocalDateTime now = LocalDateTime.now();
            List<UploadedImage> images = new ArrayList<>();
            int order = 0;
            for (PreparedFile f : prepared.fresh()) {
                String storedName = UUID.randomUUID() + "." + f.type().getExtension();
                Path target = uploadDir.resolve(storedName);
                try (InputStream in = f.file().getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
                images.add(UploadedImage.builder()
                        .originalName(displayName(f.file()))
                        .storedName(storedName)
                        .contentType(f.type().getMimeType())
                        .fileSize(f.file().getSize())
                        .uploadedBy(username)
                        .uploadedAt(now)
                        .contentHash(f.hash())
                        .documentId(documentId)
                        .sortOrder(documentId == null ? null : order++)
                        .build());
            }
            return uploadedImageRepository.saveAll(images);
        } catch (IOException | RuntimeException e) {
            written.forEach(this::deleteQuietly);
            if (e instanceof IOException io) {
                throw new UncheckedIOException("이미지 저장에 실패했습니다.", io);
            }
            throw (RuntimeException) e;
        }
    }

    /** 해시 칸이 생기기 전에 올린 이미지의 해시를 채운다 — 그래야 예전 이미지를 다시 올려도 중복으로 걸린다. */
    @EventListener(ApplicationReadyEvent.class)
    public void fillMissingHashes() {
        List<UploadedImage> missing = uploadedImageRepository.findByContentHashIsNull();
        int filled = 0;
        for (UploadedImage image : missing) {
            Path path = resolveStored(image.getStoredName());
            if (!Files.isRegularFile(path)) {
                continue; // 파일이 없어진 이미지 — 원본 보기에서 따로 드러난다
            }
            try (InputStream in = Files.newInputStream(path)) {
                image.assignContentHash(sha256(in));
                uploadedImageRepository.save(image);
                filled++;
            } catch (IOException e) {
                log.warn("이미지 해시를 채우지 못했습니다 image={}: {}", image.getId(), e.getMessage());
            }
        }
        if (filled > 0) {
            log.info("예전에 올린 이미지 {}장의 중복 확인용 해시를 채웠습니다.", filled);
        }
    }

    private static String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return sha256(in);
        } catch (IOException e) {
            throw new UncheckedIOException("이미지를 읽지 못했습니다: " + displayName(file), e);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // JDK에 항상 있다
        }
    }

    /** 분석(AnalysisService)이 Claude에 보낼 원문의 이미지 전부 — 따로 올린 이미지 원문이면 한 장, 텍스트 원문이면 첨부(순서대로). */
    public List<ImageBytes> readDocumentImages(SourceDocument doc) {
        if (doc.getImageId() != null) {
            return List.of(readBytes(uploadedImageRepository.findById(doc.getImageId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이미지입니다: " + doc.getImageId()))));
        }
        return uploadedImageRepository.findByDocumentIdOrderBySortOrderAscIdAsc(doc.getId()).stream()
                .map(this::readBytes).toList();
    }

    private ImageBytes readBytes(UploadedImage image) {
        try {
            return new ImageBytes(Files.readAllBytes(resolveStored(image.getStoredName())), image.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("이미지 파일을 읽지 못했습니다: " + image.getOriginalName(), e);
        }
    }

    /** 화면 표시·다운로드용 파일. 저장 폴더 밖을 가리키면(DB 값이 오염된 경우) 열지 않는다. */
    public ImageFile getFile(Long id) {
        return toFile(uploadedImageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이미지입니다: " + id)));
    }

    /** 텍스트 원문 첨부 보기 — 그 원문에 딸린 이미지일 때만(다른 원문·따로 올린 이미지 id로 열지 못하게). */
    public ImageFile getAttachmentFile(Long documentId, Long imageId) {
        return toFile(uploadedImageRepository.findById(imageId)
                .filter(image -> documentId.equals(image.getDocumentId()))
                .orElseThrow(() -> new IllegalArgumentException("이 원문의 이미지가 아닙니다: " + imageId)));
    }

    private ImageFile toFile(UploadedImage image) {
        Path path = resolveStored(image.getStoredName());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("이미지 파일이 없습니다: " + image.getOriginalName());
        }
        return new ImageFile(new PathResource(path), image.getContentType(), image.getOriginalName());
    }

    /** 선택한 이미지를 지운다. DB 행을 먼저 지우고 파일은 그 다음에 지운다(파일 삭제 실패는 로그만 — 목록에서 사라지는 게 우선). */
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("삭제할 이미지를 선택하세요.");
        }
        // 텍스트 원문 첨부는 여기서 지우지 않는다 — 원문을 지울 때 같이 지운다(deleteAttachments)
        List<UploadedImage> images = uploadedImageRepository.findAllById(ids).stream()
                .filter(image -> image.getDocumentId() == null).toList();
        documentService.deleteByImageIds(images.stream().map(UploadedImage::getId).toList());
        removeImages(images);
    }

    /** 텍스트 원문을 지울 때 그 첨부 이미지(행·파일)를 같이 지운다. */
    public void deleteAttachments(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        removeImages(uploadedImageRepository.findByDocumentIdIn(documentIds));
    }

    private void removeImages(List<UploadedImage> images) {
        uploadedImageRepository.deleteAll(images);
        images.forEach(image -> deleteQuietly(resolveStored(image.getStoredName())));
    }

    private Path resolveStored(String storedName) {
        Path path = uploadDir.resolve(storedName).normalize();
        if (!path.startsWith(uploadDir)) {
            throw new IllegalArgumentException("잘못된 저장 경로입니다.");
        }
        return path;
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageType.HEADER_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다: " + displayName(file), e);
        }
    }

    /** 표시용 파일명 — 브라우저가 경로까지 보내는 경우가 있어 마지막 이름만 남기고 길이를 자른다. */
    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).trim();
        if (name.isEmpty()) {
            name = "image";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("이미지 파일을 지우지 못했습니다: {}", path, e);
        }
    }

    /** prepare를 통과한 파일들. fresh는 저장할 것, duplicates는 이미 있어 건너뛴 것. */
    public record Prepared(List<PreparedFile> fresh, List<ImageUploadResult.Duplicate> duplicates) {
        public boolean isEmpty() {
            return fresh.isEmpty() && duplicates.isEmpty();
        }
    }

    public record PreparedFile(MultipartFile file, ImageType type, String hash) {
    }

    /** 원본 바이트와 형식(업로드 때 파일 내용으로 확인한 값). */
    public record ImageBytes(byte[] bytes, String contentType) {
    }

    /** 내려줄 파일과 그 형식. */
    public record ImageFile(Resource resource, String contentType, String originalName) {
    }
}
