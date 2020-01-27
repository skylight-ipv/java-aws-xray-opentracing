package io.opentracing.contrib.aws.xray;

import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.entities.Entity;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link ScopeManager} when tracing with AWS X-Ray.
 * The X-Ray libraries also have their own lifecycle management and
 * reference counting for the underlying trace {@link Entity}s, so we
 * need to hook in to these to keep OpenTracing and X-Ray in sync.
 *
 * @author ashley.mercer@skylightipv.com
 */
class AWSXRayScopeManager implements ScopeManager {

    private static final Logger log = LoggerFactory.getLogger(AWSXRayScopeManager.class);

    /**
     * The {@link AWSXRayRecorder} class keeps track of the current trace
     * {@link Entity} on this thread, and we need to keep its view and our
     * view of the world in sync.
     */
    private final AWSXRayRecorder xRayRecorder;

    /**
     * X-Ray already keeps track of the current active {@link Entity}, but
     * additionally track here the whole {@link Scope} in order to be able
     * to recover the previous state on this thread once the current span
     * is finished / closed.
     */
    private final ThreadLocal<AWSXRayScope> currentScope;

    /**
     * Set the current {@link Scope} back to the given value. All changes to
     * the {@link #currentScope} value should pass through this method, since
     * it also hooks into the underlying X-Ray classes.
     *
     * @param scope the new current scope
     */
    void setCurrentScope(AWSXRayScope scope) {
        currentScope.set(scope);
        xRayRecorder.setTraceEntity(scope == null ? null : scope.span().getEntity());
    }

    AWSXRayScopeManager(AWSXRayRecorder xRayRecorder) {
        this.xRayRecorder = xRayRecorder;
        this.currentScope = new ThreadLocal<>();
    }

    @Override
    public AWSXRaySpan activeSpan() {
        final AWSXRayScope activeScope = this.currentScope.get();
        return activeScope == null ? null : activeScope.span();
    }

    @Override
    public Scope activate(Span span) {
        if (span instanceof AWSXRaySpan) {
            final AWSXRayScope oldScope = currentScope.get();
            final AWSXRayScope newScope = new AWSXRayScope(this, oldScope, (AWSXRaySpan) span);
            setCurrentScope(newScope);
            return newScope;
        }
        else {
            if (span != null) {
                log.warn("Cannot activate Span: expected AWSXRaySpan but got type " + span.getClass().getSimpleName());
            }
            return currentScope.get();
        }
    }
}
