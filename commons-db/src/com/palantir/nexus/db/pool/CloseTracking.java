package com.palantir.nexus.db.pool;

import java.sql.Connection;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.FinalizableReferenceQueue;
import com.google.common.base.FinalizableWeakReference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.palantir.nexus.db.ResourceCreationLocation;

public class CloseTracking {
    private static final Logger log = LoggerFactory.getLogger(CloseTracking.class);

    private CloseTracking() {
        // nope
    }

    public static ConnectionManager wrap(ConnectionManager delegate) {
        return wrap(ResourceTypes.CONNECTION_MANAGER, delegate);
    }

    public static Connection wrap(Connection delegate) {
        return wrap(ResourceTypes.CONNECTION, delegate);
    }

    public static <R, E extends Exception> R wrap(final ResourceType<R, E> type, final R delegate) {
        if (!log.isErrorEnabled()) {
            // We leave this check in in case this goes haywire in the field.
            // This way setting the log level for CloseTracking to OFF can
            // still disable it entirely.
            return delegate;
        }
        final Tracking tracking = new Tracking(type.name());
        R wrapped = type.closeWrapper(delegate, new ResourceOnClose<E>() {
            @Override
            public void close() throws E {
                tracking.close();
                type.close(delegate);
            }
        });
        destructorReferences.add(new MyReference(wrapped, tracking));
        return wrapped;
    }

    private static final class Tracking {
        private final String typeName;
        private final Throwable createTrace;

        private boolean closed = false;

        public Tracking(String typeName) {
            this.typeName = typeName;
            this.createTrace = new ResourceCreationLocation("This is where the " + typeName + " was allocated");
        }

        public synchronized void close() {
            closed = true;
        }

        public synchronized void check() {
            if (!closed) {
                log.error(typeName + " never closed!", createTrace);
            }
        }
    }

    private static final class MyReference<T> extends FinalizableWeakReference<T> {
        private final Tracking tracking;

        public MyReference(T referent, Tracking tracking) {
            super(referent, destructorQueue);

            this.tracking = tracking;
        }

        @Override
        public void finalizeReferent() {
            try {
                tracking.check();
            } finally {
                destructorReferences.remove(this);
            }
        }
    }

    // We maintain hard references to the custom weak references since
    // otherwise they themselves can get collected and thus never enqueued.
    private static final Set<MyReference<?>> destructorReferences = Sets.newSetFromMap(Maps.<MyReference<?>, Boolean>newConcurrentMap());
    private static final FinalizableReferenceQueue destructorQueue = new FinalizableReferenceQueue();
}
