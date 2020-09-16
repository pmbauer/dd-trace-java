package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v0.RequestMeta.SCOPE_CONTEXT_KEY;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {

  private static final CharSequence SPAN_NAME = UTF8BytesString.createConstant("aws.http");

  private final AwsSdkClientDecorator decorate;

  public TracingRequestHandler(
      final ContextStore<AmazonWebServiceRequest, RequestMeta> contextStore) {
    decorate = new AwsSdkClientDecorator(contextStore);
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    final AgentSpan span = startSpan(SPAN_NAME);
    decorate.afterStart(span);
    decorate.onRequest(span, request);
    request.addHandlerContext(SCOPE_CONTEXT_KEY, activateSpan(span));
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      decorate.onResponse(scope.span(), response);
      decorate.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      decorate.onError(scope.span(), e);
      decorate.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
