package com.example.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * このテストで確認したいこと:
 *  1. 連続リクエストで容量を使い切ると 429 が返る
 *  2. 少し待つとトークンが補充されて、また 200 が返る
 *
 * つまり「レートリミットがちゃんとかかるし、時間経過で解除もされる」という
 * 実運用に近い挙動を証明する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // テスト専用のレート設定（速く枯れて速く回復するように小さく・早くしている）
        "ratelimit.capacity=2",            // バースト許容量は2リクエスト
        "ratelimit.refill-per-second=5.0", // 1秒で5トークン補充(=0.2秒で1トークン戻るイメージ)
        "ratelimit.idle-evict-seconds=0"   // 掃除は気にしない
})
class RateLimiterControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rateLimit_thenRecoverAfterRefill() throws Exception {
        final String url = "/v1/allow?key=test-user";

        // 1回目: OKのはず (残り1トークン)
        mockMvc.perform(post(url))
                .andExpect(status().isOk());

        // 2回目: OKのはず (残り0トークン)
        mockMvc.perform(post(url))
                .andExpect(status().isOk());

        // 3回目: もう枯れてるので 429 が返るはず
        mockMvc.perform(post(url))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // ---- 回復を待つ ------------------------------------
        // refill-per-second=5.0 → 0.5秒あれば2.5トークンくらい補充される計算なので
        // 500msスリープしてもう一度許されることを確認する
        Thread.sleep(500);

        // 4回目: 再び OK に戻るはず
        var result = mockMvc.perform(post(url))
                .andExpect(status().isOk())
                .andReturn();

        // 念のためレスポンスJSONに allowed:true が入ってるかも軽く確かめる
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"allowed\":true");
    }
}
