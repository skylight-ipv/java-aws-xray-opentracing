package io.opentracing.contrib.aws.xray;

import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.contexts.LambdaSegmentContext;
import com.amazonaws.xray.entities.*;
import io.opentracing.*;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Top-level OpenTracing {@link Tracer} implementation which is backed
 * by the AWS X-Ray client libraries.
 *
 * @see <a href="https://opentracing.io">https://opentracing.io</a>
 * @see <a href="https://docs.aws.amazon.com/xray/latest/devguide/aws-xray.html">https://docs.aws.amazon.com/xray/latest/devguide/aws-xray.html</a>
 * @author ashley.mercer@skylightipv.com
 */
@SuppressWarnings("WeakerAccess")
public class AWSXRayTracer implements Tracer {

    private static final Pattern spanNameInvalidCharactersRegex = Pattern.compile("[^-A-Za-z0-9 \t_.:/%&#=+@]");
    private static final Logger log = LoggerFactory.getLogger(AWSXRayTracer.class);

    private final AWSXRayRecorder xRayRecorder;
    private final AWSXRayScopeManager scopeManager;

    public AWSXRayTracer(AWSXRayRecorder xRayRecorder) {
        this.xRayRecorder = xRayRecorder;
        this.scopeManager = new AWSXRayScopeManager(xRayRecorder);
    }

    @Override
    public ScopeManager scopeManager() {
        return scopeManager;
    }

    @Override
    public AWSXRaySpan activeSpan() {
        return scopeManager.activeSpan();
    }

    @Override
    public SpanBuilder buildSpan(String operationName) {
        return new AWSXRaySpanBuilderImpl(operationName);
    }

    @Override
    public Scope activateSpan(Span span) {
        return scopeManager.activate(span);
    }

    @Override
    public void close() {
    }

    @Override
    public <C> void inject(SpanContext spanContext, Format<C> format, C carrier) {
        if (format == Format.Builtin.TEXT_MAP || format == Format.Builtin.HTTP_HEADERS) {
            final TextMap textMap = (TextMap) carrier;
            spanContext.baggageItems().forEach(e -> textMap.put(e.getKey(), e.getValue()));
        }
        else {
            throw new UnsupportedOperationException("Format " + format.toString() +  " is not currently supported");
        }
    }

    @Override
    public <C> SpanContext extract(Format<C> format, C carrier) {
        if (format == Format.Builtin.TEXT_MAP || format == Format.Builtin.HTTP_HEADERS) {
            final TextMap textMap = (TextMap) carrier;
            final Map<String, String> baggage = new HashMap<>();
            for (Map.Entry<String, String> e : textMap) { baggage.put(e.getKey(), e.getValue()); }
            final String spanId = Optional.ofNullable(activeSpan())
                    .flatMap(span -> Optional.ofNullable(span.getEntity()))
                    .flatMap(entity -> Optional.ofNullable(entity.getId())).orElse("");

            return new AWSXRaySpanContext(spanId, baggage);
        }
        else {
            throw new UnsupportedOperationException("Format " + format.toString() +  " is not currently supported");
        }
    }

    /**
     * AWS-specific {@link io.opentracing.Tracer.SpanBuilder} implementation
     */
    private final class AWSXRaySpanBuilderImpl implements SpanBuilder {

        private final String operationName;

        private final Map<String, String>  stringTags;
        private final Map<String, Boolean> booleanTags;
        private final Map<String, Number>  numberTags;

        /**
         * AWS X-Ray timestamps are stored a number of seconds since
         * the UNIX epoch, with the fractional part giving sub-second
         * precision. Defaults to creation time of this builder.
         *
         * @see #withStartTimestamp(long)
         * @see Entity#getStartTime()
         */
        private final AtomicReference<Double> startTimestampEpochSeconds;

        /**
         * @see SpanBuilder#ignoreActiveSpan()
         */
        private final AtomicReference<Boolean> ignoreActiveSpan;

        /**
         * Currently only support a single reference to the parent Span (if
         * it exists). Other references are not supported.
         *
         * @see References
         */
        private final Map<String, SpanContext> references;

