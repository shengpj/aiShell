package com.aishell.chat;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

/**
 * Makes the last response of a stream end the ADK turn.
 *
 * <p>The Spring AI adapter flags a streamed response as partial whenever its text does not end with
 * ".", "!", "?" or a newline, even when the provider reported {@code finish_reason=stop}. ADK only
 * ends a flow on a non-partial response, so a final chunk with such text made the agent call the
 * model again, up to the 500 call limit. Only the last chunk is rewritten, so tool-call chunks and
 * mid-stream flags stay as the adapter produced them.
 */
final class TurnFinishingModel extends BaseLlm {

  private final BaseLlm delegate;

  TurnFinishingModel(BaseLlm delegate) {
    super(delegate.model());
    this.delegate = delegate;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean streaming) {
    Flowable<LlmResponse> source = delegate.generateContent(request, streaming);
    if (!streaming) {
      return source;
    }
    // buffer(2, 1) releases each response one chunk late, so the stream's last chunk is known.
    return source.buffer(2, 1).concatMap(TurnFinishingModel::release);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    return delegate.connect(request);
  }

  private static Flowable<LlmResponse> release(List<LlmResponse> window) {
    if (window.size() == 2) {
      return Flowable.just(window.get(0));
    }
    LlmResponse last = window.get(0);
    return Flowable.just(
        last.partial().orElse(false) ? last.toBuilder().partial(false).build() : last);
  }
}
