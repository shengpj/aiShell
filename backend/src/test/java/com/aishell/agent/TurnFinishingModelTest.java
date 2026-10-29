package com.aishell.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnFinishingModelTest {

  @Test
  void clearsPartialOnlyOnTheLastChunk() {
    LlmResponse first = chunk("checking", true);
    LlmResponse second = chunk("disk", true);
    LlmResponse last = chunk("usage", true);

    List<LlmResponse> out = stream(first, second, last);

    assertThat(out).hasSize(3);
    assertThat(out.get(0).partial()).contains(true);
    assertThat(out.get(1).partial()).contains(true);
    assertThat(out.get(2).partial()).contains(false);
    assertThat(out.get(2).content().map(Content::text)).contains("usage");
  }

  @Test
  void singleChunkStreamIsFinal() {
    List<LlmResponse> out = stream(chunk("hello", true));

    assertThat(out).hasSize(1);
    assertThat(out.get(0).partial()).contains(false);
  }

  @Test
  void leavesAnAlreadyFinalToolCallChunkUntouched() {
    LlmResponse call = chunk("", false);

    List<LlmResponse> out = stream(call);

    assertThat(out).containsExactly(call);
  }

  @Test
  void nonStreamingResponsesPassThrough() {
    LlmResponse response = chunk("done", false);

    List<LlmResponse> out =
        new TurnFinishingModel(delegate(response))
            .generateContent(LlmRequest.builder().build(), false)
            .toList()
            .blockingGet();

    assertThat(out).containsExactly(response);
  }

  private static List<LlmResponse> stream(LlmResponse... responses) {
    return new TurnFinishingModel(delegate(responses))
        .generateContent(LlmRequest.builder().build(), true)
        .toList()
        .blockingGet();
  }

  private static LlmResponse chunk(String text, boolean partial) {
    return LlmResponse.builder()
        .content(Content.fromParts(Part.fromText(text)))
        .partial(partial)
        .turnComplete(true)
        .build();
  }

  private static BaseLlm delegate(LlmResponse... responses) {
    return new BaseLlm("fake") {
      @Override
      public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return Flowable.fromArray(responses);
      }

      @Override
      public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