        private AWSXRaySpanBuilderImpl(String operationName) {
            if (spanNameInvalidCharactersRegex.matcher(operationName).find()) {
                final String replacement = spanNameInvalidCharactersRegex.matcher(operationName).replaceAll("-");
                log.warn("operation name «{}» is invalid; using «{}» instead", operationName, replacement);
                this.operationName = replacement;
            } else {
                this.operationName = operationName;
            }

            this.stringTags = new HashMap<>();
            this.booleanTags = new HashMap<>();
            this.numberTags = new HashMap<>();

            this.startTimestampEpochSeconds = new AtomicReference<>();
            this.ignoreActiveSpan = new AtomicReference<>(false);
            this.references = new ConcurrentHashMap<>();
        }

        @Override
        public SpanBuilder asChildOf(SpanContext parent) {
            return addReference(References.CHILD_OF, parent);
        }

        @Override
        public SpanBuilder asChildOf(Span parent) {
            if (parent == null) {
                return this;
            }
            else if (parent instanceof AWSXRaySpan) {
                return addReference(References.CHILD_OF, new CapturingSpanContext((AWSXRaySpan) parent));
            }
            else {
                return addReference(References.CHILD_OF, parent.context());
            }
        }

        @Override
        public SpanBuilder addReference(String referenceType, SpanContext referencedContext) {
            if (references.containsKey(referenceType)) {
                log.warn("Replacing reference of type '" + referenceType + "': multiple references of the same type are not supported by X-Ray");
            }
            references.put(referenceType, referencedContext);
            return this;
        }

        @Override
        public SpanBuilder ignoreActiveSpan() {
            ignoreActiveSpan.set(true);
            return this;
        }

        @Override
        public SpanBuilder withTag(String key, String value) {
            stringTags.put(key, value);
            return this;
        }

        @Override
        public SpanBuilder withTag(String key, boolean value) {
            booleanTags.put(key, value);
            return this;
        }

        @Override
        public SpanBuilder withTag(String key, Number value) {
            numberTags.put(key, value);
            return this;
        }

        @Override
        public <T> SpanBuilder withTag(Tag<T> tag, T value) {
            if(value instanceof Number) {
                withTag(tag.getKey(), (Number) value);
            } else if (value instanceof Boolean) {
                withTag(tag.getKey(), (Boolean) value);
            } else {
                stringTags.put(tag.getKey(), value.toString());
            }
            return this;
        }

        @Override
        public SpanBuilder withStartTimestamp(long microseconds) {
            startTimestampEpochSeconds.set(microseconds / 1000.0 / 1000.0);
            return this;
        }

