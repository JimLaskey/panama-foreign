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
 * The Allocator class defines the minimum set of methods that all
 * allocators must define.
 */
abstract class Allocator extends Space {
    /**
     * Size order of the smallest quantum handled by this allocator.
     */
    private final int smallestSizeOrder;

    /**
     * Size order of the largest quantum handled by this allocator.
     */
    private final int largestSizeOrder;

    /**
     * Constructor.
     *
     * @param base               lower bounds of a memory range.
     * @param size               size of memory range.
     * @param smallestSizeOrder  order of the smallest quantum handled.
     * @param largestSizeOrder   order of the largest quantum handled.
     */
    Allocator(
        long base,
        long size,
        int smallestSizeOrder,
        int largestSizeOrder
    )
    {
        super(base, size);
        this.smallestSizeOrder = smallestSizeOrder;
        this.largestSizeOrder = largestSizeOrder;
    }

    /**
     * Allocates a memory block of size order bytes.
     *
     * @param order  size order to allocate.
     *
     * @return address of memory block or zero if not allocated.
     */
    abstract long allocate(int order);

    /**
     * Retrieves smallest quantum order handled by this allocator.
     *
     * @return smallest quantum order handled by this allocator.
     */
    int smallestSizeOrder() {
        return smallestSizeOrder;
    }

    /**
     * Retrieves largest quantum order handled by this allocator.
     *
     * @return largest quantum order handled by this allocator.
     */
    int largestSizeOrder() {
        return largestSizeOrder;
    }

    /**
     * Retrieves smallest quantum size handled by this allocator.
     *
     * @return smallest quantum size handled by this allocator.
     */
    long smallestSize() {
        return Common.orderToSize(smallestSizeOrder);
    }

    /**
     * Retrieves largest quantum size handled by this allocator.
     *
     * @return largest quantum size handled by this allocator.
     */
    long largestSize() {
        return Common.orderToSize(largestSizeOrder);
    }
}
