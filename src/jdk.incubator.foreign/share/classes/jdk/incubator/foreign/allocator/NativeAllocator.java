/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.incubator.foreign.allocator;

import java.io.Closeable;
import java.util.Arrays;

import static jdk.incubator.foreign.allocator.Common.*;

final public class NativeAllocator extends Space implements Closeable {
    /**
     * true if sharing allocations
     */
    private final boolean isShared;

    /**
     * true if allocations are cleared on deallocation.
     */
    private final boolean isSecure;

    /**
     * Roster used to map size order to appropriate allocator
     */
    private final Roster roster;

    /**
     * Quantum allocators used to allocate blocks less than 64M
     */
    private final QuantumAllocator[] quantumAllocators;

    /**
     * Slab allocator used to allocate blocks larger than 64M
     */
    private final SlabAllocator slabAllocator;

    /**
     * Substitute allocator to handle cases when ZERO should always be returned.
     */
    final static class NullAllocator extends Allocator {
        /**
         * Constructor.
         *
         * @param base               lower bounds of a memory range.
         * @param size               size of memory range.
         * @param smallestSizeOrder  order of the smallest quantum handled.
         * @param largestSizeOrder   order of the largest quantum handled.
         */
        private NullAllocator(
                long base,
                long size,
                int smallestSizeOrder,
                int largestSizeOrder
        ) {
            super(base, size, smallestSizeOrder, largestSizeOrder);
        }

        /**
         * Create a new instance of NullAllocator.
         *
         * @param smallestSizeOrder  order of the smallest quantum handled.
         * @param largestSizeOrder   order of the largest quantum handled.
         *
         * @return instance of NullAllocator.
         */
        static NullAllocator create(
            int smallestSizeOrder,
            int largestSizeOrder
        ) {
            return new NullAllocator(ZERO, ZERO, smallestSizeOrder, largestSizeOrder);
        }


        @Override
        long allocate(int order) {
            return ZERO;
        }
    }

    /**
     * Constructor.
     *
     * @param base              lower bounds of a memory range.
     * @param size              size of memory range.
     * @param isShared          true if sharing allocations.
     * @param isSecure          true if allocations are cleared on deallocation.
     * @param roster            roster used to map size order to appropriate allocator.
     * @param quantumAllocators quantum allocators used to allocate blocks less than 64M.
     * @param slabAllocator     slab allocator used to allocate blocks larger than 64M.
     */
    private NativeAllocator(
            long base,
            long size,
            boolean isShared,
            boolean isSecure,
            Roster roster,
            QuantumAllocator[] quantumAllocators,
            SlabAllocator slabAllocator
    ) {
        super(base, size);
        this.isShared = isShared;
        this.isSecure = isSecure;
        this.roster = roster;
        this.quantumAllocators = quantumAllocators;
        this.slabAllocator = slabAllocator;

        /*
         * Fill out roster.
         */

        /*
         * Less than SMALLEST_SIZE goes to small allocator.
         */
        roster.setAllocators(
            quantumAllocators[0].isEmpty() ?
                NullAllocator.create(0, SMALLEST_SIZE_ORDER + 1) :
                quantumAllocators[0],
            0,
            SMALLEST_SIZE_ORDER + 1);

        /*
         * Fill in small, medium and large.
         */
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            Allocator allocator = quantumAllocators[i];

            if (!allocator.isEmpty()) {
                roster.setAllocators(allocator,
                        allocator.smallestSizeOrder(),
                        allocator.largestSizeOrder() + 1);
            } else {
                roster.setAllocators(NullAllocator.create(
                            allocator.smallestSizeOrder(),
                            allocator.largestSizeOrder() + 1
                        ),
                        allocator.smallestSizeOrder(),
                        allocator.largestSizeOrder() + 1);
            }
        }

