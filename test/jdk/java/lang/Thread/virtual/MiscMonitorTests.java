/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test id=Xint
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @modules java.base/java.lang:+open
 * @run junit/othervm -Xint MiscMonitorTests
 */

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.thread.VThreadScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiscMonitorTests {
    static final int CARRIER_COUNT = Runtime.getRuntime().availableProcessors();
    
    @Test
    void testReleaseOnYieldRecursive() throws Exception {
        try (var test = new TestReleaseOnYieldRecursive()) {
            test.runTest();
        }
    }

    private static class TestReleaseOnYieldRecursive extends TestBase {
        final Object lock = new Object();
        volatile boolean finish;
        volatile int counter;

        @Override
        void runTest() throws Exception {
            int vthreadCount = CARRIER_COUNT;

            startVThreads(() -> foo(), vthreadCount, "Batch1");
            sleep(500);  // Give time for threads to reach Thread.yield
            startVThreads(() -> bar(), vthreadCount, "Batch2");

            while (counter != 2 * vthreadCount) {
                Thread.onSpinWait();
            }
            finish = true;
            joinVThreads();
        }

        void foo() {
           Object lock = new Object();
            synchronized (lock) {
                while (!finish) {
                    Thread.yield();
                }
            }
            System.err.println("Exiting foo from thread " + Thread.currentThread().getName());
        }

        void bar() {
            synchronized (lock) {
                counter++;
            }
            recursive(10);
            System.err.println("Exiting bar from thread " + Thread.currentThread().getName());
        };

        void recursive(int count) {
            synchronized (Thread.currentThread()) {
                if (count > 0) {
                    recursive(count - 1);
                } else {
                    synchronized (lock) {
                        counter++;
                        Thread.yield();
                    }
                }
            }
        }
    }

    /**
     * Test contention on monitorenter with synchronized methods.
     */
    @Test
    void testContentionWithSyncMethods() throws Exception {
        try (var test = new TestContentionWithSyncMethods()) {
            test.runTest();
        }
    }

    private static class TestContentionWithSyncMethods extends TestBase {
        static final int MONITOR_COUNT = 12;
        final Object[] lockArray = new Object[MONITOR_COUNT];
        final AtomicInteger workerCount = new AtomicInteger(0);
        volatile boolean finish;

        @Override
        void runTest() throws Exception {
            int vthreadCount = CARRIER_COUNT * 8;
            for (int i = 0; i < MONITOR_COUNT; i++) {
                lockArray[i] = new Object();
            }

            startVThreads(() -> foo(), vthreadCount, "VThread");

            sleep(5000);
            finish = true;
            joinVThreads();
            assertEquals(vthreadCount, workerCount.get());
        }

        void foo() {
            Object myLock = new Object();

            while (!finish) {
                int lockNumber = ThreadLocalRandom.current().nextInt(0, MONITOR_COUNT - 1);
                synchronized (myLock) {
                    synchronized (lockArray[lockNumber]) {
                        recursive(lockNumber, myLock);
                    }
                }
            }
            workerCount.getAndIncrement();
            System.err.println("Exiting foo from thread " + Thread.currentThread().getName());
        };

        synchronized void recursive(int depth, Object myLock) {
            if (depth > 0) {
                recursive(depth - 1, myLock);
            } else {
                if (Math.random() < 0.5) {
                    Thread.yield();
                } else {
                    synchronized (myLock) {
                        Thread.yield();
                    }
                }
            }
        }
    }

    private static abstract class TestBase implements AutoCloseable {
        final ExecutorService scheduler = Executors.newFixedThreadPool(CARRIER_COUNT);
        final List<Thread[]> vthreadList = new ArrayList<>();

        abstract void runTest() throws Exception;

        void startVThreads(Runnable r, int count, String name) {
            Thread vthreads[] = new Thread[count];
            for (int i = 0; i < count; i++) {
                vthreads[i] = VThreadScheduler.virtualThreadBuilder(scheduler).name(name + "-" + i).start(r);
            }
            vthreadList.add(vthreads);
        }

        void joinVThreads() throws Exception {
            for (Thread[] vthreads : vthreadList) {
                for (Thread vthread : vthreads) {
                    vthread.join();
                }
            }
        }

        void sleep(long ms) throws Exception {
            Thread.sleep(ms);
        }

        @Override
        public void close() {
            scheduler.close();
        }
    }
}
