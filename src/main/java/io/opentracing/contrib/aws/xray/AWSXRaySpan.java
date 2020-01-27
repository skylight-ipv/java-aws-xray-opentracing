package io.opentracing.contrib.aws.xray;

import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.Subsegment;
import io.opentracing.Span;
import io.opentracing.contrib.tag.ExtraTags;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tag;
import io.opentracing.tag.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Span} in the OpenTracing API corresponds more or less directly
 * to a trace {@link Entity} in the X-Ray API, with a couple of major differences:
 *
 * <ul>
 *     <li>X-Ray distinguishes between the top-level trace entity, modelled as
 *     a {@link Segment}, and child entities, modelled as {@link Subsegment}. No
 *     such distinction exists in OpenTracing.</li>
 *
 *     <li>OpenTracing allows arbitrary key-value pairs (aka tags) to be stored on
 *     the trace in an approximately flat namespace; by contrast X-Ray stores such
 *     data in a hierarchical structure, and uses different areas of the trace for
 *     different types of data (e.g. HTTP request and response data, versus database
 *     connection data)</li>
 *
 *     <li>X-Ray doesn't really have any concept of a "log" as defined in the
 *     OpenTracing API; distributed logging is better done in AWS by e.g. piping
 *     log files to CloudWatch. We provide some basic facility to store log
 *     statements directly on the trace but this isn't advisable (although it can
 *     be useful for exception / error tracking, since these do show up correctly
 *     in the X-Ray graphs)</li>
 * </ul>
 *
 * @see Span
 * @see Segment
 * @see Subsegment
 * @author ashley.mercer@skylightipv.com
 */
class AWSXRaySpan implements Span {

    private static final Logger log = LoggerFactory.getLogger(AWSXRaySpan.class);

    /**
     * A map of synonyms from OpenTracing naming conventions to X-Ray. This is
     * necessary because X-Ray traces store some information (e.g. HTTP request
     * and response data) in different places on the resulting object, but we
     * want users to be able to continue to use the standard OpenTracing names.
     */
    private static final Map<String, String> TAG_SYNONYMS = new HashMap<>();
    static {
        TAG_SYNONYMS.put(Tags.DB_INSTANCE.getKey(),              "sql.url");
        TAG_SYNONYMS.put(Tags.DB_STATEMENT.getKey(),             "sql.sanitized_query");
        TAG_SYNONYMS.put(Tags.DB_TYPE.getKey(),                  "sql.database_type");
        TAG_SYNONYMS.put(Tags.DB_USER.getKey(),                  "sql.user");
        TAG_SYNONYMS.put(ExtraTags.DB_DRIVER.getKey(),           "sql.driver_version");
        TAG_SYNONYMS.put(ExtraTags.DB_VERSION.getKey(),          "sql.database_version");

        TAG_SYNONYMS.put(Tags.HTTP_METHOD.getKey(),              "http.request.method");
        TAG_SYNONYMS.put(Tags.HTTP_STATUS.getKey(),              "http.response.status");
        TAG_SYNONYMS.put(Tags.HTTP_URL.getKey(),                 "http.request.url");
        TAG_SYNONYMS.put(ExtraTags.HTTP_CLIENT_IP.getKey(),      "http.request.client_ip");
        TAG_SYNONYMS.put(ExtraTags.HTTP_USER_AGENT.getKey(),     "http.request.user_agent");
        TAG_SYNONYMS.put(ExtraTags.HTTP_CONTENT_LENGTH.getKey(), "http.response.content_length");

        TAG_SYNONYMS.put(ExtraTags.VERSION.getKey(),             "service.version");
    }

    /**
     * Reference to the underlying X-Ray trace {@link Entity}
     */
    private final Entity entity;

    /**
     * @see io.opentracing.SpanContext
     */
    private final AWSXRaySpanContext context;

    /**
     * Keep track of whether finish() has been called yet
     */
    private final AtomicBoolean isFinished;

    AWSXRaySpan(Entity entity, AWSXRaySpanContext context) {
        this.entity = entity;
        this.context = context;
        this.isFinished = new AtomicBoolean(false);
    }

    /**
     * @return the underlying X-Ray trace {@link Entity}
     */
    Entity getEntity() {
        return entity;
    }

    @Override
    public AWSXRaySpanContext context() {
        return context;
    }

    @Override
    public Span setTag(String key, String value) {

        // Match for known tags which are handled differently in X-Ray
        if (AWSXRayTags.USER.getKey().equals(key) && entity instanceof Segment) {
            ((Segment) entity).setUser(value);
        }
        else if (AWSXRayTags.ORIGIN.getKey().equals(key) && entity instanceof Segment) {
            ((Segment) entity).setOrigin(value);
        }
        else if (AWSXRayTags.PARENT_ID.getKey().equals(key)) {
            entity.setParentId(value);
        }
        else {
            setTagAny(key, value);
        }
        return this;
    }

