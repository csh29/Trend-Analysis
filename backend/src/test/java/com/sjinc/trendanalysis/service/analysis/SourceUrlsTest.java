package com.sjinc.trendanalysis.service.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 원문은 같은 URL로 — 공유 링크의 추적 파라미터·www·끝 슬래시·http/https 차이를 없앤다. */
class SourceUrlsTest {

    @Test
    void 같은_기사의_여러_링크는_하나로_맞춘다() {
        String expected = "https://vogue.co.kr/2026/10/22/leather";
        assertThat(SourceUrls.normalize("https://www.vogue.co.kr/2026/10/22/leather/")).isEqualTo(expected);
        assertThat(SourceUrls.normalize(" http://VOGUE.co.kr:80/2026/10/22/leather?utm_source=ig&utm_medium=social#top ")).isEqualTo(expected);
        assertThat(SourceUrls.normalize("https://vogue.co.kr/2026/10/22/leather?fbclid=abc&igshid=x")).isEqualTo(expected);
    }

    @Test
    void 원문을_가르는_파라미터는_남기고_순서를_맞춘다() {
        assertThat(SourceUrls.normalize("https://news.naver.com/article?oid=001&aid=123&utm_source=x"))
                .isEqualTo("https://news.naver.com/article?aid=123&oid=001");
        assertThat(SourceUrls.normalize("https://news.naver.com/article?aid=123&oid=001"))
                .isEqualTo(SourceUrls.normalize("https://news.naver.com/article?oid=001&aid=123"));
        // 경로 대소문자는 원문을 가를 수 있어 그대로 둔다
        assertThat(SourceUrls.normalize("https://instagram.com/p/AbC")).isEqualTo("https://instagram.com/p/AbC");
    }

    @Test
    void URL이_아니면_공백만_뗀다() {
        assertThat(SourceUrls.normalize("  ")).isNull();
        assertThat(SourceUrls.normalize(null)).isNull();
        assertThat(SourceUrls.normalize(" 보그 10월호 p.42 ")).isEqualTo("보그 10월호 p.42");
    }
}
