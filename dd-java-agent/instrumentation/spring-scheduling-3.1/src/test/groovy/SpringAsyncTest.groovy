import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SpringAsyncTest extends AgentTestRunner {

  def "context propagated through @async annotation" () {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)
    when:
    runUnderTrace("root") {
      asyncTask.async().join()
    }
    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          resourceName "root"
          threadNameStartsWith "main"
        }
        span(1) {
          resourceName "AsyncTask.async"
          threadNameStartsWith "SimpleAsyncTaskExecutor"
          childOf span(0)
        }
        span(2) {
          resourceName "AsyncTask.getInt"
          threadNameStartsWith "SimpleAsyncTaskExecutor"
          childOf span(1)
        }
      }
    }
  }

  def "context propagated to @async task" () {
    setup:
    def context = new AnnotationConfigApplicationContext(AsyncTaskConfig)
    AsyncTask asyncTask = context.getBean(AsyncTask)
    when:
    runUnderTrace("root") {
      asyncTask.async().join()
    }
    then:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          resourceName "root"
          threadNameStartsWith "main"
        }
        span(1) {
          resourceName "AsyncTask.async"
          threadNameStartsWith "SimpleAsyncTaskExecutor"
          childOf span(0)
        }
      }
    }
  }

  static class Leaf {

    private int i

    Leaf(int i) {
      this.i = i
    }

    @Trace
    int leaf() {
      return i
    }
  }
}