    @Override
    public Span setTag(String key, boolean value) {

        // Match for known tags which are handled differently in X-Ray
        if (Tags.ERROR.getKey().equals(key)) {
            entity.setError(value);
        }
        else if (AWSXRayTags.FAULT.getKey().equals(key)) {
            entity.setFault(value);
        }
        else if (AWSXRayTags.THROTTLE.getKey().equals(key)) {
            entity.setThrottle(value);
        }
        else if (AWSXRayTags.IS_SAMPLED.getKey().equals(key) && entity instanceof Segment) {
            ((Segment) entity).setSampled(value);
        }
        else {
            setTagAny(key, value);
        }
        return this;
    }

    @Override
    public Span setTag(String key, Number value) {
        setTagAny(key, value);
        return this;
    }

    @Override
    public <T> Span setTag(Tag<T> tag, T value) {
        setTagAny(tag.getKey(), value);
        return this;
    }

    @Override
    public Span log(String event) {
        return log(Collections.singletonMap(Fields.MESSAGE, event));
    }

    @Override
    public Span log(long timestampMicroseconds, String event) {
        return log(timestampMicroseconds, Collections.singletonMap(Fields.MESSAGE, event));
    }

    @Override
    public Span log(Map<String, ?> fields) {
        return log(Instant.now(), fields);
    }

    @Override
    public Span log(long timestampMicroseconds, Map<String, ?> fields) {
        return log(Instant.ofEpochMilli(timestampMicroseconds / 1000L), fields);
    }

    /**
     * Log arbitrary data to the X-Ray trace. Since X-Ray doesn't really have its own
     * built-in log mechanism (CloudWatch would be a more suitable place for this)
     * we just store the provided data in a special "metadata.logs" area.
     *
     * @param timestamp the timestamp of the provided log
     * @param fields arbitrary fields to store against this timestamp
     */
    private Span log(Instant timestamp, Map<String, ?> fields) {

        // If the provided map contains an exception, use X-Ray's Cause
        // object to record the full stack trace
        final Object errorObject = fields.get(Fields.ERROR_OBJECT);
        if (errorObject instanceof Throwable) {
            entity.addException((Throwable) errorObject);
        }
        else {
            entity.putMetadata(AWSXRayMetadataNamespaces.LOG, timestamp.toString(), fields);
        }
        return this;
    }

    @Override
    public Span setBaggageItem(String key, String value) {
        context.setBaggageItem(key, value);
        return this;
    }

    @Override
    public String getBaggageItem(String key) {
        return context.getBaggageItem(key);
    }

    @Override
    public Span setOperationName(String operationName) {
        throw new UnsupportedOperationException("The AWS X-Ray API does not permit segment names to be changed after creation");
    }

