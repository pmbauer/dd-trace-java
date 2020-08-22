package datadog.trace.core.scopemanager;

import com.google.common.util.concurrent.RateLimiter;
import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.StatsDClient;
import datadog.trace.api.Config;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator;
import datadog.trace.mlt.MethodLevelTracer;
import datadog.trace.mlt.Session;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

public abstract class TraceProfilingScopeInterceptor {
  private static final long MAX_NANOSECONDS_BETWEEN_ACTIVATIONS = TimeUnit.SECONDS.toNanos(1);
  private static final double ACTIVATIONS_PER_SECOND = 5;
  private static final ThreadLocal<Boolean> IS_THREAD_PROFILING =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  private final StatsDClient statsDClient;

  protected final RateLimiter rateLimiter = RateLimiter.create(ACTIVATIONS_PER_SECOND);

  private TraceProfilingScopeInterceptor(final StatsDClient statsDClient) {
    this.statsDClient = statsDClient;
  }

  public static TraceProfilingScopeInterceptor create(
      final Double methodTraceSampleRate,
      final TraceHeuristicsEvaluator traceHeuristicsEvaluator,
      final StatsDClient statsDClient) {
    if (!Config.get().isMethodTraceEnabled()) {
      return new NoOp();
    }
    if (methodTraceSampleRate != null) {
      return new Percentage(methodTraceSampleRate, statsDClient);
    }
    return new Heuristical(traceHeuristicsEvaluator, statsDClient);
  }

  public AgentScope handleSpan(final AgentSpan span) {
    if (!(span instanceof AgentTracer.NoopAgentSpan)
        && (IS_THREAD_PROFILING.get() // if already profiling, we want to add more context.
            || shouldProfile(span))) {
      return new TraceProfilingScope(span);
    }
    // We don't want to wrap the scope for profiling.
    return AgentTracer.NoopAgentScope.INSTANCE;
  }

  abstract boolean shouldProfile(AgentSpan span);

  private static class NoOp extends TraceProfilingScopeInterceptor {

    private NoOp() {
      super(new NoOpStatsDClient());
    }

    @Override
    public AgentScope handleSpan(AgentSpan span) {
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    @Override
    boolean shouldProfile(AgentSpan span) {
      return false;
    }
  }

  private static class Percentage extends TraceProfilingScopeInterceptor {
    private static final BigDecimal TRACE_ID_MAX_AS_BIG_DECIMAL =
        new BigDecimal(CoreTracer.TRACE_ID_MAX);

    private final long cutoff;

    private Percentage(final double percent, final StatsDClient statsDClient) {
      super(statsDClient);
      assert 0 <= percent && percent <= 1;
      cutoff = new BigDecimal(percent).multiply(TRACE_ID_MAX_AS_BIG_DECIMAL).longValue();
    }

    @Override
    boolean shouldProfile(final AgentSpan span) {
      // Do we want to apply rate limiting?
      return compareUnsigned(span.getTraceId().toLong(), cutoff) <= 0;
    }

    // When we drop support for 1.7, we can use Long.compareUnsigned directly.
    public static int compareUnsigned(final long x, final long y) {
      return Long.compare(x + Long.MIN_VALUE, y + Long.MIN_VALUE);
    }
  }

  private static class Heuristical extends TraceProfilingScopeInterceptor {
    private volatile long lastProfileTimestamp = System.nanoTime();

    private final TraceHeuristicsEvaluator traceEvaluator;

    private Heuristical(
        final TraceHeuristicsEvaluator traceEvaluator, final StatsDClient statsDClient) {
      super(statsDClient);
      this.traceEvaluator = traceEvaluator;
    }

    @Override
    boolean shouldProfile(final AgentSpan span) {
      if (maybeInteresting(span) && acquireProfilePermit()) {
        lastProfileTimestamp = System.nanoTime();
        return true;
      }
      return false;
    }

    private boolean maybeInteresting(final AgentSpan span) {
      if (traceEvaluator.isDistinctive(span.getLocalRootSpan())) {
        // This is a distinctive trace, so lets profile it.
        return true;
      }

      Integer samplingPriority = span.getSamplingPriority();
      if (samplingPriority != null && samplingPriority == PrioritySampling.USER_KEEP) {
        // The trace was manually identified as important, so more likely interesting.
        return true;
      }

      if (lastProfileTimestamp + MAX_NANOSECONDS_BETWEEN_ACTIVATIONS < System.nanoTime()) {
        // It's been a while since we last profiled, so lets take one now.
        // Due to the multi-threaded nature here, we will likely have multiple threads enter here
        // but they will still be subject to the rate limiter following.
        return true;
      }
      return false;
    }

    private boolean acquireProfilePermit() {
      return rateLimiter.tryAcquire();
    }
  }

  private class TraceProfilingScope implements AgentScope {
    private final Session session;
    private final boolean rootScope;
    private final AgentSpan span;

    private TraceProfilingScope(final AgentSpan span) {
      rootScope = !IS_THREAD_PROFILING.get();
      if (rootScope) {
        statsDClient.incrementCounter("mlt.scope", "scope:root");
        IS_THREAD_PROFILING.set(true);
      } else {
        statsDClient.incrementCounter("mlt.scope", "scope:child");
      }
      session = MethodLevelTracer.startProfiling(span.getTraceId().toString());
      this.span = span;
    }

    @Override
    public AgentSpan span() {
      return span;
    }

    @Override
    public void setAsyncPropagation(boolean value) {}

    @Override
    public TraceScope.Continuation capture() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      if (rootScope) {
        IS_THREAD_PROFILING.set(false);
      }
      final byte[] samplingData = session.close();

      if (samplingData != null) {
        statsDClient.incrementCounter("mlt.count");
        statsDClient.count("mlt.bytes", samplingData.length);
        span.setTag(InstrumentationTags.DD_MLT, samplingData);
        if (span.getSamplingPriority() == null) {
          // if priority not set, let's increase priority to improve chance this is kept.
          span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
        }
      }
    }

    @Override
    public boolean isAsyncPropagating() {
      return false;
    }
  }
}