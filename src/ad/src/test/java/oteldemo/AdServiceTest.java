/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.AdResponse;
import oteldemo.problempattern.CPULoad;

/**
 * Behaviour of getAds with the adFailure, adHighCpu and adManualGc flags in their default (off)
 * state, which is the state that remains once those flags are removed from the code.
 */
class AdServiceTest {

  private static final String GC_LOGGER = "oteldemo.problempattern.GarbageCollectionTrigger";

  private static final class Capture implements StreamObserver<AdResponse> {
    AdResponse response;
    Throwable error;
    boolean completed;

    @Override
    public void onNext(AdResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable t) {
      error = t;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class Events extends AbstractAppender {
    final List<String> messages = new CopyOnWriteArrayList<>();

    Events() {
      super("ad-test-events", null, null, true, null);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getMessage().getFormattedMessage());
    }
  }

  private AdServiceGrpc.AdServiceImplBase impl;
  private Events events;
  private LoggerConfig gcLoggerConfig;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> type = Class.forName("oteldemo.AdService$AdServiceImpl");
    Constructor<?> ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    impl = (AdServiceGrpc.AdServiceImplBase) ctor.newInstance();

    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    events = new Events();
    events.start();
    config.addAppender(events);
    gcLoggerConfig = new LoggerConfig(GC_LOGGER, Level.ALL, false);
    gcLoggerConfig.addAppender(events, Level.ALL, null);
    config.addLogger(GC_LOGGER, gcLoggerConfig);
    ctx.updateLoggers();
  }

  @AfterEach
  void tearDown() {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    ctx.getConfiguration().removeLogger(GC_LOGGER);
    ctx.updateLoggers();
    events.stop();
  }

  private Capture call(AdRequest request) {
    Capture out = new Capture();
    impl.getAds(request, out);
    return out;
  }

  @Test
  void targetedRequestServesTheCategoryAd() {
    Capture out = call(AdRequest.newBuilder().addContextKeys("binoculars").build());

    assertTrue(out.completed);
    assertNull(out.error);
    assertEquals(1, out.response.getAdsCount());
    assertEquals("/product/2ZYFJ3GM2N", out.response.getAds(0).getRedirectUrl());
  }

  @Test
  void multipleContextKeysConcatenateTheirAds() {
    Capture out =
        call(AdRequest.newBuilder().addContextKeys("binoculars").addContextKeys("travel").build());

    assertTrue(out.completed);
    List<String> urls = new ArrayList<>();
    out.response.getAdsList().forEach(ad -> urls.add(ad.getRedirectUrl()));
    assertEquals(List.of("/product/2ZYFJ3GM2N", "/product/1YMWWN1N4O"), urls);
  }

  @Test
  void unmatchedContextKeyFallsBackToTwoRandomAds() {
    Capture out = call(AdRequest.newBuilder().addContextKeys("books").build());

    assertTrue(out.completed);
    assertNull(out.error);
    assertEquals(2, out.response.getAdsCount());
  }

  @Test
  void requestWithoutContextServesTwoRandomAds() {
    Capture out = call(AdRequest.getDefaultInstance());

    assertTrue(out.completed);
    assertEquals(2, out.response.getAdsCount());
  }

  @Test
  void requestsNeverFailWithUnavailable() {
    int failures = 0;
    for (int i = 0; i < 500; i++) {
      Capture out = call(AdRequest.newBuilder().addContextKeys("accessories").build());
      if (out.error != null) {
        failures++;
      } else {
        assertEquals(3, out.response.getAdsCount());
      }
    }
    assertEquals(0, failures);
  }

  @Test
  void noCpuLoadWorkersAreRunningAfterRequests() throws Exception {
    for (int i = 0; i < 5; i++) {
      call(AdRequest.getDefaultInstance());
    }

    CPULoad load = CPULoad.getInstance();
    Field running = CPULoad.class.getDeclaredField("running");
    running.setAccessible(true);
    Field workers = CPULoad.class.getDeclaredField("runningWorkers");
    workers.setAccessible(true);
    assertFalse((boolean) running.get(load));
    assertTrue(((List<?>) workers.get(load)).isEmpty());
  }

  @Test
  void noManualGarbageCollectionIsTriggered() {
    for (int i = 0; i < 5; i++) {
      call(AdRequest.getDefaultInstance());
    }

    assertTrue(events.messages.isEmpty(), "unexpected GC trigger logs: " + events.messages);
  }
}
