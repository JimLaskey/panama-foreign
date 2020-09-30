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
 * Manages a span of memory subdivided into partitions. There can be
 * multiple QuantumAllocators managed by a Director. The reason for doing
 * this is to keep the ratio of partition size and quantum size to the low
 * end. This in turn keeps quantum registries small and fast.
 */
public class QuantumAllocator extends Allocator {
    /**
     * true if quantum allocator is shared.
     */
    private final boolean isShared;

    /**
     * true if allocations are cleared on deallocation.
     */
    private final boolean isSecure;

    private final Roster roster;

    private final int partitionSizeOrder;

    private final int partitionCount;

    private final long partitionSize;

    private final int smallestSizeOrder;

    private final int largestSizeOrder;

    private final Partition[] partitions;

    private final Registry partitionRegistry;

    private final Registry[] orderRegistry;

    /**
     * Constructor.
     *
     * @param isShared            true if allocations are shared.
     * @param isSecure            true if allocations are cleared on deallocation.
     * @param roster              allocation roster from managing Director.
     * @param smallestSizeOrder   size order of the smallest quantum handled by this
     *                            allocator.
     * @param largestSizeOrder    size order of the largest quantum handled by this
     *                            allocator.
     * @param partitionSizeOrder  order of the partition size handled by this
     *                            allocator.
     * @param partitionCount      number of partitions managed by this allocator.
     * @param base                Lower bounds of managed space.
     */
    private QuantumAllocator(
            boolean isShared,
            boolean isSecure,
            Roster roster,
            int smallestSizeOrder,
            int largestSizeOrder,
            int partitionSizeOrder,
            int partitionCount,
            long base
    )
    {
        super(base, orderToSize(partitionSizeOrder) * partitionCount,
                smallestSizeOrder, largestSizeOrder);
        this.isShared = isShared;
        this.isSecure = isSecure;
        this.roster = roster;
        this.partitionSizeOrder = partitionSizeOrder;
        this.partitionCount = partitionCount;
        this.partitionSize = orderToSize(partitionSizeOrder);
        this.smallestSizeOrder = smallestSizeOrder;
        this.largestSizeOrder = largestSizeOrder;
        this.partitions = new Partition[partitionCount];
        this.partitionRegistry = Registry.create(isShared, partitionCount);
        this.orderRegistry = new Registry[MAX_QUANTUM_ALLOCATOR_ORDERS];

        /*
         * Initialize order registries.
         */
        for (int i = 0; i < MAX_QUANTUM_ALLOCATOR_ORDERS; i++) {
            this.orderRegistry[i] = Registry.create(isShared, partitionCount);
        }
    }

    static QuantumAllocator create(
            boolean isShared,
            boolean isSecure,
            Roster roster,
            int smallestSizeOrder,
            int largestSizeOrder,
            int partitionSizeOrder,
            int partitionCount,
            long base
    ) {
        return new QuantumAllocator(
            isShared,
            isSecure,
            roster,
            smallestSizeOrder,
            largestSizeOrder,
            partitionSizeOrder,
            partitionCount,
            base
        );
    }

    /**
     * Returns the partition allocator at partitionIndex.
     *
     * @param partitionIndex  corresponding to the  partition's position in
     *                        memory and its matching allocator.
     *
     * @return partition allocator at partitionIndex.
     */
    Partition getPartition(int partitionIndex) {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;

        return partitions[partitionIndex];
    }

    /**
     * Returns the partition index from an arbitrary address in the partition.
     *
     * @param address  arbitrary address in this quantum allocator's space.
     *
     * @return the partition index.
     */
    int getPartitionIndex(long  address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address not in allocator";

        return orderDiv(address - base(), partitionSizeOrder);
    }

