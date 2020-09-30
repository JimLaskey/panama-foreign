/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @test
 * @bug 0000000
 * @summary Verify NativeAllocator API.
 * @modules java.base/jdk.internal.misc jdk.incubator.foreign/jdk.incubator.foreign.allocator
 * @compile --enable-preview -source ${jdk.version} TestNativeAllocator.java
 * @run main/othervm --enable-preview -ea TestNativeAllocator
 */

import jdk.internal.misc.Unsafe;

import jdk.incubator.foreign.allocator.AtomicRegistry;
import jdk.incubator.foreign.allocator.NativeAllocator;
import jdk.incubator.foreign.allocator.Registry;

import java.util.ArrayList;
import java.util.List;

public class TestNativeAllocator {
    final static Unsafe UNSAFE = Unsafe.getUnsafe();

    final static long K = 1024L;
    final static long M = K * K;
    final static long G = K * M;
    final static long T = K * G;
    final static long P = K * T;

    final static long SMALLEST_SMALL = 8L;
    final static long SMALLEST_MEDIUM = 2L * K + 1;
    final static long SMALLEST_LARGE = 512L * K + 1;
    final static long SMALLEST_SLAB = 64L * M + 1;

    final static long FLOATING_ADDRESS = 0;
    final static long FIXED_ADDRESS = 0x0000600000000000L;
    final static long[] addressVariants = new long[]
            { FLOATING_ADDRESS, FIXED_ADDRESS};
    final static boolean[] booleanVariants = new boolean[]
            { false, true };
    final static int[] partitionVariants = new int[]
            { 0, 16, 32, 128 };
    final static int[] slabVariants = new int[]
            { 0, 4, 2000 };
    final static long[] allocationVariants = new long[]
            { SMALLEST_SMALL, SMALLEST_MEDIUM, SMALLEST_LARGE, SMALLEST_SLAB};

    static class Configuration {
        final long address;
        final boolean isShared;
        final boolean isSecure;
        final int smallPartitionCount;
        final int mediumPartitionCount;
        final int largePartitionCount;
        final int maxSlabCount;

        public Configuration(long address, boolean isShared, boolean isSecure,
                             int smallPartitionCount, int mediumPartitionCount,
                             int largePartitionCount, int maxSlabCount) {
            this.address = address;
            this.isShared = isShared;
            this.isSecure = isSecure;
            this.smallPartitionCount = smallPartitionCount;
            this.mediumPartitionCount = mediumPartitionCount;
            this.largePartitionCount = largePartitionCount;
            this.maxSlabCount = maxSlabCount;
        }

        NativeAllocator newAllocator() {
            return NativeAllocator.create(address, isShared, isSecure,
                    smallPartitionCount, mediumPartitionCount, largePartitionCount,
                    maxSlabCount) ;
        }

        static NativeAllocator oneShotAllocator() {
            return NativeAllocator.create(0L, false, false, 1, 1, 1, 1);
        }

        static NativeAllocator mimimumAllocator() {
            return NativeAllocator.create(0L, false, false, 8, 8, 8, 8);
        }

        static NativeAllocator mimimumAllocatorShared() {
            return NativeAllocator.create(0L, true, false, 8, 8, 8, 8);
        }

        boolean hasZeroCounts() {
            return smallPartitionCount== 0 ||
                    mediumPartitionCount == 0 ||
                    largePartitionCount == 0 ||
                    maxSlabCount == 0;
        }
    }

    final static List<Configuration> configurations;

    static {
        configurations = new ArrayList<>();
        for (long base : addressVariants)
        for (boolean isShared : booleanVariants)
        for (boolean isSecure : booleanVariants)
        for (int smallCount : partitionVariants)
        for (int mediumCount : partitionVariants)
        for (int largeCount : partitionVariants)
        for (int slabCount : slabVariants) {
            configurations.add(new Configuration(base, isShared, isSecure,
                    smallCount, mediumCount, largeCount, slabCount));
        }
    }

    public static void main(String... args) {
        registry();
        atomicRegistry();

        create();
        allocate1();
        allocate2();
        allocate3();
        deallocate();
        reallocate();
        clear();

        allocationSize();
        allocationBase();
        nextAllocation();

        threads();
    }

    static void registry() {
        final int MAXIMUM = 16;
        Registry registry = Registry.create(MAXIMUM);

        int index1 = registry.findFree();
        int index2 = registry.findFree();
        registry.free(index1);
        int index3 = registry.findFree();

        if (index1 != 0 || index2 != 1 || index3 != 0) {
            throw new RuntimeException("Registry find failure");
        }

        for (int i = 2; i < MAXIMUM; i++) {
            registry.findFree();
        }

        int index4 = registry.findFree();
        if (index4 != -1) {
            throw new RuntimeException("Registry full failure");
        }
    }

