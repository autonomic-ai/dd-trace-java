package datadog.trace.instrumentation.couchbase.client;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.message.CouchbaseRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CouchbaseCoreInstrumentation extends Instrumenter.Default {

  public CouchbaseCoreInstrumentation() {
    super("couchbase");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.couchbase.client.core.CouchbaseCore");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.couchbase.client.core.message.CouchbaseRequest", Span.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, named("com.couchbase.client.core.message.CouchbaseRequest")))
            .and(named("send")),
        CouchbaseCoreAdvice.class.getName());
  }

  public static class CouchbaseCoreAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addOperationIdToSpan(@Advice.Argument(0) final CouchbaseRequest request) {

      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope != null) {
        // The scope from the initial rxJava subscribe is not available to the networking layer
        // To transfer the span, the span is added to the context store

        final ContextStore<CouchbaseRequest, Span> contextStore =
            InstrumentationContext.get(CouchbaseRequest.class, Span.class);

        Span span = contextStore.get(request);

        if (span == null) {
          span = GlobalTracer.get().activeSpan();
          contextStore.put(request, span);

          if (request.operationId() != null) {
            span.setTag("couchbase.operation_id", request.operationId());
          }
        }
      }
    }
  }
}
