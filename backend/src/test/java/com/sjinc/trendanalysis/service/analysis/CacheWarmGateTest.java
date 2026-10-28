package com.sjinc.trendanalysis.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 캐시가 식었으면 한 건만 먼저 보내고, 나머지는 그 건이 끝난 뒤 캐시를 읽게 하는지 본다(2026-10-28). */
class CacheWarmGateTest {

    private final AtomicLong now = new AtomicLong();
    private final CacheWarmGate gate = new CacheWarmGate(now::get);

    @Test
    void 첫_건이_데우고_끝나면_다음_건은_바로_보낸다() throws InterruptedException {
        assertThat(gate.enter("m:TEXT")).isTrue();  // 첫 건이 데운다
        gate.leave("m:TEXT", true, true);
        assertThat(gate.enter("m:TEXT")).isFalse(); // 따뜻하다 — 데우지 않고 바로
    }

    @Test
    void 데우는_중이면_끝날_때까지_기다린다() throws InterruptedException {
        CacheWarmGate real = new CacheWarmGate();
        assertThat(real.enter("m:TEXT")).isTrue();
        AtomicBoolean waited = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            try {
                waited.set(!real.enter("m:TEXT"));
            } catch (InterruptedException ignored) {
            }
            done.countDown();
        });
        second.start();
        assertThat(done.await(300, TimeUnit.MILLISECONDS)).isFalse(); // 첫 건이 끝나기 전에는 보내지 않는다
        real.leave("m:TEXT", true, true);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(waited).isTrue(); // 첫 건이 끝난 뒤 캐시를 읽는 쪽으로 보낸다
    }

    @Test
    void 데우던_건이_실패하면_다음_건이_데운다() throws InterruptedException {
        assertThat(gate.enter("m:TEXT")).isTrue();
        gate.leave("m:TEXT", true, false);
        assertThat(gate.enter("m:TEXT")).isTrue();
    }

    @Test
    void 사분이_지나면_다시_데운다() throws InterruptedException {
        assertThat(gate.enter("m:IMAGE")).isTrue();
        gate.leave("m:IMAGE", true, true);
        now.addAndGet(CacheWarmGate.WARM_FOR.toNanos());
        assertThat(gate.enter("m:IMAGE")).isTrue();
    }

    @Test
    void 프롬프트_종류마다_따로_데운다() throws InterruptedException {
        AiClient.ImageInput image = new AiClient.ImageInput("eA==", "image/png");
        String text = CacheWarmGate.keyOf(new AiClient.StructureRequest("T", "TEXT", "m", "제목", "본문", List.of(), null));
        String article = CacheWarmGate.keyOf(new AiClient.StructureRequest("A", "TEXT", "m", "제목", "본문", List.of(image), null));
        String photo = CacheWarmGate.keyOf(new AiClient.StructureRequest("I", "IMAGE", "m", "사진", null, List.of(image), null));
        assertThat(List.of(text, article, photo)).containsExactly("m:TEXT", "m:ARTICLE", "m:IMAGE");
        assertThat(gate.enter(text)).isTrue();
        assertThat(gate.enter(photo)).isTrue(); // 다른 종류는 기다리지 않는다
    }
}
