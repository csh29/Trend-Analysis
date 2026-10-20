package com.sjinc.trendanalysis.service.image;

import java.util.Arrays;
import java.util.Optional;

/**
 * 받는 이미지 형식과 그 파일 시그니처(앞부분 바이트). 확장자나 브라우저가 보낸 Content-Type은 바꿔 보낼 수 있으므로
 * 파일 내용으로 형식을 정한다. SVG는 스크립트를 담을 수 있어(같은 출처에서 열면 XSS) 받지 않는다.
 */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    BMP("image/bmp", "bmp");

    /** 형식을 판별하는 데 필요한 앞부분 바이트 수(WEBP가 가장 길다). */
    public static final int HEADER_BYTES = 12;

    private final String mimeType;
    private final String extension;

    ImageType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String getMimeType() { return mimeType; }
    public String getExtension() { return extension; }

    /** 파일 앞부분으로 형식을 판별한다. 받는 형식이 아니면 empty. */
    public static Optional<ImageType> detect(byte[] header) {
        return Arrays.stream(values()).filter(type -> type.matches(header)).findFirst();
    }

    private boolean matches(byte[] h) {
        return switch (this) {
            case JPEG -> startsWith(h, 0xFF, 0xD8, 0xFF);
            case PNG -> startsWith(h, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case GIF -> startsWith(h, 'G', 'I', 'F', '8');
            case BMP -> startsWith(h, 'B', 'M');
            // RIFF....WEBP — 4~7번째 바이트는 파일 길이라 건너뛴다
            case WEBP -> startsWith(h, 'R', 'I', 'F', 'F') && h.length >= 12
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        };
    }

    private static boolean startsWith(byte[] h, int... signature) {
        if (h.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((h[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
