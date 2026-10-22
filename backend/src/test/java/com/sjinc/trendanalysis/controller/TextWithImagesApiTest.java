package com.sjinc.trendanalysis.controller;

import com.jayway.jsonpath.JsonPath;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.UploadedImageRepository;
import com.sjinc.trendanalysis.service.analysis.AiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원문 1건 = 본문 + 이미지 N장 — 텍스트 등록에 이미지를 첨부하면 한 원문으로 저장되고, 분석 요청 한 번에 본문과 이미지가 같이 간다.
 * 첨부는 이미지 업로드 목록에 나오지 않고, 원문을 지우면 같이 지워진다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:textimagetest",
        "image.upload.dir=target/test-uploads-text",
        "claude.api.key=test-key"
})
@AutoConfigureMockMvc
class TextWithImagesApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UploadedImageRepository uploadedImageRepository;
    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @MockBean
    private AiClient aiClient;

    /** 테스트마다 내용이 다른 PNG(중복 거르기에 걸리지 않게 끝에 순번을 붙인다). */
    private static byte[] png() {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        byte[] bytes = Arrays.copyOf(head, head.length + 4);
        int n = SEQ.incrementAndGet();
        bytes[head.length] = (byte) (n >> 8);
        bytes[head.length + 1] = (byte) n;
        return bytes;
    }

    private static MockMultipartFile image(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/png", bytes);
    }

    private static MockHttpServletRequestBuilder register(String body, MockMultipartFile... files) {
        String json = "{\"title\":\"가을 아우터\",\"body\":\"" + body + "\",\"media\":\"보그코리아\",\"url\":\"https://example.com/a/" + SEQ.incrementAndGet() + "\"}"; // 원문마다 다른 URL(같은 URL은 중복으로 막힌다)
        var builder = multipart("/api/texts")
                .file(new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        return builder.with(csrf());
    }

    @Test
    @WithMockUser(username = "admin")
    void 본문과_이미지를_한_원문으로_등록하고_한_번에_분석_요청한다() throws Exception {
        String body = mockMvc.perform(register("버건디 레더 재킷이 올가을 유행이다.", image("look1.png", png()), image("look2.png", png())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.imageCount").value(2))
                .andExpect(jsonPath("$.document.media").value("보그코리아"))
                .andExpect(jsonPath("$.skippedImages.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.document.id");
        String docId = JsonPath.read(body, "$.document.docId");

        // 분석 요청 한 번에 본문과 이미지 두 장(첨부 순서대로)
        // 분석은 별도 스레드라 이 원문의 호출이 올 때까지 기다린다(다른 테스트의 원문 호출과 섞이지 않게 문서 ID로 고른다)
        ArgumentCaptor<AiClient.StructureRequest> sent = ArgumentCaptor.forClass(AiClient.StructureRequest.class);
        verify(aiClient, timeout(5000).atLeastOnce()).structure(argThat(r -> r != null && docId.equals(r.docId())), any());
        verify(aiClient, org.mockito.Mockito.atLeastOnce()).structure(sent.capture(), any());
        AiClient.StructureRequest request = sent.getAllValues().stream().filter(r -> docId.equals(r.docId())).findFirst().orElseThrow();
        assertThat(request.docType()).isEqualTo("TEXT");
        assertThat(request.text()).contains("버건디 레더 재킷");
        assertThat(request.images()).hasSize(2).allMatch(i -> "image/png".equals(i.mediaType()) && !i.data().isEmpty());

        // 결과 보기에 첨부 이미지가 나오고, 텍스트 등록 권한으로 열린다
        String analysis = mockMvc.perform(get("/api/texts/" + id + "/analysis"))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].name").value("look1.png"))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(analysis, "$.images[0].url");
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));

        // 첨부는 이미지 업로드 목록에 나오지 않는다(따로 올린 이미지만)
        mockMvc.perform(get("/api/images")).andExpect(content().string(not(containsString("look1.png"))));

        // 원문을 지우면 첨부도 지운다
        mockMvc.perform(delete("/api/texts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[" + id + "]"))
                .andExpect(status().isOk());
        assertThat(uploadedImageRepository.findByDocumentIdIn(java.util.List.of(id.longValue()))).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin")
    void 다른_원문의_이미지_id로는_열지_못한다() throws Exception {
        String a = mockMvc.perform(register("원문 A", image("a.png", png()))).andReturn().getResponse().getContentAsString();
        String b = mockMvc.perform(register("원문 B", image("b.png", png()))).andReturn().getResponse().getContentAsString();
        Number idA = JsonPath.read(a, "$.document.id");
        Number idB = JsonPath.read(b, "$.document.id");
        Long imageOfB = uploadedImageRepository.findByDocumentIdOrderBySortOrderAscIdAsc(idB.longValue()).get(0).getId();

        mockMvc.perform(get("/api/texts/" + idA + "/images/" + imageOfB + "/file")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin")
    void 이미_올린_이미지는_건너뛰고_원문은_등록한다() throws Exception {
        byte[] same = png();
        mockMvc.perform(register("첫 기사", image("first.png", same))).andExpect(status().isOk());

        mockMvc.perform(register("두 번째 기사", image("again.png", same), image("new.png", png())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.imageCount").value(1))
                .andExpect(jsonPath("$.skippedImages[0].fileName").value("again.png"))
                .andExpect(jsonPath("$.skippedImages[0].existingName").value("first.png"));
    }

    @Test
    @WithMockUser(username = "admin")
    void 이미지가_여섯_장이거나_이미지가_아니면_원문도_만들지_않는다() throws Exception {
        long before = sourceDocumentRepository.count();
        MockMultipartFile[] six = new MockMultipartFile[6];
        for (int i = 0; i < six.length; i++) {
            six[i] = image("p" + i + ".png", png());
        }
        mockMvc.perform(register("여섯 장", six))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("5장")));
        mockMvc.perform(register("텍스트 파일", new MockMultipartFile("files", "memo.txt", "image/png", "not image".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("memo.txt")));
        assertThat(sourceDocumentRepository.count()).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin")
    void 이미지_없이_multipart로_보내도_본문만_등록된다() throws Exception {
        mockMvc.perform(register("본문만 있는 기사"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.imageCount").value(0));
        mockMvc.perform(get("/api/texts")).andExpect(jsonPath("$[*].imageCount", hasItem(0)));
        verify(aiClient, timeout(3000).atLeastOnce()).structure(argThat(r -> r != null && r.images().isEmpty()), any());
    }
}
