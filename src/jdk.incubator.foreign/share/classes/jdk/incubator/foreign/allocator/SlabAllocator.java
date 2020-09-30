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

/**
 * Allocator for allocating large one-of blocks that are unlikely to be recycled.
 */
class SlabAllocator extends Allocator {
    /**
     * true if allocations are shared across threads.
     */
    private final boolean isShared;

    /**
     * true if allocations are cleared on deallocation.
     */
    private final boolean isSecure;

    /**
     * Array of allocated slabs.
     */
    private final Slab[] slabs;

    /**
     * Registry for allocated slabs.
     */
    private final Registry registry;

    /**
     * Represents allocations that are very large and unlikely to be recycled.
     */
    static class Slab extends Space {
        /**
         * Constructor used when creating a specific slab.
         *
         * @param base lower bounds of a memory range.
         * @param size size of memory range.
         */
        Slab(long base, long size) {
            super(base, size);
        }
    }

    /**
     * Find slab containing the address.
     *
     * @param address  address in a slab allocation.
     *
     * @return index  index in slabs or NOT_FOUND if not found.
     */
    int find(long address) {
        for (int i = 0; i < slabs.length; i++) {
            if (slabs[i].in(address) && registry.isSet(i)) {
                return i;
            }
        }

        return Common.NOT_FOUND;
    }

    /**
     * Unregister an allocation from the slab allocator.
     *
     * @param index  index in slabs
     */
    void erase(int index) {
        assert 0 <= index && index < slabs.length :
                "slab index out of range: " + index;
        registry.free(index);
    }

    /**
     * Attempt to recycle a previously freed allocation. If not then allocate
     * new space.
     *
     * @param size - number of bytes requires rounded to page size.
     */
    long reserve(long size) {
        /*
         * Look for a free slab.
         */
        int index = registry.findFree();

        /*
         * If no slabs available.
         */
        if (Common.isNotFound(index)) {
            return Common.ZERO;
        }

        /*
         * Extract slab data.
         */
        Slab slab = slabs[index];

        if (slab != null) {
            long slabBase = slab.base();
            long slabSize = slab.size();

            /*
             * If old slab is large enough.
             */
            if (slabSize > size) {
                /*
                 * Discard extra.
                 */
                long postfixSize = slabSize - size;
                VMMemory.release(slabBase + size, postfixSize);
            }

            /*
             * If old slab is large enough.
             */
            if (slabSize >= size) {
                /*
                 * Clear it and return base address.
                 */
                if (isSecure) {
                    VMMemory.commit(slabBase, size);
                }

                slabs[index] = new Slab(slabBase, size);

                return slabBase;
            }

            /*
             * If old slab is not empty.
             */
            if (slabSize != Common.ZERO) {
                /*
                 * Discard old slab.
                 */
                VMMemory.release(slabBase, slabSize);
            }
        }

        /*
         * Allocate new slab.
         */
        long base = VMMemory.reserveAligned(size, size);

        /*
         * If not allocated.
         */
        if (base == Common.ZERO) {
            /*
             * Clear registry entry.
             */
            registry.free(index);

            return Common.ZERO;
        }

        /*
         * Commit to using the new slab and return result.
         */
        VMMemory.commit(base, size);
        slabs[index] = new Slab(base, size);

        return base;
    }

    /**
     * Constructor.
     *
     * @param isShared  true if allocator shared across threads.
     * @param isSecure  true if allocations are cleared on deallocation.
     * @param maxCount  maximum number of slabs.
     */
    private SlabAllocator(
        boolean isShared,
        boolean isSecure,
        int maxCount
    )
    {
        super(Common.ZERO, Long.MAX_VALUE, Common.LARGEST_SIZE_ORDER, Common.MAX_ALLOCATION_ORDER);
        this.isShared = isShared;
        this.isSecure = isSecure;
        this.slabs = new Slab[maxCount];
        this.registry = Registry.create(isShared, maxCount);
    }