        /*
         * Default to slab allocator.
         */
        roster.setAllocators(slabAllocator, LARGEST_SIZE_ORDER + 1, MAX_ADDRESS_ORDER);
        roster.setAllocators(NullAllocator.create(MAX_ADDRESS_ORDER + 1, MAX_ROSTER - 1 ),
                                                  MAX_ADDRESS_ORDER + 1, MAX_ROSTER - 1);
    }

    /**
     * Creates an instance of NativeAllocator.
     *
     * @param address              zero or fixed based address for allocation
     * @param isShared             true if sharing allocations.
     * @param isSecure             true if allocations are cleared on deallocation.
     * @param smallPartitionCount  partition count for small quantum allocator.
     * @param mediumPartitionCount partition count for medium quantum allocator.
     * @param largePartitionCount  partition count for large quantum allocator.
     * @param maxSlabCount         maximum number of slabs.
     *
     * @return new instance of NativeAllocator or null if memory is not available.
     */
    public static NativeAllocator create(
            long address,
            boolean isShared,
            boolean isSecure,
            int smallPartitionCount,
            int mediumPartitionCount,
            int largePartitionCount,
            int maxSlabCount
    ) {
        assert (address == ZERO || isValidAddress(address)) :
                "address is invalid";
        assert (address & mask(LARGEST_SIZE)) == ZERO :
                "address must be a multiple of largest quantum size";
        assert 0 <= maxSlabCount :
                "slabs out of range";


        int[] partitionCounts = new int[]{
                smallPartitionCount,
                mediumPartitionCount,
                largePartitionCount
        };

        /*
         * Reservation needed and offsets for each quantum region.
         */
        long reservation = ZERO;
        long[] offsets = new long[MAX_QUANTUM_ALLOCATORS];

        /*
         * Size quantum allocator regions.
         */
        for (int i = MAX_QUANTUM_ALLOCATORS; 0 < i; ) {
            i--;

            /*
             *  Smallest size order for quantum allocator.
             */
            int smallestSizeOrder = SMALLEST_SIZE_ORDER + i * MAX_QUANTUM_ALLOCATOR_ORDERS;

            /*
             * Partition size and size order for current quantum allocator.
             */
            long partitionSize = orderMul(MAX_PARTITION_QUANTUM, smallestSizeOrder);
            int partitionSizeOrder = sizeToOrder(partitionSize);

            /*
             * Save size and offset of current quantum allocator region.
             */
            long size = orderMul(partitionCounts[i], partitionSizeOrder);
            offsets[i] = reservation;
            reservation += size;
        }

        /*
         * Allocate regions.
         */
        long base = address == ZERO ? VMMemory.reserveAligned(reservation, LARGEST_SIZE)
                                    : VMMemory.reserve(reservation, address);

        /*
         * If memory not available.
         */
        if (base == ZERO) {
            return null;
        }

        /*
         *  Allocate roster.
         */
        Roster roster = new Roster();

        /*
         *  Track quantum allocators
         */
        QuantumAllocator[] quantumAllocators = new QuantumAllocator[MAX_QUANTUM_ALLOCATORS];

        /*
         *  Allocate quantum allocators.
         */
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            /*
             *  Smallest and largest size order for quantum allocator.
             */
            int smallestSizeOrder = SMALLEST_SIZE_ORDER + i * MAX_QUANTUM_ALLOCATOR_ORDERS;
            int largestSizeOrder = smallestSizeOrder + MAX_QUANTUM_ALLOCATOR_ORDERS - 1;

            /*
             * Partition size and size order for current quantum allocator.
             */
            long partitionSize = orderMul(MAX_PARTITION_QUANTUM, smallestSizeOrder);
            int partitionSizeOrder = sizeToOrder(partitionSize);

            /*
             * Quantum allocator space.
             */
            quantumAllocators[i] = QuantumAllocator.create(
                isShared,
                isSecure,
                roster,
                smallestSizeOrder,
                largestSizeOrder,
                partitionSizeOrder,
                partitionCounts[i],
                base + offsets[i]
            );
        }

        /*
         *  Create slab allocator.
         */
        SlabAllocator slabAllocator = SlabAllocator.create(
            isShared,
            isSecure,
            maxSlabCount
        );

        /*
         *  Create NativeAllocator.
         */
        return new NativeAllocator(
            base,
            reservation,
            isShared,
            isSecure,
            roster,
            quantumAllocators,
            slabAllocator
        );
    }

    /**
     * Close out memory used by NativeAllocator.
     */
    public void close() {
        slabAllocator.close();
        VMMemory.release(base(), size());
    }

    /**
     * Returns an allocator suited to allocate blocks of order of bytes.
     *
     * @param order size order of block to allocate.
     */
    private Allocator findAllocatorBySize(int order) {
        assert 0 <= order && order <= BITS_PER_WORD :
                "order out of range: " + order;

        return roster.getAllocator(order);
    }

    /**
     * allocate returns the address of a memory block at least "size" bytes
     * long. The block may be larger due to rounding up. allocate may return
     * ZERO if the required memory is not available.
     *
     * @param size number of bytes to allocate.
     *
     * @return address of a memory block or null if not available.
     */
    public long allocate(long size) {
        int order = sizeToOrder(size);
        Allocator allocator = findAllocatorBySize(order);

        return allocator.allocate(order);
    }

    /**
     * deallocate makes the memory block pointed to by "address" available
     * for further allocation. If the "address" is ZERO or outside the
     * range of the allocator the deallocate does nothing and returns false.
     *
     * @param address address of memory block to deallocate.
     */
    public void deallocate(long address) {
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            QuantumAllocator allocator = quantumAllocators[i];

            if (allocator.in(address)) {
                allocator.deallocate(address);

                return;
            }
        }

        slabAllocator.deallocate(address);
    }

    /**
     * Tests the new size against the existing size to see if a new block is
     * appropriate. If so the new block is allocated, the contents of the old
     * block copied over, the old block deallocated and the new block address
     * returned. If not the old block address is returned. If the old block was
     * ZERO then a new empty block is returned. May return zero if unable to
     * allocate the new block (old block not deallocated.)
     *
     * @param oldAddress address of memory block to reallocate.
     * @param newSize    new memory block size in bytes.
     */
    public long reallocate(long oldAddress, long newSize) {
        if (oldAddress == ZERO) {
            return allocate(newSize);
        }

        long oldSize = allocationSize(oldAddress);

        if (oldSize < roundUpPowerOf2(newSize) ||
                sizeToOrder(newSize) < sizeToOrder(oldSize)) {
            long newAddress = allocate(newSize);

            if (newAddress != ZERO && oldSize != ZERO) {
                VMMemory.copy(oldAddress, newAddress, oldSize);
                deallocate(oldAddress);
            }

            return newAddress;
        }

        return oldAddress;
    }

    /**
     * Zeroes out the content of a memory block.
     *
     * @param address  address of memory block to clear.
     */
    public void clear(long address) {
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            QuantumAllocator allocator = quantumAllocators[i];

            if (allocator.in(address)) {
                allocator.clear(address);

                return;
            }
        }

        slabAllocator.clear(address);
    }

    /**
     * Returns number of bytes allocated at the "address".
     *
     * @param address  arbitrary address in an allocated memory block.
     *
     * @return number of bytes allocated at the "address".
     */
    public long allocationSize(long address) {
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            QuantumAllocator allocator = quantumAllocators[i];

            if (allocator.in(address)) {
                return allocator.allocationSize(address);
            }
        }

        return slabAllocator.allocationSize(address);
    }

    /**
     * Returns the base address of an allocated block containing the "address".
     *
     * @param address  arbitrary address in an allocated memory block.
     */
    public long allocationBase(long address) {
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            QuantumAllocator allocator = quantumAllocators[i];

            if (allocator.in(address)) {
                return allocator.allocationBase(address);
            }
        }

        return slabAllocator.allocationBase(address);
    }

    /**
     * Can be used to "walk" through all the allocations managed by the native
     * allocator. The first call should have an "address" of ZERO with
     * successive calls using the result of the previous call. The result
     * itself can not be used for memory access since the result may have been
     * deallocated after fetching (potential seg fault). The result can however
     * be used to make calls to allocationSize or allocationSideData. A result
     * of zero indicates no further blocks.
     *
     *
     * @param address  ZERO or result of last call to nextAllocation
     *
     * @return address of next allocation.
     */
    public long nextAllocation(long address) {
        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            QuantumAllocator allocator = quantumAllocators[i];

            if (address == ZERO || allocator.in(address)) {
                long next = allocator.nextAllocation(address);

                if (next != ZERO) {
                    return next;
                }

                address = ZERO;
            }
        }

        return slabAllocator.nextAllocation(address);
    }

    /**
     * Fills in "counts" and "sizes" buffers with information known to
     * this allocator. Slots 1 to MAX_ALLOCATION_ORDER contain counts and
     * sizes of allocations of that size order.
     *
     * Slot 0 - Sum of all other slots.
     * Slot 1 - Unused.
     * Slot 2 - Unused.
     * Slot 3-48 - Totals for blocks sized 2^slot.
     * Slot 49 and above - Unused.
     *
     * @param counts  counts buffer.
     * @param sizes   sizes buffer.
     */
    public void stats(long[] counts, long[] sizes) {
        assert counts != null : "counts should not be null";
        assert sizes != null : "sizes should not be null";
        Arrays.fill(counts, 0L);
        Arrays.fill(sizes, 0L);

        for (int i = 0; i < MAX_QUANTUM_ALLOCATORS; i++) {
            quantumAllocators[i].stats(counts, sizes);
        }

        slabAllocator.stats(counts, sizes);

        long count = 0;
        long size = 0;

        for (int i = 1; i < counts.length; i++) {
            count += counts[i];
            size += sizes[i];
        }

        counts[0] = count;
        sizes[0] = size;
    }
}