    /**
     * For an arbitrary key and value, store it in the correct place on
     * the underlying X-Ray {@link Entity}:
     *
     * <ul>
     *     <li>X-Ray traces use different naming conventions from OpenTracing
     *     so some OpenTracing names are automatically to X-Ray format</li>
     *     <li>X-Ray traces have special subsections for certain types of
     *     data, e.g. HTTP request and response data</li>
     *     <li>all other fields are stored in the general-purpose "metadata"
     *     part of the X-Ray trace</li>
     * </ul>
     *
     * @param key the tag (name) for the value to be stored
     * @param value the value to be stored
     */
    private void setTagAny(String key, Object value) {

        // First translate the key from OpenTracing names to X-Ray names
        final String awsKey = TAG_SYNONYMS.getOrDefault(key, key);
        if (awsKey != null) {

            // OpenTracing keys are '.'-separated by convention
            final List<String> allKeyParts = Arrays.asList(awsKey.split("\\."));
            final Iterator<String> remainingKeyParts = allKeyParts.iterator();

            // String.split is guaranteed to return at least one element (if the
            // separator didn't appear at all) so we're always safe to get the
            // first element from this iterator
            final String awsKeyPart1 = remainingKeyParts.next();

            // X-Ray Entity uses different Maps to store different types of
            // information and the first part of the key will tell us whether to
            // use one these Maps or not
            if ("annotations".equals(awsKeyPart1)) {
                setTagAny(remainingKeyParts, value, entity.getAnnotations());
            }
            else if ("aws".equals(awsKeyPart1)) {
                setTagAny(remainingKeyParts, value, entity.getAws());
            }
            else if ("http".equals(awsKeyPart1)) {
                setTagAny(remainingKeyParts, value, entity.getHttp());
            }
            // Service-level information is only available on top-level trace Segments
            else if ("service".equals(awsKeyPart1) && entity instanceof Segment) {
                setTagAny(remainingKeyParts, value, ((Segment) entity).getService());
            }
            else if ("sql".equals(awsKeyPart1)) {
                setTagAny(remainingKeyParts, value, entity.getSql());
            }

            // Store everything else in the "metadata" part of the trace
            else {
                final Map<String, Map<String, Object>> metadata = entity.getMetadata();

                //
                // Figure out which namespace to use in the metadata using the following rules:
                //
                // "foo"                    -> "metadata.default.foo"
                // "metadata.foo"           -> "metadata.default.foo"
                // "namespace.foo"          -> "metadata.namespace.foo"
                // "metadata.namespace.foo" -> "metadata.namespace.foo"
                //

                // If the key started with "metadata" chomp it; otherwise assume "metadata"
                // implicitly and revert to using the whole of supplied key for namespacing
                final Iterator<String> metadataKeyParts = "metadata".equals(awsKeyPart1) && remainingKeyParts.hasNext() ?
                        remainingKeyParts :
                        allKeyParts.iterator();

                // Look at the next part of the key: if there are more key parts after this,
                // then awsKeyPart2 is the namespace; otherwise assume the default namespace
                final String awsKeyPart2 = metadataKeyParts.next();
                final String metadataNamespace = metadataKeyParts.hasNext() ?
                        awsKeyPart2 :
                        AWSXRayMetadataNamespaces.DEFAULT;

                // If there are remaining key parts (after the second part) then they are the
                // nested keys; otherwise we only had a 2-part key (and will have used the
                // DEFAULT namespace) so just use the second part as the nested key
                final Iterator<String> namespaceKeyParts = metadataKeyParts.hasNext() ?
                        metadataKeyParts :
                        Collections.singletonList(awsKeyPart2).iterator();

                final Map<String, Object> targetMap = metadata.computeIfAbsent(metadataNamespace, __ -> new ConcurrentHashMap<>());
                setTagAny(namespaceKeyParts, value, targetMap);
            }
        }
    }

    /**
     * <p>
     * Store an arbitrary value in a nested {@link Map} structure, assuming
     * that at each level of nesting we either have a key-value pair, or else
     * a key pointing to a nested Map. This mimics the JSON structure used
     * by X-Ray to transmit tag and baggage data.
     * </p>
     *
     * For example, given the following existing value for targetMap:
     *
     * <pre>
     * {
     *     "http": {
     *         "request": {
     *             "method": "GET",
     *             "url": "http://www.example.com"
     *         }
     *     }
     * }
     * </pre>
     *
     * and the inputs:
     *
     * <pre>
     * keyParts: ["http", "response", "code"],
     * value: 200
     * </pre>
     *
     * we return the value:
     *
     * <pre>
     * {
     *     "http": {
     *         "request": {
     *             "method": "GET",
     *             "url": "http://www.example.com"
     *         },
     *         "response": {
     *             "code": 200
     *         }
     *     }
     * }
     * </pre>
     *
     * NB this method <em>mutates</em> the underlying value of targetMap so care should
     * be taken to only ever pass thread-safe instances (and this method in turn will
     * create thread-safe sub-Maps for nested values).
     *
     * @param remainingKeyParts the list of individual parts of the tag (name) for the value to be stored
     * @param value the value to be stored
     * @param targetMap the target Map instance
     */
    private void setTagAny(Iterator<String> remainingKeyParts, Object value, Map<String, Object> targetMap) {

        // The iterator should never be empty here, but just for safety
        if (remainingKeyParts.hasNext()) {
            final String nextKeyPart = remainingKeyParts.next();

            // The key is further nested so recurse to the next level of the map
            if (remainingKeyParts.hasNext()) {

                @SuppressWarnings("unchecked")
                final Map<String, Object> targetSubMap = (Map<String, Object>) targetMap.computeIfAbsent(nextKeyPart, __ -> new ConcurrentHashMap<>());
                setTagAny(remainingKeyParts, value, targetSubMap);

            // This is the last key, so store at this level
            } else {
                targetMap.put(nextKeyPart, value);
            }
        }
    }

    @Override
    public void finish() {
        finish(Instant.now().toEpochMilli() / 1000.0);
    }

    @Override
    public void finish(long finishMicros) {
        finish(finishMicros / 1000.0 / 1000.0);
    }

    /**
     * Set the end time for this span and close it - this will usually trigger
     * sending the trace data back to AWS.
     *
     * @param finishSeconds timestamp of the end of this span as a number of
     *                      seconds since the UNIX
     */
    private void finish(double finishSeconds) {
        if (isFinished.compareAndSet(false, true)) {
            try {
                entity.setEndTime(finishSeconds);
                entity.close();
            } catch (Exception e) {
                log.error("Failed to close underlying AWS trace Entity: " + e.getMessage(), e);
            }
        }
    }
}
