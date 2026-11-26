/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The evaluation event reaches the span the way the collector will see it. */
class FeatureFlagEvaluationHookTest {

  private final List<SpanData> exported = new CopyOnWriteArrayList<>();
  private SdkTracerProvider tracers;
  private Client client;

  @BeforeEach
  void setUp() {
    SpanExporter capture =
        new SpanExporter() {
          @Override
          public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };
    tracers =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(capture)).build();
    OpenFeatureAPI.getInstance().addHooks(new FeatureFlagEvaluationHook());
    client = OpenFeatureAPI.getInstance().getClient();
  }

  private static Flag<Boolean> off() {
    return Flag.<Boolean>builder()
        .variant("on", true)
        .variant("off", false)
        .defaultVariant("off")
        .build();
  }

  private void useDemoFlags() throws Exception {
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(
            new InMemoryProvider(
                Map.of("adFailure", off(), "adHighCpu", off(), "adManualGc", off())));
  }

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().shutdown();
    OpenFeatureAPI.getInstance().clearHooks();
    tracers.close();
  }

  private List<EventData> evaluationsOf(Runnable evaluate) {
    Span span = tracers.get("test").spanBuilder("getAds").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      evaluate.run();
    } finally {
      span.end();
    }
    return exported.get(0).getEvents().stream()
        .filter(e -> e.getName().equals("feature_flag.evaluation"))
        .toList();
  }

  @Test
  void evaluationBecomesOneFeatureFlagEvaluationEventWithKeyAndVariant() throws Exception {
    useDemoFlags();
    List<EventData> events = evaluationsOf(() -> client.getBooleanValue("adFailure", true));
    assertEquals(1, events.size());
    var attributes = events.get(0).getAttributes();
    assertEquals("adFailure", attributes.get(FeatureFlagEvaluationHook.KEY));
    assertEquals("off", attributes.get(FeatureFlagEvaluationHook.VARIANT));
    assertEquals("false", attributes.get(FeatureFlagEvaluationHook.VALUE));
    assertTrue(!attributes.get(FeatureFlagEvaluationHook.PROVIDER).isEmpty());
  }

  @Test
  void anEvaluationThatFailsIsRecordedAsAnErrorWithoutAVariant() {
    List<EventData> events = evaluationsOf(() -> client.getBooleanValue("adFailure", false));
    assertEquals(1, events.size());
    var attributes = events.get(0).getAttributes();
    assertEquals("error", attributes.get(FeatureFlagEvaluationHook.REASON));
    assertEquals(null, attributes.get(FeatureFlagEvaluationHook.VARIANT));
  }

  @Test
  void eachOfTheThreeFlagsIsRecordedUnderItsOwnKey() throws Exception {
    useDemoFlags();
    List<EventData> events =
        evaluationsOf(() -> {
          client.getBooleanValue("adFailure", false);
          client.getBooleanValue("adHighCpu", false);
          client.getBooleanValue("adManualGc", false);
        });
    assertEquals(
        List.of("adFailure", "adHighCpu", "adManualGc"),
        events.stream().map(e -> e.getAttributes().get(FeatureFlagEvaluationHook.KEY)).toList());
  }

  @Test
  void evaluationWithoutACurrentSpanRecordsNothingAndStillReturnsTheValue() {
    assertEquals(false, client.getBooleanValue("adFailure", false));
    assertTrue(exported.isEmpty());
  }
}
