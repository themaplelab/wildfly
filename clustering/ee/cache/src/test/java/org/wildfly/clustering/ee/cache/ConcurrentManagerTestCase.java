/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.ee.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Assert;
import org.junit.Test;
import org.wildfly.clustering.ee.Manager;
import java.util.concurrent.ThreadFactory;

/**
 * @author Paul Ferraro
 */
public class ConcurrentManagerTestCase {

    private static final int KEYS = 10;
    private static final int SIZE = 100;

    @Test
    public void test() throws InterruptedException, ExecutionException {
ThreadFactory threadFactory = Thread.ofVirtual().factory();

        Manager<Integer, ManagedObject> manager = new ConcurrentManager<>(ManagedObject::created, ManagedObject::closed);
        List<List<Future<ManagedObject>>> keyFutures = new ArrayList<>(KEYS);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
        try {
            for (int i = 0; i < KEYS; ++i) {
                List<Future<ManagedObject>> futures = new ArrayList<>(SIZE);
                keyFutures.add(futures);
                for (int j = 0; j < SIZE; ++j) {
                    int key = i;
                    Callable<ManagedObject> task = () -> {
                        try (ManagedObject object = manager.apply(key, ManagedObject::new)) {
                            Assert.assertTrue(object.isCreated());
                            Assert.assertFalse(object.isClosed());
                            Thread.sleep(10);
                            return object;
                        }
                    };
                    futures.add(executor.submit(task));
                }
            }
            // Wait until all tasks are finished
            for (List<Future<ManagedObject>> futures : keyFutures) {
                for (Future<ManagedObject> future : futures) {
                    future.get();
                }
            }
            // Verify
            for (List<Future<ManagedObject>> futures : keyFutures) {
                for (Future<ManagedObject> future : futures) {
                    ManagedObject object = future.get();
                    Assert.assertTrue(object.toString(), object.isCreated());
                    Assert.assertTrue(object.toString(), object.isClosed());
                }
            }
        } finally {
            executor.shutdown();
        }}
    

    
private static class ManagedObject implements AutoCloseable {
        private volatile boolean created = false;
        private volatile boolean closed = false;
        private final Runnable closeTask;

        ManagedObject(Runnable closeTask) {
            this.closeTask = closeTask;
        }

        void created() {
            this.created = true;
        }

        boolean isCreated() {
            return this.created;
        }

        void closed() {
            this.closed = true;
        }

        boolean isClosed() {
            return this.closed;
        }

        @Override
        public void close() {
            this.closeTask.run();
        }
    }
}