        @Override
        public Span start() {

            // X-Ray only supports parent-child relationships between spans
            // (OpenTracing allows for other references e.g. FOLLOWS_FROM)
            references.forEach((key, value) -> {
                if (!References.CHILD_OF.equals(key)) {
                    log.warn("Ignoring reference of type '" + key + "': references of this type are not supported by X-Ray");
                }
            });

            // If an explicit CHILD_OF reference is set, this should override
            // any (implicit) reference to the current trace entity
            final Entity originalTraceEntity = xRayRecorder.getTraceEntity();
            final SpanContext explicitParentContext = references.get(References.CHILD_OF);

            final Entity parentEntity;
            final Map<String, String> parentBaggage;

            // Because X-Ray an OpenTracing maintain their references to the
            // "current" trace separately, we can be in one of four possible states:
            //
            // 1. an explicit parent is set, and it has captured a full AWSXRaySpan
            //    i.e. this is an in-memory Span with a real X-Ray Entity
            //
            if (explicitParentContext instanceof CapturingSpanContext) {
                parentEntity = ((CapturingSpanContext) explicitParentContext).span.getEntity();
                parentBaggage = AWSXRayUtils.extract(explicitParentContext.baggageItems());
            }

            // 2. an explicit parent is set but it doesn't have an X-Ray Entity
            //    attached: we can present a FacadeSegment to X-Ray
            //
            else if (explicitParentContext != null) {

                // If the parent context has a valid AWS trace ID in its baggage
                // (e.g. it came from some remote upstream server) then extract
                // the trace and parent segment IDs here
                TraceHeader traceHeader = null;
                for (Map.Entry<String, String> e : explicitParentContext.baggageItems()) {
                    if (TraceHeader.HEADER_KEY.equals(e.getKey())) {
                        traceHeader = TraceHeader.fromString(e.getValue());
                    }
                }
                final TraceID traceId = null == traceHeader ? null : traceHeader.getRootTraceId();
                final String parentId = null == traceHeader ? null : traceHeader.getParentId();
                final TraceHeader.SampleDecision sampleDecision = traceHeader == null ? null : traceHeader.getSampled();

                // NB the default FacadeSegment class throws exceptions but we want
                // to allow subsegments to be added and removed (even though both
                // of these are ultimately a no-op)
                parentEntity = new FacadeSegment(xRayRecorder, traceId, parentId, sampleDecision) {
                    @Override public void addSubsegment(Subsegment subsegment) {}
                    @Override public void removeSubsegment(Subsegment subsegment) {}
                };
                parentBaggage = AWSXRayUtils.extract(explicitParentContext.baggageItems());
            }

            // 3. no explicit parent is set, but ignoreActiveSpan has been set so
            //    make sure the parent Entity is null (i.e. we'll create a new
            //    Segment in X-Ray terms)
            //
            else if (ignoreActiveSpan.get()) {
                parentEntity = null;
                parentBaggage = Collections.emptyMap();
            }

            // 4. no explicit parent, and ignoreActiveSpan is not set so create an
            //    implicit reference to the current trace entity (if it exists:
            //    if it's null we'll instead end up creating a top-level Segment
            //    instead)
            //
            else {
                parentEntity = originalTraceEntity;
                parentBaggage = Collections.emptyMap();
            }

            // X-Ray automatically maintains internal references between Segments and
            // Subsegments - rather than trying to replicate that logic here, we cheat
            // by (temporarily) overwriting the parent trace Entity, creating the new
            // Entity, then setting it back once we're done
            xRayRecorder.setTraceEntity(parentEntity);

            // Special case when running in AWS Lambda: the Lambda infrastructure
            // creates a top-level trace Segment to which we do not have access, so
            // creating another Segment here would be an error. Instead, we need to
            // forcibly create a Subsegment.
            final boolean isAwsLambda = xRayRecorder.getSegmentContextResolverChain().resolve() instanceof LambdaSegmentContext;

            final Entity childEntity = (xRayRecorder.getTraceEntity() == null && !isAwsLambda) ?
                    xRayRecorder.beginSegment(operationName) :
                    xRayRecorder.beginSubsegment(operationName);

            // Set the original trace entity back on AWSXRayRecorder as soon as possible
            xRayRecorder.setTraceEntity(originalTraceEntity);

            // AWS X-Ray doesn't support the notion of "not-yet-started" segments
            // so set the Entity to be "in progress"
            childEntity.setInProgress(true);

            // Default to "now" if an explicit start time wasn't set
            startTimestampEpochSeconds.compareAndSet(null, Instant.now().toEpochMilli() / 1000.0);
            childEntity.setStartTime(startTimestampEpochSeconds.get());

            // Baggage items should mostly be carried over from the parent Span's
            // context (if it exists) to the child Span; however, the TraceHeader
            // should be replaced with the new value for the child span
            final TraceHeader traceHeader = new TraceHeader(
                 childEntity.getParentSegment().getTraceId(),
                 null == parentEntity ? null : parentEntity.getId(),
                 childEntity.getParentSegment().isSampled() ? TraceHeader.SampleDecision.SAMPLED : TraceHeader.SampleDecision.NOT_SAMPLED
            );

            final Map<String, String> childBaggage = new HashMap<>(parentBaggage);
            childBaggage.put(TraceHeader.HEADER_KEY, traceHeader.toString());

            final AWSXRaySpanContext newSpanContext = new AWSXRaySpanContext(childEntity.getId(), childBaggage);

            // Defer to AWSXRaySpan to set tag values since this will handle
            // converting to X-Ray's naming conventions and format
            final AWSXRaySpan newSpan = new AWSXRaySpan(childEntity, newSpanContext);
            stringTags.forEach(newSpan::setTag);
            booleanTags.forEach(newSpan::setTag);
            numberTags.forEach(newSpan::setTag);

            return newSpan;
        }
    }

    /**
     * Parent-child relationships between Spans are typically only defined in
     * terms of the SpanContext (i.e. we only need to know the parent span's
     * trace and span ID). However, X-Ray also holds directly object references
     * to the underlying Segment and Subsegment instances, so try to capture
     * the full AWSXRaySpan instance here if we can.
     */
    private static final class CapturingSpanContext implements SpanContext {
        private final AWSXRaySpan span;

        public CapturingSpanContext(AWSXRaySpan span) {
            this.span = span;
        }

        @Override
        public Iterable<Map.Entry<String, String>> baggageItems() {
            return span.context().baggageItems();
        }

        @Override
        public String toTraceId() {
            return span.context().getBaggageItem(TraceHeader.HEADER_KEY);
        }

        @Override
        public String toSpanId() {
            return "";
        }
    }
}
