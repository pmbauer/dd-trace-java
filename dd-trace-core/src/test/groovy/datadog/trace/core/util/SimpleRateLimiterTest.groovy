package datadog.trace.core.util

import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.util.test.DDSpecification

import java.util.concurrent.TimeUnit

class SimpleRateLimiterTest extends DDSpecification {
  def "initial rate available at creation"() {
    setup:
    def timeSource = new ControllableTimeSource()
    def limiter = new SimpleRateLimiter(rate, timeSource)

    when:
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 1000]
  }

  def "tokens never go beyond rate"() {
    setup:
    def timeSource = new ControllableTimeSource()
    def limiter = new SimpleRateLimiter(rate, timeSource)

    when:
    timeSource.advance(TimeUnit.SECONDS.toNanos(5))
    rate.times {
      assert limiter.tryAcquire(): "failed for $it"
    }

    then:
    assert !limiter.tryAcquire()

    where:
    rate << [10, 100, 1000]
  }
}