    static void atomicRegistry() {
        final int MAXIMUM = 16;
        Registry registry = AtomicRegistry.create(MAXIMUM);

        int index1 = registry.findFree();
        int index2 = registry.findFree();
        registry.free(index1);
        int index3 = registry.findFree();

        if (index1 != 0 || index2 != 1 || index3 != 0) {
            throw new RuntimeException("Registry find failure");
        }

        for (int i = 2; i < MAXIMUM; i++) {
            registry.findFree();
        }

        int index4 = registry.findFree();
        if (index4 != -1) {
            throw new RuntimeException("Registry full failure");
        }
    }

    static void create() {
        /*
         * Check create variations
         */
        for (Configuration configuration : configurations) {
            if (configuration.hasZeroCounts()) {
                continue;
            }

            NativeAllocator na = configuration.newAllocator();

            if (na == null) {
                throw new RuntimeException("Failed to create allocator");
            }

            long allocation = na.allocate(512 * 1024);

            if (configuration.address != 0L && allocation != configuration.address) {
                throw new RuntimeException("Not at fixed address");
            }

            na.close();
        }
    }

    static void allocate1() {
        /*
         * Try allocation variations.
         */
        for (Configuration configuration : configurations) {
            NativeAllocator na = configuration.newAllocator();

            if (na == null) {
                if (configuration.hasZeroCounts()) {
                    continue;
                }

                throw new RuntimeException("Allocation failure");
            }

            for (long allocation : allocationVariants) {
                long address = na.allocate(allocation);

                if (address == 0L) {
                    if (allocation == 0L || configuration.hasZeroCounts()) {
                        continue;
                    }

                    throw new RuntimeException("Allocation failure");
                }

                UNSAFE.putLong(address, 0x1234L);
                long value = UNSAFE.getLong(address);

                if (value != 0x1234L) {
                    throw new RuntimeException("Value failure");
                }
            }

            na.close();
        }
    }

    static void allocate2() {
        /*
         * Exhaust a partition.
         */
        try (NativeAllocator na = Configuration.oneShotAllocator()) {
            for (int i = 0; i < 100000; i++) {
                long address = na.allocate(SMALLEST_MEDIUM - 1);

                if (address == 0L) {
                    return;
                }
            }

            throw new RuntimeException("Did not exhaust partition");
        }
    }

    static void allocate3() {
        /*
         * Exhaust slabs.
         */
        try (NativeAllocator na = Configuration.oneShotAllocator()) {
            for (int i = 0; i < 2; i++) {
                long address = na.allocate(SMALLEST_SLAB);

                if (address == 0L) {
                    return;
                }
            }

            throw new RuntimeException("Did not exhaust slabs");
        }
    }

    static void deallocate() {
        /*
         * Make sure it recycles.
         */
        for (Configuration configuration : configurations) {
            if (configuration.hasZeroCounts()) {
                continue;
            }

            try (NativeAllocator na = configuration.newAllocator()) {
                long address1 = na.allocate(SMALLEST_SMALL);
                na.deallocate(address1);
                long address2 = na.allocate(SMALLEST_SMALL);

                if (address1 != address2) {
                    throw new RuntimeException("Not deallocating");
                }
            }
        }
    }

    static void reallocate() {
        try (NativeAllocator na = Configuration.mimimumAllocator()) {
            long address1 = na.allocate(64);
            long address2 = na.reallocate(address1, 63);
            long address3 = na.reallocate(address2, 64);
            long address4 = na.reallocate(address3, 65);
            long address5 = na.reallocate(address3, 64);
            long address6 = na.reallocate(0L, 64);

            if (address1 != address2) {
                throw new RuntimeException("Not reallocating smaller in place");
            }

            if (address2 != address3) {
                throw new RuntimeException("Not reallocating larger but within limit in place");
            }

            if (address3 == address4) {
                throw new RuntimeException("Not reallocating larger in new block");
            }

            if (address1 != address5) {
                throw new RuntimeException("Not recycling old block");
            }

            if (address6 == 0L) {
                throw new RuntimeException("Not reallocating from zero");
            }
        }
    }

