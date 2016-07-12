/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.subscribers.flowable;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.*;

import io.reactivex.internal.fuseable.QueueSubscription;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.QueueDrainHelper;

/**
 * Subscriber that can fuse with the upstream and calls a support interface
 * whenever an event is available.
 *
 * @param <T> the value type
 */
public final class InnerQueuedSubscriber<T> 
extends AtomicReference<Subscription>
implements Subscriber<T>, Subscription {

    /** */
    private static final long serialVersionUID = 22876611072430776L;

    final InnerQueuedSubscriberSupport<T> parent;
    
    final int prefetch;
    
    final int limit;

    Queue<T> queue;
    
    volatile boolean done;
    
    long produced;
    
    int fusionMode;
    
    public InnerQueuedSubscriber(InnerQueuedSubscriberSupport<T> parent, int prefetch) {
        this.parent = parent;
        this.prefetch = prefetch;
        this.limit = prefetch - (prefetch >> 2);
    }
    
    @Override
    public void onSubscribe(Subscription s) {
        if (SubscriptionHelper.setOnce(this, s)) {
            if (s instanceof QueueSubscription) {
                @SuppressWarnings("unchecked")
                QueueSubscription<T> qs = (QueueSubscription<T>) s;
                
                int m = qs.requestFusion(QueueSubscription.ANY);
                if (m == QueueSubscription.SYNC) {
                    fusionMode = m;
                    queue = qs;
                    done = true;
                    parent.innerComplete(this);
                    return;
                }
                if (m == QueueSubscription.ASYNC) {
                    fusionMode = m;
                    queue = qs;
                    QueueDrainHelper.request(get(), prefetch);
                    return;
                }
            }
            
            queue = QueueDrainHelper.createQueue(prefetch);
            
            QueueDrainHelper.request(get(), prefetch);
        }
    }
    
    @Override
    public void onNext(T t) {
        if (fusionMode == QueueSubscription.SYNC) {
            parent.drain();
            return;
        }
        parent.innerNext(this, t);
    }
    
    @Override
    public void onError(Throwable t) {
        parent.innerError(this, t);
    }
    
    @Override
    public void onComplete() {
        parent.innerComplete(this);
    }
    
    @Override
    public void request(long n) {
        long p = produced + n;
        if (p >= limit) {
            produced = 0L;
            get().request(p);
        } else {
            produced = p;
        }
    }
    
    public void requestOne() {
        long p = produced + 1;
        if (p == limit) {
            produced = 0L;
            get().request(p);
        } else {
            produced = p;
        }
    }
    
    @Override
    public void cancel() {
        SubscriptionHelper.dispose(this);
    }
    
    public boolean isDone() {
        return done;
    }
    
    public void setDone() {
        this.done = true;
    }
    
    public Queue<T> queue() {
        return queue;
    }
    
    public int fusionMode() {
        return fusionMode;
    }
}