    /**
     * Initializes the partition at partitionIndex for quantum of size
     * order and then returns partition's allocator.
     *
     * @param partitionIndex  corresponding to the  partition's position in
     *                        memory and its matching allocator.
     * @param order           size order of quantum managed by the partition.
     *
     * @return newly created partition allocator.
     */
    Partition newPartition(
            int partitionIndex,
            int order
    )
    {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;
        assert smallestSizeOrder <= order && order <= largestSizeOrder :
                "order out of range: " + order;
        long address = base() + orderMul(partitionIndex, partitionSizeOrder);
        Partition partition = new Partition(isShared, isSecure, this, address,
                partitionSize, order);
        partitions[partitionIndex] = partition;

        return partition;
    }

    /**
     * getOrderIndex returns the local order index (orderRegistry index.)
     *
     * @param order  size order of quantum managed by the partition.
     *
     * @return the local order index.
     */
    int getOrderIndex(int order) {
        assert smallestSizeOrder <= order && order <= largestSizeOrder :
                "order is out of range: " + order;
        return order - smallestSizeOrder;
    }

    /**
     * Adds a partition to an order registry and then makes its allocator the
     * primary allocator for allocations of that order.
     *
     * @param orderIndex     size order of the partition relative to the
     *                       smallestSizeOrder.
     * @param partition      partition allocator.
     * @param partitionIndex partition's position in quantum allocator's memory.
     */
    void addToOrder(
            int orderIndex,
            Partition partition,
            int partitionIndex
    )
    {
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range : " + orderIndex;
        onlinePartition(partitionIndex, orderIndex);
        roster.setAllocator(partition, smallestSizeOrder + orderIndex);
    }

    /**
     * Creates a new partition allocator and puts it online.
     *
     * @param orderIndex size order of the partition relative to the
     *                   smallestSizeOrder.
     *
     * @return newly created partition allocator or null if no partitions are
     * available.
     */
    Partition newOrderPartition(int orderIndex) {
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range : " + orderIndex;
        int partitionIndex = allocatePartition();

        if (isNotFound(partitionIndex)) {
            return null;
        }

        int order = orderIndex + smallestSizeOrder();
        Partition partition = newPartition(partitionIndex, order);
        addToOrder(orderIndex, partition, partitionIndex);

        return partition;
    }

    /**
     * Takes a partition out of rotation.
     *
     * @param partitionIndex partition's position in quantum allocator's memory.
     * @param orderIndex     size order of the partition relative to the
     *                       smallestSizeOrder.
     *
     * @return true if successfully made offline.
     */
    boolean offlinePartition(int partitionIndex, int orderIndex) {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range : " + orderIndex;
        Registry registry = orderRegistry[orderIndex];
        boolean cleared = registry.clear(partitionIndex);
        roster.setAllocator(this, smallestSizeOrder + orderIndex);

        return cleared;
    }

    /**
     * Puts a partition into rotation.
     *
     * @param partitionIndex partition's position in quantum allocator's memory.
     * @param orderIndex     size order of the partition relative to the
     *                       smallestSizeOrder.
     *
     * @return true if successfully made online.
     */
    boolean onlinePartition(int partitionIndex, int orderIndex) {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range : " + orderIndex;
        Registry registry = orderRegistry[orderIndex];

        return registry.set(partitionIndex);
    }

    /**
     * Scan through partitions looking for an empty partition then takes it
     * offline and reestablishes with a new size order.
     *
     * @param orderIndex  size order of the partition relative to the
     *                    smallestSizeOrder.
     *
     * @return available partition or null if not found.
     */
    Partition freeUpPartition(int orderIndex) {
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range : " + orderIndex;
        for (int partitionIndex = partitions.length - 1;
             0 <= partitionIndex;
             partitionIndex--
        )
        {
            Partition partition = getPartition(partitionIndex);

            /*
             * Is the partition provisionally empty.
             */
            if (partition == null || !partition.isEmpty()) {
                continue;
            }

            /*
             * Take partition offline and then test for absolute emptiness.
             */
            if (!offlinePartition(partitionIndex, orderIndex) ||
                    !partition.isEmpty()) {
                /*
                 * If can't take offline or not empty put online again.
                 * No-op if already online.
                 */
                onlinePartition(partitionIndex, orderIndex);

                continue;
            }

            /*
             * Put partition online with new size.
             */
            int order = orderIndex + smallestSizeOrder();
            partition = newPartition(partitionIndex, order);
            addToOrder(orderIndex, partition, partitionIndex);

            return partition;
        }

        return null;
    }

