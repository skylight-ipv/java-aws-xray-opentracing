package io.opentracing.contrib.aws.xray;

import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.TraceHeader;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author ashley.mercer@skylightipv.com
 */
class AWSXRaySpanBuilderTests extends AWSXRayTestParent {

    private final AWSXRayRecorder recorder = AWSXRayRecorderBuilder.defaultRecorder();
    private final AWSXRayTracer tracer = new AWSXRayTracer(recorder);

    @Test
    @DisplayName("set span name")
    void setSpanName() {
        final Span span = tracer
                .buildSpan("test-span-name")
                .start();

        assertEquals("test-span-name", ((AWSXRaySpan) span).getEntity().getName());
    }

    @Test
    @DisplayName("set active span")
    void setActiveSpan() {
        final Span activeSpan = tracer
                .buildSpan("test-active-span")
                .start();
        final Scope activeScope = tracer.activateSpan(activeSpan);

        assertEquals(activeSpan, tracer.activeSpan());

        activeScope.close();
    }

    @Test
    @DisplayName("set trace header in baggage")
    void setTraceHeaderInBaggage() {
        final Span activeSpan = tracer
                .buildSpan("test-trace-header")
                .start();
        final Scope activeScope = tracer.activateSpan(activeSpan);

        final String activeTraceHeader = activeSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(activeTraceHeader);
        assertTrue(activeTraceHeader.contains(((AWSXRaySpan) activeSpan).getEntity().getTraceId().toString()));

        activeScope.close();
    }

    @Test
    @DisplayName("set implicit active span as parent")
    void setImplicitParentSpan() {
        final Span parentSpan = tracer
                .buildSpan("parent-span")
                .start();
        final Scope parentScope = tracer.activateSpan(parentSpan);

        final Span childSpan =  tracer
                .buildSpan("child-span")
                .start();
        final Scope childScope = tracer.activateSpan(childSpan);

        final Entity parentEntity = ((AWSXRaySpan) parentSpan).getEntity();
        final Entity childEntity  = ((AWSXRaySpan) childSpan).getEntity();

        assertFalse(parentEntity.getSubsegments().isEmpty());
        assertEquals(parentEntity, childEntity.getParent());
        assertEquals(parentEntity.getTraceId(), childEntity.getParent().getTraceId());

        // Check that trace header is correctly set in the child
        final String childTraceHeader = childSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(childTraceHeader);
        assertTrue(childTraceHeader.contains(parentEntity.getTraceId().toString()));
        assertTrue(childTraceHeader.contains(parentEntity.getId()));

        childScope.close();
        parentScope.close();
    }

    @Test
    @DisplayName("set explicit span as parent")
    void setExplicitParentSpan() {

        // NB we *don't* startActive here - assume this Span
        // object came from somewhere else in the code
        final AWSXRaySpan explicitParentSpan = mockSpan("explicit-parent-span");

        // This implicit parent should be ignored by SpanBuilder
        // when we set the explicit parent
        final Span implicitParentSpan = tracer
                .buildSpan("implicit-parent-span")
                .start();
        final Scope implicitParentScope = tracer.activateSpan(implicitParentSpan);

        final Span childSpan = tracer
                .buildSpan("child-span")
                .asChildOf(explicitParentSpan)
                .start();
        final Scope childScope = tracer.activateSpan(childSpan);

        final Entity explicitParentEntity = explicitParentSpan.getEntity();
        final Entity implicitParentEntity = ((AWSXRaySpan) implicitParentSpan).getEntity();
        final Entity childEntity = ((AWSXRaySpan) childSpan).getEntity();

        assertFalse(explicitParentEntity.getSubsegments().isEmpty());
        assertTrue(implicitParentEntity.getSubsegments().isEmpty());

        assertEquals(explicitParentEntity, childEntity.getParent());
        assertNotEquals(explicitParentEntity.getId(), childEntity.getId());

        // Check that trace header is correctly set in the child
        final String childTraceHeader = childSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(childTraceHeader);
        assertTrue(childTraceHeader.contains(explicitParentEntity.getTraceId().toString()));
        assertTrue(childTraceHeader.contains(explicitParentEntity.getId()));

        childScope.close();
        implicitParentScope.close();
    }

