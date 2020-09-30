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
 * Defines the bounds of a managed memory range.
 */
class Space {
    /**
     * Base or lower bounds (inclusive) of a memory range.
     */
    private final long base;

    /**
     * Limit or upper bounds (exclusive) of a memory range.
     */
    private final long limit;

    /**
     * Constructor.
     *
     * @param base lower bounds of a memory range.
     * @param size size of memory range.
     */
    Space(long base, long size) {
        this.base = base;
        this.limit = base + size;
        assert base <= limit : "base should be less equal than limit";
    }

    /**
     * Retrieves lower bounds of a memory range.
     *
     * @return lower bounds of a memory range.
     */
    long base() {
        return base;
    }

    /**
     * Retrieves upper bounds of a memory range.
     *
     * @return upper bounds of a memory range.
     */
    long limit() {
        return limit;
    }

    /**
     * Retrieves size of memory range in bytes.
     *
     * @return size of memory range in bytes.
     */
    long size() {
        return limit - base;
    }

    /**
     * Tests if the address is in the bounds of the range.
     *
     * @param address  memory address to test.
     *
     * @return true if the address is in the bounds of the range.
     */
    boolean in(long address) {
        return base <= address && address < limit;
    }

    /*
     * Returns true if base == limit, i.e., is size zero.
     *
     * @returns true if space is empty.
     */
    boolean isEmpty() {
        return base == limit;
    }
}
