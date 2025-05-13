/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @summary Test virtual threads using a custom scheduler
 * @requires vm.continuations
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit CustomScheduler
 */

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadScheduler;
import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class CustomScheduler {
    private static ExecutorService scheduler1;
    private static ExecutorService scheduler2;

    @BeforeAll
    static void setup() {
        scheduler1 = Executors.newFixedThreadPool(1);
        scheduler2 = Executors.newFixedThreadPool(1);
    }

    @AfterAll
    static void shutdown() {
        scheduler1.shutdown();
        scheduler2.shutdown();
    }

    /**
     * Test custom scheduler throwing OOME when unparking a thread.
     */
    @Test
    void testThreadUnparkOOME() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            AtomicInteger counter = new AtomicInteger();
            Executor scheduler = task -> {
                switch (counter.getAndIncrement()) {
                    case 0 -> executor.execute(task);             // Thread.start
                    case 1, 2 -> {                                // unpark attempt 1+2
                        System.err.println("OutOfMemoryError");
                        throw new OutOfMemoryError();
                    }
                    default -> executor.execute(task);
                }
                executor.execute(task);
            };

            // start thread and wait for it to park
            ThreadFactory factory = VThreadScheduler.virtualThreadFactory(scheduler);
            var thread = factory.newThread(LockSupport::park);
            thread.start();
            await(thread, Thread.State.WAITING);

            // unpark thread, this should retry until OOME is not thrown
            LockSupport.unpark(thread);
            thread.join();
        }

    }

    /**
     * Waits for the given thread to reach a given state.
     */
    private void await(Thread thread, Thread.State expectedState) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000; // Wait up to 5 seconds
        while (thread.getState() != expectedState) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timeout waiting for thread to reach " + expectedState);
            }
            if (thread.getState() == Thread.State.TERMINATED) {
                throw new AssertionError("Thread terminated before reaching expected state");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve the interrupt status
                throw new RuntimeException("Test thread was interrupted while waiting for thread to reach " + expectedState, e);
            }
        }
    }
}