    /**
     * Create a new instance of SlabAllocator.
     *
     * @param isShared  true if allocator shared across threads.
     * @param isSecure  true if allocations are cleared on deallocation.
     * @param maxCount  maximum number of slabs.
     */
    static SlabAllocator create(
        boolean isShared,
        boolean isSecure,
        int maxCount

    ) {
        return new SlabAllocator(
            isShared,
            isSecure,
            maxCount
        );
    }

    /**
     * Close out memory used by allocator.
     */
    void close() {
        for (Slab slab : slabs) {
            if (slab != null && slab.size() != Common.ZERO) {
                VMMemory.release(slab.base(), slab.size());
            }
        }
    }

    /**
     * Returns the address of a memory block at least size bytes. The block may
     * be larger due to rounding up to one megabyte.
     *
     * @param order  size order to allocate.
     *
     * @return allocation address of memory block or ZERO if the required memory
     *         is not available.
     */
    long allocate(int order) {
        assert 0 <= order && order <= Common.MAX_ALLOCATION_ORDER :
                "order out of range";

        return reserve(Common.orderToSize(order));
    }

    /**
     * Makes the memory block pointed to by address available for further
     * allocation. If the address is ZERO or outside the range of the
     * allocator the deallocate does nothing.
     *
     * @param address  address of memory block to deallocate.
     */
    void deallocate(long address) {
        int index = find(address);

        if (Common.isNotFound(index)) {
            return;
        }

        /*
         * Clear from registry but allow for recycling.
         */
        erase(index);
    }

    /**
     * clear zeroes out the content of a memory block.
     *
     * @param address  address of memory block to clear.
     */
    void clear(long address) {
        int index = find(address);

        if (Common.isNotFound(index)) {
            return;
        }

        Slab slab = slabs[index];
        VMMemory.clear(slab.base(), slab.size());
    }

    /**
     * Returns number of bytes allocated at the address.
     *
     * @param address  arbitrary address in an allocated memory block.
     *
     * @return number of bytes allocated for the memory block.
     */
    long allocationSize(long address) {
        int index = find(address);

        if (Common.isNotFound(index)) {
            return Common.ZERO;
        }

        Slab slab = slabs[index];

        return slab.size();
    }

    /**
     * Returns the base address of an allocated block containing the address.
     *
     * @param address  arbitrary address in an allocated memory block.
     *
     * @return base address of allocated block.
     */
    long allocationBase(long address) {
        int index = find(address);

        if (Common.isNotFound(index)) {
            return Common.ZERO;
        }

        Slab slab = slabs[index];

        return slab.base();
    }

    /**
     * Walk through all the allocations managed by the native allocator. The
     * first call should have an "address" of nullptr with successive calls
     * using the result of the previous call. The result itself can not be used
     * for memory access since the result may have been deallocated after
     * fetching (potential seg fault). The result can however be used to make
     * calls to allocationSize.
     *
     * @param address  arbitrary address in an allocated memory block.
     *
     * @return next allocation or ZERO.
     */
    long nextAllocation(long address) {
        int index = address != Common.ZERO ? find(address) : Common.NOT_FOUND;

        if (Common.isNotFound(index)) {
            return Common.ZERO;
        }

        Registry.RegistryIsSetIterator iterator = registry.isSetIterator(index + 1);

        index = iterator.nextSet();

        return Common.isFound(index) ? slabs[index].base() : Common.ZERO;
    }

    /**
     * Fills in counts and sizes arrays with information known to this
     * allocator. Slots 3 to MAX_ALLOCATION_ORDER contain counts and sizes of
     * allocations of that size order.
     *
     * Slot 0 - Sum of all other slots.
     * Slot 1 - Unused
     * Slot 2 - Unused.
     * Slot 3-48 - Totals for blocks sized 2^slot.
     * Slot 49 and above - Unused.
     *
     * @param counts  counts array.
     * @param sizes   sizes array.
     */
    void stats(long[] counts, long[] sizes) {
        assert counts != null : "counts should not be null";
        assert sizes != null : "sizes should not be null";

        for (int i = 0; i < slabs.length; i++) {
            if (registry.isSet(i)) {
                long size = slabs[i].size();
                int order = Common.sizeToOrder(size);
                counts[order]++;
                sizes[order] += size;
            }
        }
    }
}
