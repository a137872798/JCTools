/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues;

import static org.jctools.util.UnsafeAccess.UNSAFE;

import java.util.Queue;

import org.jctools.queues.alt.ConcurrentQueue;
import org.jctools.queues.alt.ConcurrentQueueConsumer;
import org.jctools.queues.alt.ConcurrentQueueProducer;
import org.jctools.util.UnsafeAccess;

abstract class MpscConcurrentQueueL1Pad<E> extends ConcurrentCircularArray<E> {
    long p10, p11, p12, p13, p14, p15, p16;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpscConcurrentQueueL1Pad(int capacity) {
        super(capacity);
    }
}

abstract class MpscConcurrentQueueTailField<E> extends MpscConcurrentQueueL1Pad<E> {
    private final static long TAIL_OFFSET;

    static {
        try {
            TAIL_OFFSET = UnsafeAccess.UNSAFE.objectFieldOffset(MpscConcurrentQueueTailField.class
                    .getDeclaredField("tail"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    private volatile long tail;

    public MpscConcurrentQueueTailField(int capacity) {
        super(capacity);
    }

    protected final long lvTail() {
        return tail;
    }

    protected final boolean casTail(long expect, long newValue) {
        return UnsafeAccess.UNSAFE.compareAndSwapLong(this, TAIL_OFFSET, expect, newValue);
    }

}

abstract class MpscConcurrentQueueMidPad<E> extends MpscConcurrentQueueTailField<E> {
    long p20, p21, p22, p23, p24, p25, p26;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpscConcurrentQueueMidPad(int capacity) {
        super(capacity);
    }
}

abstract class MpscConcurrentQueueHeadCacheField<E> extends MpscConcurrentQueueMidPad<E> {
    private volatile long headCache;

    public MpscConcurrentQueueHeadCacheField(int capacity) {
        super(capacity);
    }

    protected final long lvHeadCache() {
        return headCache;
    }

    protected final void svHeadCache(long v) {
        headCache = v;
    }

}

abstract class MpscConcurrentQueueL2Pad<E> extends MpscConcurrentQueueHeadCacheField<E> {
    long p20, p21, p22, p23, p24, p25, p26;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpscConcurrentQueueL2Pad(int capacity) {
        super(capacity);
    }
}

abstract class MpscConcurrentQueueHeadField<E> extends MpscConcurrentQueueL2Pad<E> {
    private final static long HEAD_OFFSET;
    static {
        try {
            HEAD_OFFSET = UNSAFE.objectFieldOffset(MpscConcurrentQueueHeadField.class
                    .getDeclaredField("head"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    private volatile long head;

    public MpscConcurrentQueueHeadField(int capacity) {
        super(capacity);
    }

    protected final long lvHead() {
        return head;
    }

    protected void soHead(long l) {
        UNSAFE.putOrderedLong(this, HEAD_OFFSET, l);
    }
}

public final class MpscConcurrentQueue<E> extends MpscConcurrentQueueHeadField<E> implements Queue<E>,
        ConcurrentQueue<E>, ConcurrentQueueProducer<E>, ConcurrentQueueConsumer<E> {
    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpscConcurrentQueue(final int capacity) {
        super(capacity);
    }

    @Override
    public boolean offer(final E e) {
        if (null == e) {
            throw new NullPointerException("Null is not a valid element");
        }
        final long currHeadCache = lvHeadCache();
        long currentTail;
        do {
            currentTail = lvTail();
            final long wrapPoint = currentTail - capacity;
            if (currHeadCache <= wrapPoint) {
                long currHead = lvHead();
                if (currHead <= wrapPoint) {
                    return false;
                } else {
                    svHeadCache(currHead);
                }
            }
        } while (!casTail(currentTail, currentTail + 1));
        final long offset = calcOffset(currentTail);
        soElement(offset, e);
        return true;
    }

    /**
     * @param e a bludgeoned hamster
     * @return 1 if full, -1 if CAS failed, 0 if successful
     */
    public int tryOffer(final E e) {
        if (null == e) {
            throw new NullPointerException("Null is not a valid element");
        }

        long currentTail = lvTail();
        final long currHeadCache = lvHeadCache();
        final long wrapPoint = currentTail - capacity;
        if (currHeadCache <= wrapPoint) {
            long currHead = lvHead();
            if (currHead <= wrapPoint) {
                return 1; // FULL
            } else {
                svHeadCache(currHead);
            }
        }
        if (!casTail(currentTail, currentTail + 1)) {
            return -1; // CAS FAIL
        }
        final long offset = calcOffset(currentTail);
        soElement(offset, e);
        return 0; // AWESOME
    }

    @Override
    public E poll() {
        final long currHead = lvHead();
        final long offset = calcOffset(currHead);
        final E[] lb = buffer;
        // If we can't see the next available element, consider the queue empty
        final E e = lvElement(lb, offset);
        if (null == e) {
            return null; // EMPTY
        }
        spElement(lb, offset, null);
        soHead(currHead + 1);
        return e;
    }

    @Override
    public E peek() {
        return lpElement(calcOffset(lvHead()));
    }

    @Override
    public int size() {
        return (int) (lvTail() - lvHead());
    }

    @Override
    public ConcurrentQueueConsumer<E> consumer() {
        return this;
    }

    @Override
    public ConcurrentQueueProducer<E> producer() {
        return this;
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
