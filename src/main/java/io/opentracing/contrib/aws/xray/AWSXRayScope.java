package io.opentracing.contrib.aws.xray;

import io.opentracing.Scope;

/**
 * @see Scope
 * @author ashley.mercer@skylightipv.com
 */
class AWSXRayScope implements Scope {

    private final AWSXRayScopeManager scopeManager;
    private final AWSXRayScope previousScope;

    private final AWSXRaySpan span;

    AWSXRayScope(AWSXRayScopeManager scopeManager, AWSXRayScope previousScope, AWSXRaySpan span) {
        this.scopeManager = scopeManager;
        this.previousScope = previousScope;
        this.span = span;
    }

    @Override
    public void close() {
        scopeManager.setCurrentScope(previousScope);
    }

    AWSXRaySpan span() {
        return span;
    }
}
