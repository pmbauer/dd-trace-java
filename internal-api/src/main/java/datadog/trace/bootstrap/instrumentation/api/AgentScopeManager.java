package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;

/**
 * Allows custom scope managers. See OTScopeManager, CustomScopeManager, and ContextualScopeManager
 */
public interface AgentScopeManager {

  // Inherits the async propagation of the current scope
  AgentScope activate(AgentSpan span, ScopeSource source);

  AgentScope activate(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

  TraceScope active();

  AgentSpan activeSpan();
}