    /**
     * partitionBase returns the base address of the partitionIndex partition.
     *
     * @param partitionIndex partition's position in quantum allocator's memory.
     *
     * @return base address of partition.
     */
    long partitionBase(int partitionIndex) {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;

        return base() + orderMul(partitionIndex, partitionSizeOrder);
    }

    /**
     * Find a free partition and commit its memory.
     *
     * @return the partition index or NOT_FOUND.
     */
    int allocatePartition() {
        int partitionIndex = partitionRegistry.findFree();

        if (isFound(partitionIndex)) {
            VMMemory.commit(partitionBase(partitionIndex), partitionSize);
        }

        return partitionIndex;
    }

    /**
     * Frees the partition in the partition registry.
     *
     * @param partitionIndex partition's position in quantum allocator's memory.
     */
    void freePartition(int partitionIndex) {
        assert 0 <= partitionIndex && partitionIndex < partitions.length :
                "partition out of range: " + partitionIndex;
        partitionRegistry.clear(partitionIndex);
    }

    /**
     * Returns the partition allocator from an arbitrary address in the partition.
     *
     * @param address  arbitrary address in this quantum allocator's space.
     *
     * @return partition allocator managing the address.
     */
    Partition partitionFromAddress(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address not in range for allocator";
        int partitionIndex = getPartitionIndex(address);

        return getPartition(partitionIndex);
    }

    /**
     * Attempts to create a new partition allocator. If it can not then it tries
     * to free up an existing partition allocator.
     *
     * @param orderIndex  size order of the partition relative to the
     *                    smallestSizeOrder.
     *
     * @return partition allocator if successful otherwise returns nullptr.
     */
    Partition getFreePartition(int orderIndex) {
        assert 0 <= orderIndex && orderIndex < MAX_QUANTUM_ALLOCATOR_ORDERS :
                "order index out of range";
        Partition partition = newOrderPartition(orderIndex);

        return partition != null ? partition : freeUpPartition(orderIndex);
    }



    /**
     * Used to iterate through partitions
     */
    public class PartitionIterator {
        /**
         * Size order relative to smallestSizeOrder.
         */
        private final int orderIndex;

        /**
         * Underlying registry iterator.
         */
        private final RegistryIsSetIterator registryIterator;

        /**
         * true if should allocate new partition allocator when exhausts registry.
         */
        private boolean allocateNew;

        /**
         * true if should continuously allocate new partition allocator if
         * exhausts registry.
         */
        private final boolean continuous;

        /**
         * Constructor.
         *
         * @param quantumAllocator  managing quantum allocator.
         * @param order             size order of allocation.
         * @param allocateNew       true if should allocate new partition allocator
         *                          when exhausts registry.
         * @param continuous        true if should continuously allocate new partition
         *                          allocator if exhausts registry.
         */
        PartitionIterator(
                QuantumAllocator quantumAllocator,
                int order,
                boolean allocateNew,
                boolean continuous
        )
        {
            assert quantumAllocator != null : "quantumAllocator should not be null";
            this.orderIndex = quantumAllocator.getOrderIndex(order);
            this.registryIterator = orderRegistry[orderIndex].isSetIterator();
            this.allocateNew = allocateNew;
            this.continuous = continuous;
        }

        /**
         * Returns next online partition allocator or nullptr if none found.
         */
        Partition next() {
            int partitionIndex = registryIterator.nextSet();

            if (isFound(partitionIndex)) {
                return getPartition(partitionIndex);
            }

            if (allocateNew) {
                if (!continuous) {
                    allocateNew = false;
                }

                return getFreePartition(orderIndex);
            }

            return null;
        }
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
        assert smallestSizeOrder <= order && order <= largestSizeOrder :
                "order out of range";

