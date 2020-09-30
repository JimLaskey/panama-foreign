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

import java.util.concurrent.atomic.AtomicReferenceArray;

import static jdk.incubator.foreign.allocator.Common.*;

/**
 * Used to assign allocators to specific size orders. A Director class
 * instance is usually responsible for making the initial assignments. The
 * entries in the roster are atomic because they can change over time.
 * Ex. A partition allocator may take over for a quantum allocator for a
 * specific order and thereby removing the middleman (overhead.)
 */
class Roster {
    /**
     * A table of allocators indexed by size order. The allocator at a given
     * index (order) can presumably allocate blocks of that size.
     */
    private final AtomicReferenceArray<Allocator> allocators;

    /**
     * Constructor.
     */
    Roster() {
        this.allocators = new AtomicReferenceArray<>(MAX_ROSTER);
    }

    /**
     * Returns the allocator assigned to order.
     *
     * @param order  order of size to be allocated.
     *
     * @return allocator assigned to order.
     */
    Allocator getAllocator(int order) {
        assert SMALLEST_SIZE_ORDER <= order && order <= MAX_ROSTER :
                "order out of range: " + order;
        Allocator allocator = allocators.get(order);

        return allocator;
    }

    /*
     * Assigns an allocator to a specific order.
     *
     * @param allocator  allocator instance used to allocate blocks of size order.
     * @param order      order of size to be allocated.
     */
    void setAllocator(Allocator allocator, int order) {
        assert allocator != null : "allocator should not be null";
        assert SMALLEST_SIZE_ORDER <= order && order <= MAX_ROSTER :
                "order out of range: " + order;
        allocators.set(order, allocator);
    }

    /*
     * setAllocators assigns an "allocator" to a range of orders.
     *
     * allocator - allocator instance used to allocate blocks of size order.
     * loOrder - lower bounds of order range (inclusive).
     * hiOrder - upper bounds of order range (exclusive).
     */
    void setAllocators(Allocator allocator, int loOrder, int hiOrder) {
        assert allocator != null : "allocator should not be null";
        assert 0 <= loOrder && loOrder <= MAX_ROSTER :
                "order out of range: " + loOrder;
        assert SMALLEST_SIZE_ORDER <= hiOrder && hiOrder <= MAX_ROSTER :
                "order out of range: " + hiOrder;

        for (int i = loOrder; i <= hiOrder ; i++) {
            allocators.set(i, allocator);
        }
    }
}
