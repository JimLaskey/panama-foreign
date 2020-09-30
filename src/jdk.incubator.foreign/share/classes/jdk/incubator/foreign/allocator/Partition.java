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

import jdk.incubator.foreign.allocator.Registry.RegistryIsSetIterator;
import static jdk.incubator.foreign.allocator.Common.*;

/**
 * Specialized allocator for a specific quantum in a single partition.
 */
class Partition extends Allocator {
    /**
     * true if partition is shared.
     */
    private final boolean isShared;

    /**
     * true if allocations are cleared on deallocation.
     */
    private final boolean isSecure;

    /**
     * Managing QuantumAllocator.
     */
    private final QuantumAllocator quantumAllocator;

    /**
     * Size order of the quantum.
     */
    private final int quantumSizeOrder;

    /**
     * Size of the quantum.
     */
    private final long quantumSize;

    /**
     * Registry used to track allocations in this partition.
     */
    private final Registry registry;

    /**
     * Constructor for specific partition and quantum size.
     *
     * @param isShared          true if allocations are shared.
     * @param isSecure          true if allocations are cleared on deallocation.
     * @param quantumAllocator  managing QuantumAllocator.
     * @param base              base address of the partition.
     * @param partitionSize     size of the partition in bytes.
     * @param quantumSizeOrder  size order of the quantum.
     */
    Partition(
            boolean isShared,
            boolean isSecure,
            QuantumAllocator quantumAllocator,
            long base,
            long partitionSize,
            int quantumSizeOrder
    ) {
        super(base, partitionSize, quantumSizeOrder, quantumSizeOrder);
        assert quantumAllocator != null : "quantumAllocator is null";
        assert isPowerOfTwo(partitionSize) :
                "invalid partition size: " + partitionSize;
        assert base != ZERO : "base is null";
        assert 0 <= quantumSizeOrder && quantumSizeOrder <= MAX_ALLOCATION_ORDER :
                "invalid quantum size order: " + quantumSizeOrder;
        this.isShared = isShared;
        this.isSecure = isSecure;
        this.quantumAllocator = quantumAllocator;
        this.quantumSizeOrder = quantumSizeOrder;
        this.quantumSize = orderToSize(quantumSizeOrder);
        this.registry = Registry.create(isShared,
                orderDiv(partitionSize, quantumSizeOrder));
    }

    /**
     * Returns the index of the quantum containing the address.
     *
     * @param address address in the quantum allocation.
     *
     * @return index of the quantum containing the address.
     */
    int quantumIndex(long address) {
        return orderDiv(address - base(), quantumSizeOrder);
    }

    /**
     * Speculatively returns true if this partition is empty.
     */
    boolean isEmpty() {
        return registry.isEmpty();
    }

    /**
     * Allocates a memory block at least "size" bytes long. The block may be
     * larger due to rounding up to power of two.
     *
     * @param order  size order to allocate.
     *
     * @return address of memory block or zero if not allocated.
     */
    long allocate(int order) {
        assert quantumSizeOrder == order : "order out of range: " + order;
        int index = registry.findFree();

        if (isNotFound(index)) {
            return quantumAllocator.allocateNonRecursive(this, quantumSizeOrder);
        }

        return base() + orderMul(index, quantumSizeOrder);
    }

    /**
     * Frees the quantum that contains the address.
     *
     * @param address  address of memory block to deallocate.
     */
    void deallocate(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";

        int index = quantumIndex(address);
        assert registry.isSet(index) : "double deallocate";

        if (isSecure) {
            VMMemory.clear(allocationBase(address), quantumSize);
        }

        registry.free(index);
    }

    /**
     * Zeroes out the content of a memory block.
     *
     * @param address  address of memory block to clear.
     */
    void clear(long address) {
        VMMemory.clear(allocationBase(address), quantumSize);
    }

    /**
     * Number of bytes allocated at the address.
     *
     * @param address  arbitrary address in an allocated memory block.
     *
     * @return bytes allocated at address.
     */
    long allocationSize(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";
        return quantumSize;
    }

    /**
     * Base address of an allocated block containing the address.
     *
     * @param address  arbitrary address in an allocated memory block.
     */
    long allocationBase(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";
        return address & ~mask(quantumSize);
    }

    /**
     * Walk through all the allocations managed by the native allocator. The
     * first call should have an "address" of nullptr with successive calls
     * using the result of the previous call. The result itself can not be used
     * for memory access since the result may have been deallocated after
     * fetching (potential seg fault). The result can however be used to make
     *
     * @param address  Zero or arbitrary address in an allocated memory block.
     *
     * @return next address.
     */
    long nextAllocation(long address) {
        assert in(address) || address == ZERO : "address should be in this partition";
        RegistryIsSetIterator iterator =
                registry.isSetIterator(address != ZERO ? quantumIndex(address) + 1
                        : 0);
        int index = iterator.nextSet();

        return isFound(index) ? base() + orderMul(index, quantumSizeOrder) : ZERO;
    }

    //
    // Fills in "counts" and "sizes" arrays with information known to this
    // allocator. Specifically stats updates the quantum order slots with the
    // sample count of in-use bits in the registry.
    //
    // counts - counts array.
    // sizes - sizes array.
    //
    void stats(long[] counts, long[] sizes) {
        assert counts != null : "counts should not be null";
        assert sizes != null : "sizes should not be null";
        int count = registry.count();
        counts[quantumSizeOrder] += count;
        sizes[quantumSizeOrder] += orderMul(count, quantumSizeOrder);
    }
}

