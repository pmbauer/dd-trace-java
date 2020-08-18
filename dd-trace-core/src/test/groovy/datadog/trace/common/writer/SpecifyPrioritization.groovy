package datadog.trace.common.writer


import datadog.trace.common.writer.ddagent.Prioritization
import datadog.trace.common.writer.ddagent.PrioritizationStrategy
import datadog.trace.util.test.DDSpecification
import org.jctools.queues.MessagePassingQueue

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP

class SpecifyPrioritization extends DDSpecification {


  def "fast lane strategy sends kept and unset priority traces to the primary queue, dropped traces to the secondary queue" () {
    setup:
    MessagePassingQueue<Object> primary = Mock(MessagePassingQueue)
    MessagePassingQueue<Object> secondary = Mock(MessagePassingQueue)
    PrioritizationStrategy fastLane =  Prioritization.FAST_LANE.create(primary, secondary)

    when:
    fastLane.published(priority, trace)

    then:
    primaryOffers * primary.offer(trace)
    secondaryOffers * secondary.offer(trace)

    where:
    trace    |     priority      |   primaryOffers   | secondaryOffers
    []       |     UNSET         |       1           |      0
    []       |     SAMPLER_DROP  |       0           |      1
    []       |     SAMPLER_KEEP  |       1           |      0
    []       |     SAMPLER_DROP  |       0           |      1
    []       |     USER_KEEP     |       1           |      0
  }


  def "dead letters strategy drops unkept traces if the primary queue is full" () {
    setup:
    MessagePassingQueue<Object> primary = Mock(MessagePassingQueue)
    MessagePassingQueue<Object> secondary = Mock(MessagePassingQueue)
    PrioritizationStrategy fastLane =  Prioritization.DEAD_LETTERS.create(primary, secondary)

    when:
    fastLane.published(priority, trace)

    then:
    primaryOffers * primary.offer(trace) >> !primaryFull
    secondaryOffers * secondary.offer(trace)

    where:
    trace    |     primaryFull   |   priority      |   primaryOffers   | secondaryOffers
    []       |      true         |   UNSET         |       1           |      1
    []       |      true         |   SAMPLER_DROP  |       1           |      0
    []       |      true         |   SAMPLER_KEEP  |       1           |      1
    []       |      true         |   SAMPLER_DROP  |       1           |      0
    []       |      true         |   USER_KEEP     |       1           |      1
    []       |      false        |   UNSET         |       1           |      0
    []       |      false        |   SAMPLER_DROP  |       1           |      0
    []       |      false        |   SAMPLER_KEEP  |       1           |      0
    []       |      false        |   SAMPLER_DROP  |       1           |      0
    []       |      false        |   USER_KEEP     |       1           |      0
  }
}
