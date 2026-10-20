package com.sjinc.trendanalysis.service.image;

import com.sjinc.trendanalysis.domain.UploadedImage;
import com.sjinc.trendanalysis.dto.analysis.AnalysisSummary;
import com.sjinc.trendanalysis.dto.image.ImageView;
import com.sjinc.trendanalysis.repository.UploadedImageRepository;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이미지 업로드 화면의 업로드·조회·삭제. 파일은 디스크(image.upload.dir)에, 메타정보는 DB(UploadedImage)에 둔다.
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

    /** 목록 — 이미지마다 분석(P-03) 상태와 요약을 붙인다. */
    public List<ImageView> getImages() {
        return withAnalysis(uploadedImageRepository.findAllByOrderByUploadedAtDescIdDesc());
    }

    private List<ImageView> withAnalysis(List<UploadedImage> images) {
        Map<Long, AnalysisSummary> summaries = documentService.summariesByImageIds(
                images.stream().map(UploadedImage::getId).toList());
        return images.stream().map(image -> ImageView.from(image, summaries.get(image.getId()))).toList();
    }

    /** 여러 장을 한 번에 올린다. 한 장이라도 이미지가 아니면 아무것도 저장하지 않고 그 파일명들을 알려준다. */
    public List<ImageView> upload(List<MultipartFile> files, String username) {
        List<MultipartFile> targets = files == null ? List.of()
                : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지를 선택하세요.");
        }
        if (targets.size() > MAX_FILES_PER_UPLOAD) {
            throw new IllegalArgumentException("한 번에 " + MAX_FILES_PER_UPLOAD + "장까지 올릴 수 있습니다.");
        }

        List<ImageType> types = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (MultipartFile file : targets) {
            ImageType.detect(readHeader(file)).ifPresentOrElse(types::add, () -> rejected.add(displayName(file)));
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일(jpg·png·gif·webp·bmp)이 아닙니다: " + String.join(", ", rejected));
        }

        List<Path> written = new ArrayList<>();
        List<UploadedImage> saved;
        try {
            Files.createDirectories(uploadDir);
            LocalDateTime now = LocalDateTime.now();
            List<UploadedImage> images = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                MultipartFile file = targets.get(i);
                ImageType type = types.get(i);
                String storedName = UUID.randomUUID() + "." + type.getExtension();
                Path target = uploadDir.resolve(storedName);
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
                images.add(UploadedImage.builder()
                        .originalName(displayName(file))
                        .storedName(storedName)
                        .contentType(type.getMimeType())
                        .fileSize(file.getSize())
                        .uploadedBy(username)
                        .uploadedAt(now)
                        .build());
            }
            saved = uploadedImageRepository.saveAll(images);
        } catch (IOException | RuntimeException e) {
            written.forEach(this::deleteQuietly);
            if (e instanceof IOException io) {
                throw new UncheckedIOException("이미지 저장에 실패했습니다.", io);
            }
            throw (RuntimeException) e;
        }
        // 저장이 끝난 뒤 분석(P-03·P-04) 대기열에 넣는다. 업로드 응답은 분석을 기다리지 않는다.
        documentService.registerImages(saved, username);
        return withAnalysis(saved);
    }

    /** 분석(AnalysisService)이 Claude에 보낼 원본. */
    public ImageBytes readBytes(Long id) {
        UploadedImage image = uploadedImageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이미지입니다: " + id));
        try {
            return new ImageBytes(Files.readAllBytes(resolveStored(image.getStoredName())), image.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("이미지 파일을 읽지 못했습니다: " + image.getOriginalName(), e);
        }
    }

    /** 화면 표시·다운로드용 파일. 저장 폴더 밖을 가리키면(DB 값이 오염된 경우) 열지 않는다. */
    public ImageFile getFile(Long id) {
        UploadedImage image = uploadedImageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이미지입니다: " + id));
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
        List<UploadedImage> images = uploadedImageRepository.findAllById(ids);
        documentService.deleteByImageIds(images.stream().map(UploadedImage::getId).toList());
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

    /** 내려줄 파일과 그 형식. */
    /** 원본 바이트와 형식(업로드 때 파일 내용으로 확인한 값). */
    public record ImageBytes(byte[] bytes, String contentType) {
    }

    public record ImageFile(Resource resource, String contentType, String originalName) {
    }
}
