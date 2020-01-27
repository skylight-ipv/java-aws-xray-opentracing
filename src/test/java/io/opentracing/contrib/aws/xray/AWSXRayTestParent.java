package io.opentracing.contrib.aws.xray;

import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.entities.SegmentImpl;
import com.amazonaws.xray.entities.TraceHeader;
import com.amazonaws.xray.entities.TraceID;
import io.opentracing.Tracer;

import java.util.Collections;

/**
 * @author ashley.mercer@skylightipv.com
 */
abstract class AWSXRayTestParent {

    final AWSXRayRecorder awsxRayRecorder = AWSXRayRecorderBuilder.defaultRecorder();

    /**
     * Make sure this reference stays as pure {@link Tracer} since we don't
     * want to rely on implementation-specific details or return types.
     */
    final Tracer tracer = new AWSXRayTracer(awsxRayRecorder);

    /**
     * A sample {@link com.amazonaws.xray.entities.TraceHeader} value to use
     * for testing. NB we use all parts (root, parent, sampling decision) to
     * ensure that they all get propagated correctly.
     */
    final TraceHeader traceHeader = new TraceHeader(
        new TraceID(),
        "0f15eadda7879f1d",
        TraceHeader.SampleDecision.SAMPLED
    );

    /**
     * @param operationName the operation name
     */
    AWSXRaySpan mockSpan(String operationName) {
        return new AWSXRaySpan(
                new SegmentImpl(awsxRayRecorder, operationName),
                new AWSXRaySpanContext("mock-span", Collections.emptyMap()));
    }
}