    static void clear() {
        try (NativeAllocator na = Configuration.mimimumAllocator()) {
            long address1 = na.allocate(4 * 8);
            long address2 = na.allocate(4 * 8);
            long address3 = na.allocate(4 * 8);
            UNSAFE.putLong(address1 + 0 * 8, 0x1111111111111111L);
            UNSAFE.putLong(address1 + 1 * 8, 0x2222222222222222L);
            UNSAFE.putLong(address1 + 2 * 8, 0x3333333333333333L);
            UNSAFE.putLong(address1 + 3 * 8, 0x4444444444444444L);
            UNSAFE.putLong(address2 + 0 * 8, 0x5555555555555555L);
            UNSAFE.putLong(address2 + 1 * 8, 0x6666666666666666L);
            UNSAFE.putLong(address2 + 2 * 8, 0x7777777777777777L);
            UNSAFE.putLong(address2 + 3 * 8, 0x8888888888888888L);
            UNSAFE.putLong(address3 + 0 * 8, 0x9999999999999999L);
            UNSAFE.putLong(address3 + 1 * 8, 0xAAAAAAAAAAAAAAAAL);
            UNSAFE.putLong(address3 + 2 * 8, 0xBBBBBBBBBBBBBBBBL);
            UNSAFE.putLong(address3 + 3 * 8, 0xCCCCCCCCCCCCCCCCL);

            long value1 = UNSAFE.getLong(address2 + 0 * 8);
            long value2 = UNSAFE.getLong(address2 + 1 * 8);
            long value3 = UNSAFE.getLong(address2 + 2 * 8);
            long value4 = UNSAFE.getLong(address2 + 3 * 8);

            if (value1 != 0x5555555555555555L ||
                value2 != 0x6666666666666666L ||
                value3 != 0x7777777777777777L ||
                value4 != 0x8888888888888888L) {
                throw new RuntimeException("Values not set");
            }

            na.clear(address2);

            value1 = UNSAFE.getLong(address2 + 0 * 8);
            value2 = UNSAFE.getLong(address2 + 1 * 8);
            value3 = UNSAFE.getLong(address2 + 2 * 8);
            value4 = UNSAFE.getLong(address2 + 3 * 8);

            if (value1 != 0L ||
                value2 != 0L ||
                value3 != 0L ||
                value4 != 0L) {
                throw new RuntimeException("Values not cleared");
            }

            long value5 = UNSAFE.getLong(address1 + 3 * 8);
            long value6 = UNSAFE.getLong(address3 + 0 * 8);

            if (value5 == 0L) {
                throw new RuntimeException("Clear underflow");
            }

            if (value6 == 0L) {
                throw new RuntimeException("Clear overflow");
            }

            // Slabs
        }
    }


    static void allocationSize() {
        try (NativeAllocator na = Configuration.mimimumAllocator()) {
            long address = na.allocate(13);

            if (na.allocationSize(address) != 16) {
                throw new RuntimeException("allocationSize not correct");
            }

            if (na.allocationSize(address + 15) != 16) {
                throw new RuntimeException("allocationSize with offset not correct");
            }
        }
    }

    static void allocationBase() {
        try (NativeAllocator na = Configuration.mimimumAllocator()) {
            long address = na.allocate(13);

            if (na.allocationBase(address) != address) {
                throw new RuntimeException("allocationBase not correct");
            }

            if (na.allocationBase(address + 15) != address) {
                throw new RuntimeException("allocationBase with offset not correct");
            }
        }
    }

    static void nextAllocation() {
        try (NativeAllocator na = Configuration.mimimumAllocator()) {
            long address1 = na.allocate(16);
            long address2 = na.allocate(16);

            long next = na.nextAllocation(0L);

            if (next != address1) {
                throw new RuntimeException("nextAllocation first not correct");
            }

            next = na.nextAllocation(next);

            if (next != address2) {
                throw new RuntimeException("nextAllocation second not correct");
            }

            next = na.nextAllocation(next);

            if (next != 0L) {
                throw new RuntimeException("nextAllocation last not correct");
            }
        }
    }

    static void threads() {
        int NTHREADS = 50;
        int NALLOCATIONS = 100;

        try (NativeAllocator na = Configuration.mimimumAllocatorShared()) {
            Thread[] threads = new Thread[NTHREADS];

            for (int i = 0; i < NTHREADS; i++) {
                threads[i] =  new Thread(() -> {
                    long[] allocations = new long[NALLOCATIONS];

                    for (int j = 0; j < NALLOCATIONS; j++) {
                        allocations[j] = na.allocate(8);

                        if (allocations[j] == 0L) {
                            throw new RuntimeException("Threads allocation fail");
                        }
                    }

                    for (int j = 0; j < NALLOCATIONS; j++) {
                        na.deallocate(allocations[j]);
                    }
                });
            }

            for (int i = 0; i < NTHREADS; i++) {
                threads[i].start();
            }

            for (int i = 0; i < NTHREADS; i++) {
                threads[i].join();
            }

            long[] counts = new long[64];
            long[] sizes = new long[64];
            na.stats(counts, sizes);

            if (counts[0] != 0) {
                throw new RuntimeException("Threads still left with allocations");
            }

        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
}
