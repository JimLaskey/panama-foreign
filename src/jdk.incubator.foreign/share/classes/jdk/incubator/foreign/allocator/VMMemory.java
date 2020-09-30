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

import jdk.internal.misc.Unsafe;

import static jdk.incubator.foreign.allocator.Common.*;

/**
 * Encapsulate all system VM memory calls.
 */
class VMMemory {
    /**
     * Unsafe for use by VMMemory.
     */
    private final static Unsafe UNSAFE = Unsafe.getUnsafe();

    /**
     * Platform memory page size.
     */
    public final static long PAGE_SIZE = UNSAFE.pageSize();

    /**
     * Mask for memory page size.
     */
    public final static long PAGE_MASK = PAGE_SIZE - 1;

    /**
     * Reserve a specific address range for future use by an allocator. Returns
     * the reserve address or zero if the request can not be satisfied.
     *
     * @param size      size of memory (in bytes) to reserve. Should be multiple
     *                  of PAGE_SIZE.
     * @param location  fixed memory location or zero for arbitrary address.
     *
     * @return reserved address.
     */
    static long reserve(
            long size,
            long location
            ) {
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        assert (location & PAGE_MASK) == 0 :
                "location must be aligned to page size: " + location;
        return UNSAFE.memoryReserve(location, size);
    }

    /**
     * Reserve an address range for future use by an allocator. Returns the
     * reserve address or zero if the request can not be satisfied.
     *
     * @param size      size of memory (in bytes) to reserve. Should be multiple
     *                  of PAGE_SIZE.
     *
     * @return reserved address.
     */
    static long reserve(
            long size
    ) {
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        return UNSAFE.memoryReserve(ZERO, size);
    }


    /**
     * Allocate a range of memory aligned to the specified power of two. This is
     * done by over-reserving an address range by an allocator. The excess is
     * necessary to guarantee the required alignment. Any excess is returned
     * to the system after the aligned range is excised from the middle of the
     * over reserve. Returns the reserve address or zero if the request can
     * not be satisfied.
     *
     * @param size       size of memory (in bytes) to reserve. Should be multiple
     *                   of PAGE_SIZE.
     * @param alignment  power of two alignment.
     *
     * @return reserved address.
     */
    static long reserveAligned(long size, long alignment) {
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        assert alignment != ZERO && (alignment & PAGE_MASK) == 0 &&
                    isPowerOfTwo(alignment):
                "alignment must be a power of two and aligned to page size: " +
                        Long.toHexString(alignment);

        /*
         * Over allocate by the alignment size. This will allow an aligned
         * portion to be excised from the middle of the reserve.
         */
        long reserveSize = size + alignment - PAGE_SIZE;
        long address = reserve(reserveSize);

        if (address == ZERO) {
            return ZERO;
        }

        /*
         * Compute the base of aligned reserve.
         */
        long base = roundUp(address, alignment);

        /*
         * Compute the size of the excesses before and after the aligned
         * reserve.
         */
        long prefixSize = base - address;
        long postfixSize = reserveSize - size - prefixSize;

        /*
         * Return the prefix excess back to the system.
         */
        if (prefixSize != ZERO) {
            release(address, prefixSize);
        }

        /*
         * Return the postfix excess back to the system.
         */
        if (postfixSize != ZERO) {
            release(base + size, postfixSize);
        }

        return base;
    }

    /**
     * Release reserved memory back to the system.
     *
     * @param address  base address previously reserved.
     * @param size     size of memory (in bytes) previously reserved.
     */
    static void release(long address, long size) {
        assert isValidAddress(address) : "address is invalid";
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        UNSAFE.memoryRelease(address, size);
    }

    /*
     * Commit reserved memory.
     *
     * @param address base address previously reserved.
     * @param size    size of memory (in bytes) previously reserved. Should be a
     *                multiple of PAGE_SIZE.
     */
    static void commit(long address, long size) {
        assert isValidAddress(address) : "address is invalid";
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        UNSAFE.memoryCommit(address, size);
    }

    /*
     * Uncommit reserved memory.
     *
     * @param address base address previously reserved.
     * @param size    size of memory (in bytes) previously reserved. Should be a
     *                multiple of PAGE_SIZE.
     */
    static void uncommit(long address, long size) {
        assert isValidAddress(address) : "address is invalid";
        assert (size & PAGE_MASK) == 0 :
                "size must be aligned to page size: " + size;
        UNSAFE.memoryUncommit(address, size);
    }


    /**
     * Clear memory.
     *
     * @param address  base address of memory to clear.
     * @param size     size of memory (in bytes) to clear.
     */
    static void clear(long address, long size) {
        assert isValidAddress(address) : "address is invalid";
        assert (size & mask(BYTES_PER_WORD)) == 0 :
                "size must align on 8 bytes: " + size;
        UNSAFE.setMemory(address, size, (byte)0);
    }

    /*
     * Optimal copy memory. (Used for reallocate.)
     *
     * @param src   address of memory to copy from.
     * @param dst   address of memory to copy to.
     * @param size  size of memory (in bytes) to copy.
     */
    static void copy(long src, long dst, long size) {
        assert isValidAddress(src) : "source is invalid";
        assert isValidAddress(dst) : "destination is invalid";
        assert (size & mask(BYTES_PER_WORD)) == 0 :
                "size must align on 8 bytes: " + size;
        UNSAFE.copyMemory(src, dst, size);
    }
}
