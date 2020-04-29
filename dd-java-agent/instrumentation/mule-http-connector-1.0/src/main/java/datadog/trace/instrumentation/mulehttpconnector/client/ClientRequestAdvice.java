package datadog.trace.instrumentation.mulehttpconnector.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;
import static datadog.trace.instrumentation.mulehttpconnector.client.InjectAdapter.SETTER;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ParentChildSpan;
import net.bytebuddy.asm.Advice;

public class ClientRequestAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.Argument(0) final Request request,
      @Advice.Argument(1) final AsyncHandler<?> handler) {
    AgentSpan parentSpan = activeSpan();
    AgentSpan span = startSpan("http.request");
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    propagate().inject(span, request, SETTER);
    InstrumentationContext.get(AsyncCompletionHandler.class, ParentChildSpan.class)
        .put((AsyncCompletionHandler<?>) handler, new ParentChildSpan(parentSpan, span));
  }
}