    @Test
    @DisplayName("set explicit span as parent from remote server")
    void setExplicitParentSpanFromRemote() {

        // SpanContext can be passed to remote servers using inject() and
        // extract(), so assume we read this in from e.g. HTTP headers
        final SpanContext remoteContext = new AWSXRaySpanContext("child-span", Collections.singletonMap(
                TraceHeader.HEADER_KEY,
                traceHeader.toString()
        ));

        final Span childSpan =  tracer
                .buildSpan("child-span")
                .asChildOf(remoteContext)
                .start();
        final Scope childScope = tracer.activateSpan(childSpan);

        final Entity childEntity  = ((AWSXRayScope) childScope).span().getEntity();

        assertEquals(childEntity.getParentSegment().getTraceId(), traceHeader.getRootTraceId());
        assertEquals(childEntity.getParentSegment().getId(), traceHeader.getParentId());

        // Check that trace header is correctly set in the child
        final String childTraceHeader = childSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(childTraceHeader);

        childScope.close();
    }

    @Test
    @DisplayName("ignore implicit active span on ignoreActiveSpan")
    void ignoreImplicitParentSpan() {
        final Span parentSpan = tracer
                .buildSpan("parent-span")
                .start();
        final Scope parentScope = tracer.activateSpan(parentSpan);

        final Span childSpan = tracer
                .buildSpan("child-span")
                .ignoreActiveSpan()
                .start();
        final Scope childScope = tracer.activateSpan(childSpan);

        final Entity parentEntity = ((AWSXRayScope) parentScope).span().getEntity();
        final Entity childEntity = ((AWSXRayScope) childScope).span().getEntity();

        assertTrue(parentEntity.getSubsegments().isEmpty());
        assertNull(childEntity.getParent());
        assertNotEquals(parentEntity.getParentSegment().getTraceId(), childEntity.getParentSegment().getTraceId());

        // Check that trace header is correctly set in the child
        final String childTraceHeader = childSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(childTraceHeader);
        assertTrue(childTraceHeader.contains(childEntity.getParentSegment().getTraceId().toString()));
        assertFalse(childTraceHeader.contains(parentEntity.getParentSegment().getTraceId().toString()));
        assertFalse(childTraceHeader.contains(parentEntity.getId()));

        childScope.close();
        parentScope.close();
    }

    /**
     * In systems where the surrounding code is using X-Ray directly, but
     * not the OpenTracing API, we should detect if a trace is already in
     * progress. For example, in AWS Lambda functions, the lambda server
     * creates a top-level trace segment for the whole function call
     *
     * @see <a href="https://docs.aws.amazon.com/xray/latest/devguide/xray-services-lambda.html">https://docs.aws.amazon.com/xray/latest/devguide/xray-services-lambda.html</a>
     */
    @Test
    @DisplayName("detect a pre-existing X-Ray trace")
    void detectPreExisting() {
        final Segment parentEntity = awsxRayRecorder.beginSegment("pre-existing-trace");

        final Span childSpan = tracer
                .buildSpan("child-of-pre-existing-trace")
                .start();
        final Scope childScope = tracer.activateSpan(childSpan);

        final Entity childEntity = ((AWSXRayScope) childScope).span().getEntity();

        assertFalse(parentEntity.getSubsegments().isEmpty());
        assertEquals(parentEntity, childEntity.getParent());

        // Check that trace header is correctly set in the child
        final String childTraceHeader = childSpan.getBaggageItem(TraceHeader.HEADER_KEY);
        assertNotNull(childTraceHeader);
        assertTrue(childTraceHeader.contains(parentEntity.getTraceId().toString()));
        assertTrue(childTraceHeader.contains(parentEntity.getId()));

        childScope.close();
    }

    @Test
    @DisplayName("set tags correctly")
    @SuppressWarnings("unchecked")
    void setTags() {
        final Span span = tracer
                .buildSpan("test-set-tags")
                .withTag("http.request.method", "POST")
                .withTag("http.response.status_code", 503)
                .withTag("fault", true)
                .start();
        final Scope scope = tracer.activateSpan(span);

        final Entity entity = ((AWSXRayScope) scope).span().getEntity();
        assertEquals("POST", ((Map<String, Object>) entity.getHttp().get("request")).get("method"));
        assertEquals(503,    ((Map<String, Object>) entity.getHttp().get("response")).get("status_code"));
        assertTrue(entity.isFault());

        scope.close();
    }

    @Test
    @DisplayName("set start timestamp correctly")
    void setStartTimestamp() {
        final Span span = tracer
                .buildSpan("test-set-start-timestamp")
                .withStartTimestamp(1551016321000000L)
                .start();
        final Scope scope = tracer.activateSpan(span);

        final Entity entity = ((AWSXRayScope) scope).span().getEntity();
        assertEquals(1551016321.0, entity.getStartTime());

        scope.close();
    }
}
