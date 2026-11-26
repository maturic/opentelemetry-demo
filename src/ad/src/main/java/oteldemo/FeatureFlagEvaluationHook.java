/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.Map;

/**
 * Records every flag evaluation on the current span as the {@code feature_flag.evaluation} event of
 * the OpenTelemetry feature-flag semantic conventions. The contrib OpenFeature OpenTelemetry hook
 * (dev.openfeature.contrib.hooks:otel 3.3.1) still emits the older {@code feature_flag} event with
 * {@code feature_flag.variant}, which the demo's other services no longer send.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class FeatureFlagEvaluationHook implements Hook {

  static final String EVENT_NAME = "feature_flag.evaluation";

  static final AttributeKey<String> KEY = AttributeKey.stringKey("feature_flag.key");
  static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("feature_flag.provider.name");
  static final AttributeKey<String> VARIANT = AttributeKey.stringKey("feature_flag.result.variant");
  static final AttributeKey<String> VALUE = AttributeKey.stringKey("feature_flag.result.value");
  static final AttributeKey<String> REASON = AttributeKey.stringKey("feature_flag.result.reason");
  static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

  @Override
  public void after(HookContext ctx, FlagEvaluationDetails details, Map hints) {
    String value = String.valueOf(details.getValue());
    Span.current()
        .addEvent(
            EVENT_NAME,
            Attributes.builder()
                .put(KEY, ctx.getFlagKey())
                .put(PROVIDER, ctx.getProviderMetadata().getName())
                .put(VARIANT, details.getVariant() != null ? details.getVariant() : value)
                .put(VALUE, value)
                .put(REASON, String.valueOf(details.getReason()).toLowerCase())
                .build());
  }

  @Override
  public void error(HookContext ctx, Exception error, Map hints) {
    Span.current()
        .addEvent(
            EVENT_NAME,
            Attributes.builder()
                .put(KEY, ctx.getFlagKey())
                .put(PROVIDER, ctx.getProviderMetadata().getName())
                .put(REASON, "error")
                .put(ERROR_TYPE, "general")
                .build());
  }
}
