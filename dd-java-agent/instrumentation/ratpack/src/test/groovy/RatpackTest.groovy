import datadog.opentracing.DDTracer
import datadog.opentracing.scopemanager.ContextualScopeManager
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.common.writer.ListWriter
import datadog.trace.instrumentation.ratpack.RatpackScopeManager
import io.opentracing.Scope
import io.opentracing.util.GlobalTracer
import okhttp3.OkHttpClient
import okhttp3.Request
import ratpack.exec.Promise
import ratpack.exec.util.ParallelBatch
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.http.HttpUrlBuilder
import ratpack.http.client.HttpClient
import ratpack.test.exec.ExecHarness

import java.lang.reflect.Field
import java.lang.reflect.Modifier

class RatpackTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.ratpack.enabled", "true")
  }
  OkHttpClient client = new OkHttpClient.Builder()
  // Uncomment when debugging:
//    .connectTimeout(1, TimeUnit.HOURS)
//    .writeTimeout(1, TimeUnit.HOURS)
//    .readTimeout(1, TimeUnit.HOURS)
    .build()


  ListWriter writer = new ListWriter()

  def setup() {
    assert GlobalTracer.isRegistered()
    setWriterOnGlobalTracer()
    writer.start()
    assert GlobalTracer.isRegistered()
  }

  def setWriterOnGlobalTracer() {
    // this is not safe, reflection is used to modify a private final field
    DDTracer existing = (DDTracer) GlobalTracer.get().tracer
    final Field field = DDTracer.getDeclaredField("writer")
    field.setAccessible(true)
    Field modifiersField = Field.getDeclaredField("modifiers")
    modifiersField.setAccessible(true)
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL)
    field.set(existing, writer)
  }

  def "test path call"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          context.render("success")
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    then:
    resp.code() == 200
    resp.body.string() == "success"

    writer.size() == 2 // second (parent) trace is the okhttp call above...
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "ratpack"
    span.context().tags["component"] == "handler"
    span.context().spanType == DDSpanTypes.WEB_SERVLET
    !span.context().getErrorFlag()
    span.context().tags["http.url"] == "/"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["http.status_code"] == 200
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
  }

  def "test error response"() {
    setup:
    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          context.clientError(404)
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    then:
    resp.code() == 404

    writer.size() == 2 // second (parent) trace is the okhttp call above...
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().getErrorFlag()
    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "ratpack"
    span.context().tags["component"] == "handler"
    span.context().spanType == DDSpanTypes.WEB_SERVLET
    span.context().tags["http.url"] == "/"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["http.status_code"] == 404
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
  }

  def "test path call using ratpack http client"() {
    /*
    This test is somewhat convoluted and it raises some questions about how this is supposed to work
     */
    setup:

    def external = GroovyEmbeddedApp.ratpack {
      handlers {
        get("nested") {
          context.render("succ")
        }
        get("nested2") {
          context.render("ess")
        }
      }
    }

    def app = GroovyEmbeddedApp.ratpack {
      handlers {
        get { HttpClient httpClient ->
          // 1st internal http client call to nested
          httpClient.get(HttpUrlBuilder.base(external.address).path("nested").build())
            .map { it.body.text }
            .flatMap { t ->
            // make a 2nd http request and concatenate the two bodies together
            httpClient.get(HttpUrlBuilder.base(external.address).path("nested2").build()) map { t + it.body.text }
          }
          .then {
            context.render(it)
          }
        }
      }
    }
    def request = new Request.Builder()
      .url(app.address.toURL())
      .get()
      .build()
    when:
    def resp = client.newCall(request).execute()
    then:
    resp.code() == 200
    resp.body().string() == "success"

    // fourth (parent) trace is the okhttp call above...,
    // 3rd is the three traces, ratpack, http client 2 and http client 1  - I would have expected client 1 and then 2
    // 2nd is nested2 from the external server (the result of the 2nd internal http client call)
    // 1st is nested from the external server (the result of the 1st internal http client call)
    // I am not sure if this is correct
    writer.size() == 4
    def trace = writer.get(2)
    trace.size() == 3
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "ratpack"
    span.context().tags["component"] == "handler"
    span.context().spanType == DDSpanTypes.WEB_SERVLET
    !span.context().getErrorFlag()
    span.context().tags["http.url"] == "/"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["http.status_code"] == 200
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null

    //def trace2 = writer.get(1)
    //trace2.size() == 1
    def clientTrace1 = trace[1] // this is in reverse order - should the 2nd http call occur before the first

    clientTrace1.context().serviceName == "unnamed-java-app"
    clientTrace1.context().operationName == "ratpack"
    clientTrace1.context().tags["component"] == "httpclient"
    !clientTrace1.context().getErrorFlag()
    clientTrace1.context().tags["http.url"] == "${external.address}nested2"
    clientTrace1.context().tags["http.method"] == "GET"
    clientTrace1.context().tags["span.kind"] == "client"
    clientTrace1.context().tags["http.status_code"] == 200
    clientTrace1.context().tags["thread.name"] != null
    clientTrace1.context().tags["thread.id"] != null

    def clientTrace2 = trace[2]

    clientTrace2.context().serviceName == "unnamed-java-app"
    clientTrace2.context().operationName == "ratpack"
    clientTrace2.context().tags["component"] == "httpclient"
    !clientTrace2.context().getErrorFlag()
    clientTrace2.context().tags["http.url"] == "${external.address}nested"
    clientTrace2.context().tags["http.method"] == "GET"
    clientTrace2.context().tags["span.kind"] == "client"
    clientTrace2.context().tags["http.status_code"] == 200
    clientTrace2.context().tags["thread.name"] != null
    clientTrace2.context().tags["thread.id"] != null

    def nestedTrace = writer.get(1)
    nestedTrace.size() == 1
    def nestedSpan = nestedTrace[0]

    nestedSpan.context().serviceName == "unnamed-java-app"
    nestedSpan.context().operationName == "ratpack"
    nestedSpan.context().tags["component"] == "handler"
    nestedSpan.context().spanType == DDSpanTypes.WEB_SERVLET
    !nestedSpan.context().getErrorFlag()
    nestedSpan.context().tags["http.url"] == "/nested2"
    nestedSpan.context().tags["http.method"] == "GET"
    nestedSpan.context().tags["span.kind"] == "server"
    nestedSpan.context().tags["http.status_code"] == 200
    nestedSpan.context().tags["thread.name"] != null
    nestedSpan.context().tags["thread.id"] != null

    def nestedTrace2 = writer.get(0)
    nestedTrace2.size() == 1
    def nestedSpan2 = nestedTrace2[0]

    nestedSpan2.context().serviceName == "unnamed-java-app"
    nestedSpan2.context().operationName == "ratpack"
    nestedSpan2.context().tags["component"] == "handler"
    nestedSpan2.context().spanType == DDSpanTypes.WEB_SERVLET
    !nestedSpan2.context().getErrorFlag()
    nestedSpan2.context().tags["http.url"] == "/nested"
    nestedSpan2.context().tags["http.method"] == "GET"
    nestedSpan2.context().tags["span.kind"] == "server"
    nestedSpan2.context().tags["http.status_code"] == 200
    nestedSpan2.context().tags["thread.name"] != null
    nestedSpan2.context().tags["thread.id"] != null
  }

  def "forked executions inherit parent scope"() {
    when:
    def result = ExecHarness.yieldSingle({ spec ->
      // This does the work of the initial instrumentation that occurs on the server registry. Because we are using
      // ExecHarness for testing this does not get executed by the instrumentation
      def ratpackScopeManager = new RatpackScopeManager()
      spec.add(ratpackScopeManager)
      ((ContextualScopeManager) GlobalTracer.get().scopeManager())
        .addScopeContext(ratpackScopeManager)
    }, {
      final Scope scope =
        GlobalTracer.get()
          .buildSpan("ratpack.exec-test")
          .startActive(true)
      scope.span().setBaggageItem("test-baggage", "foo")
      ParallelBatch.of(testPromise(), testPromise()).yield()
    })

    then:
    result.valueOrThrow == ["foo", "foo"]
  }

  Promise<String> testPromise() {
    Promise.sync {
      GlobalTracer.get().activeSpan().getBaggageItem("test-baggage")
    }
  }
}