        PartitionIterator iterator = new PartitionIterator(this, order, true, true);
        for (Partition partition = iterator.next();
             partition != null;
             partition = iterator.next()) {
            long address = partition.allocate(order);

            if (address != ZERO) {
                return address;
            }
        }

        return ZERO;
    }

    /**
     * Allocates a memory block at least "size" bytes long. The block may be
     * larger due to rounding up to power of two.
     *
     * @param fullPartition  partition to ignore.
     * @param order          size order to allocate.
     *
     * @return address of memory block or zero if not allocated.
     */
    long allocateNonRecursive(Partition fullPartition, int order) {
        assert smallestSizeOrder <= order && order <= largestSizeOrder :
                "order out of range";

        int partitionIndex = getPartitionIndex(fullPartition.base());
        int orderIndex = order - smallestSizeOrder;

        try {
            offlinePartition(partitionIndex, orderIndex);

            PartitionIterator iterator = new PartitionIterator(this, order, true, true);
            for (Partition partition = iterator.next();
                 partition != null;
                 partition = iterator.next()) {
                long address = partition.allocate(order);

                if (address != ZERO) {
                    return address;
                }
            }
        } finally {
            onlinePartition(partitionIndex, orderIndex);
        }

        return ZERO;
    }

    /**
     * Makes the memory block pointed to by address available for further
     * allocation. If the address is ZERO or outside the range of the
     * allocator the deallocate does nothing.
     *
     * @param address  address of memory block to deallocate.
     */
    void deallocate(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";

        Partition partition = partitionFromAddress(address);
        partition.deallocate(address);
    }

    /**
     * Zeroes out the content of a memory block.
     *
     * @param address  arbitrary address in memory block to clear.
     */
    void clear(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";
        Partition partition = partitionFromAddress(address);

        partition.clear(address);
    }

    /**
     * Returns number of bytes allocated at the "address".
     *
     * @param address  arbitrary address in memory block.
     *
     * @return number of bytes allocated.
     */
    long allocationSize(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";
        Partition partition = partitionFromAddress(address);

        return partition.allocationSize(address);
    }

    /**
     * Returns the base address of an allocated block containing the "address".
     *
     * @param address  arbitrary address in memory block.
     *
     * @return base address of an allocated block.
     */
    long allocationBase(long address) {
        assert address != ZERO : "address should not be null";
        assert in(address) : "address should be in this partition";
        Partition partition = partitionFromAddress(address);

        return partition.allocationBase(address);
    }

    /**
     * Walk through all the allocations managed by the native allocator. The
     * first call should have an "address" of nullptr with successive calls
     * using the result of the previous call. The result itself can not be used
     * for memory access since the result may have been deallocated after
     * fetching (potential seg fault). The result can however be used to make
     *
     * @param address  ZERO or result of last call to nextAllocation.
     *
     * @return next allocation.
     */
    long nextAllocation(long address) {
        int index = address != ZERO && in(address) ? getPartitionIndex(address) : 0;

        while (index < partitionCount) {
            if (partitionRegistry.isSet(index)) {
                Partition partition = getPartition(index);
                long next = partition.nextAllocation(address);

                if (next != ZERO) {
                    return next;
                }
            }

            index++;
            address = ZERO;
        }

        return ZERO;
    }

    /**
     * Fills in counts and sizes arrays with information known to this
     * allocator. Slots 3 to MAX_ALLOCATION_ORDER contain counts and sizes of
     * allocations of that size order.
     *
     * Slot 0 - Sum of all other slots.
     * Slot 1 - Unused.
     * Slot 2 - Unused.
     * Slot 3-52 - Totals for blocks sized 2^slot.
     * Slot 48 and above - Unused.
     *
     * @param counts  counts array.
     * @param sizes   sizes array.
     */
    void stats(long[] counts, long[] sizes) {
        assert counts != null : "counts should not be null";
        assert sizes != null : "sizes should not be null";


        for (int i = 0; i < partitionCount; i++) {
            if (partitionRegistry.isSet(i)) {
                Partition partition = getPartition(i);
                partition.stats(counts, sizes);
            }
        }
    }
}
