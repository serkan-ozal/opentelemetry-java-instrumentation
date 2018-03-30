package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.http.Request;
import ratpack.http.Status;

/**
 * This Ratpack handler reads tracing headers from the incoming request, starts a scope and ensures
 * that the scope is closed when the request is sent
 */
public final class TracingHandler implements Handler {
  @Override
  public void handle(Context ctx) {
    Request request = ctx.getRequest();

    final SpanContext extractedContext =
        GlobalTracer.get()
            .extract(Format.Builtin.HTTP_HEADERS, new RatpackRequestExtractAdapter(request));

    final Scope scope =
        GlobalTracer.get()
            .buildSpan("ratpack")
            .asChildOf(extractedContext)
            .withTag(Tags.COMPONENT.getKey(), "handler")
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .withTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET)
            .withTag(Tags.HTTP_METHOD.getKey(), request.getMethod().getName())
            .withTag(Tags.HTTP_URL.getKey(), request.getUri())
            .startActive(true);

    ctx.getResponse()
        .beforeSend(
            response -> {
              Span span = scope.span();
              Status status = response.getStatus();
              if (status != null) {
                // Should a 4xx be marked as an error?
                if (status.is4xx() || status.is5xx()) {
                  Tags.ERROR.set(span, true);
                }
                Tags.HTTP_STATUS.set(span, status.getCode());
              }
              scope.close();
            });

    ctx.next();
  }
}
