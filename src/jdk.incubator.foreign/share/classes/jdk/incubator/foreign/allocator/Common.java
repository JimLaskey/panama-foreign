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
 * Common constants and methods  used by memory allocator.
 */
public final class Common {
    /**
     * Indicates that an index was not found.
     */
    public final static int NOT_FOUND = -1;

    /**
     * 64 bit all zeroes
     */
    public final static long ZERO = 0L;

    /**
     * 64 bit all ones
     */
    public final static long ALL_ONES = ~ZERO;

    /**
     * Memory size orders for kilo, mega, giga, tera and peta.
     */
    public final static int K_ORDER = 10;
    public final static int M_ORDER = K_ORDER + K_ORDER;
    public final static int G_ORDER = K_ORDER + M_ORDER;
    public final static int T_ORDER = K_ORDER + G_ORDER;
    public final static int P_ORDER = K_ORDER + T_ORDER;

    /**
     * Memory sizes for kilo, mega, giga, tera and peta.
     */
    public final static long K = 1024L;
    public final static long M = K * K;
    public final static long G = K * M;
    public final static long T = K * G;
    public final static long P = K * T;

    /**
     * Number of bytes per word.
     */
    public final static int BYTES_PER_WORD = Long.BYTES;

    /**
     * Number of bits per word.
     */
    public final static int BITS_PER_WORD = Long.SIZE;

   /*
    * For masking to number of bits per word.
    */
    public final static long BITS_MASK = mask(BITS_PER_WORD);

    /**
     * Order of BITS_PER_WORD.
     */
    public final static int BITS_PER_WORD_ORDER = order(BITS_PER_WORD);

    /**
     * Order of memory address that could be potentially allocated.
     */
    public final static int MAX_ADDRESS_ORDER = 48;

    /**
     * Order of maximum memory address that can be handled by the native allocator.
     */
    public final static int MAX_ALLOCATION_ORDER = MAX_ADDRESS_ORDER - 4; // 16T

    /**
     * Maximum memory address that could be potentially allocated.
     */
    public final static long MAX_ADDRESS_SIZE = orderToSize(MAX_ADDRESS_ORDER);

    /**
     * Maximum number of entries in a roster.
     */
    public final static int MAX_ROSTER = BITS_PER_WORD + 1;

    /**
     * Mask for MAX_ADDRESS_SIZE.
     */
    public final static long MAX_ADDRESS_MASK = mask(MAX_ADDRESS_SIZE);

    /**
     * Mask for valid address.
     */
    public final static long VALID_ADDRESS_MASK = MAX_ADDRESS_MASK & ~mask(8);

    /**
     * Maximum memory address that can be handled by the native allocator.
     */
    public final static long MAX_ALLOCATION_SIZE = orderToSize(MAX_ALLOCATION_ORDER);

    /**
     *  Maximum quantum per partition.
     */
    public final static int MAX_PARTITION_QUANTUM = 16 * (int)K;

    /**
     * Number of quantum allocators.
     */
    public final static int MAX_QUANTUM_ALLOCATORS = 3;

    /**
     * Maximum number of elements that can be managed by a registry. This is an
     * arbitrary fixed value to ease the dynamic allocation of internal structures.
     */
    public final static int MAX_REGISTRY_BIT_COUNT = MAX_PARTITION_QUANTUM;

    /**
     * Maximum number of words required to handled MAX_REGISTRY_BIT_COUNT elements.
     */
    public final static int MAX_REGISTRY_WORD_COUNT =
            MAX_REGISTRY_BIT_COUNT >> BITS_PER_WORD_ORDER;

    /**
     * Maximum number of orders managed by a single quantum allocator. It was
     * chosen to keep the range supported by MAX_REGISTRY_BIT_COUNT reasonable.
     */
    public final static int MAX_QUANTUM_ALLOCATOR_ORDERS = 8;

    /**
     * Order of smallest quantum.
     */
    public final static int SMALLEST_SIZE_ORDER = 3;

    /**
     * Size of smallest quantum.
     */
    public final static long SMALLEST_SIZE = orderToSize(SMALLEST_SIZE_ORDER);

    /**
     * Order of largest quantum.
     */
    public final static int LARGEST_SIZE_ORDER = SMALLEST_SIZE_ORDER +
                                                 MAX_QUANTUM_ALLOCATORS *
                                                 MAX_QUANTUM_ALLOCATOR_ORDERS - 1;

    /**
     * Size of largest quantum.
     */
    public final static long LARGEST_SIZE = orderToSize(LARGEST_SIZE_ORDER);

    /**
     * Address validation, primarily used to valid addresses passed to virtual
     * memory calls.
     *
     * @param address  any memory address cast as a 64 bit value.
     *
     * @return true if a valid address.
     */
    static boolean isValidAddress(long address) {
        return address != ZERO && (address & ~VALID_ADDRESS_MASK) == ZERO;
    }

    /**
     * Tests is a 64 bit value is all ones.
     *
     * @param value  value to test.
     *
     * @return true if the value is all ones.
     */
    static boolean isAllOnes(long value) {
        return value == ALL_ONES;
    }

    /**
     * Tests if the result of a index search is valid.
     *
     * @param index  index to test.
     *
     * @return true if index was found.
     */
    static boolean isFound(int index) {
        return 0 <= index;
    }

    /**
     * Tests if the result of a index search is is invalid.
     *
     * @param index  index to test.
     *
     * @return true if index was not found.
     */
    static boolean isNotFound(int index) {
        return index < 0;
    }

    /**
     * Compute the integer log2 if an int value.
     *
     * @param value  32 bit value
     *
     * @return integer log2 if the value.
     */
    public static int order(int value) {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(value);
    }

    /**
     * Compute the integer log2 if a long value.
     *
     * @param value  long value
     *
     * @return integer log2 if the value.
     */
    public static int order(long value) {
        return Long.SIZE - 1 - Long.numberOfLeadingZeros(value);
    }

    /**
     * Constructs a low mask from a power of two.
     *
     * @param power2  power of 2 value;
     *
     * @return a mask with the lower bits set.
     */
    public static long mask(long power2) {
        assert isPowerOfTwo(power2) : "power2 is not power of two: " + power2;
        return power2 - 1L;
    }

    /**
     * Tests is a value is a power of two. Note that zero is treated as a
     * power of two for efficiency.
     *
     * Ex. isPowerOf2(0x100) yields true.
     *
     * @param power2  value to be tested.
     *
     * @return true if power of two.
     */
    public static boolean isPowerOfTwo(long power2) {
        return (power2 & (power2 - 1L)) == 0L;
    }

    /**
     * Computes two to the order power.
     *
     * Ex. orderToSize(5) yields 2^5 or 32.
     *
     * @param order  log2 order of memory size.
     *
     * @return two to the order power.
     */
    public static long orderToSize(int order) {
        assert 0 <= order && order < BITS_PER_WORD :
                "order is out of range: " + order;
        return 1L << order;
    }

    /**
     * Rounds the value up to the specified power of two. Primarily used
     * to size up align to the next quantum.
     *
     * Ex. roundUp(0x50034, 0x1000) yields 0x60000.
     *
     * @param value     64 bit value to be rounded.
     * @param powerOf2  64 bit with a single bit set.
     *
     * @return value rounded up to specified power of two.
     */
    static long roundUp(long value, long powerOf2) {
        assert isPowerOfTwo(powerOf2) :
                "powerOf2 is not power of two: " + powerOf2;
        long mask = mask(powerOf2);

        return (value + mask) & ~mask;
    }

    /**
     * Rounds the value up to the next power of two. Primarily used
     * to size up align to the next quantum.
     *
     * Ex. roundUp(0x50000) yields 0x80000.
     *
     * @param value  64 bit value to be rounded.
     *
     * @return value rounded up to next power of two.
     */
    static long roundUpPowerOf2(long value) {
        return value != 0
                ? orderToSize(BITS_PER_WORD - Long.numberOfLeadingZeros(value - 1))
                : ZERO;
    }

    /**
     * Translates an allocation size to a power of two order. I.E., the
     * power of two bytes that is required to satisfy the allocation. Values less
     * than SMALLEST_SIZE are special cased to always yield SMALLEST_SIZE_ORDER.
     *
     * Ex. sizeToOrder(17) yields 5. 2^5 == 32 bytes the smallest quantum that can
     * satisfy an allocation of 17 bytes.
     *
     * @param size  size of allocation.
     *
     * @return  order of specified size.
     */
    static int sizeToOrder(long size) {
        if (size <= SMALLEST_SIZE) {
            return SMALLEST_SIZE_ORDER;
        } else if (size <= SMALLEST_SIZE << 1) {
            return SMALLEST_SIZE_ORDER + 1;
        } else if (size <= SMALLEST_SIZE << 2) {
            return SMALLEST_SIZE_ORDER + 2;
        }

        return BITS_PER_WORD - Long.numberOfLeadingZeros(size - 1);
    }

    /**
     * Primarily to make the multiplication by an order distinct from
     * the underlying shift operation.
     *
     * Ex. order = sizeToOrder(size)
     *     offset = orderMul(index, order)
     *     offset == index * size
     *
     * @param value  64 bit value.
     * @param order  int value between 0 and BITS_PER_WORD.
     *
     * @return value multiplied by two to the order.
     */
    static long orderMul(long value, int order) {
        assert 0 < order && order < BITS_PER_WORD :
                "order is out of range: " + order;
        return value << order;
    }

    /**
     * Primarily to make the division by an order distinct from the
     * underlying shift operation.
     *
     * Ex. partitionIndex = orderDiv(address, order)
     *
     * @param value  64 bit value.
     * @param order  int value between 0 and BITS_PER_WORD.
     *
     * @return value divided by two to the order.
     */
    static int orderDiv(long value, int order) {
        assert 0 < order && order < BITS_PER_WORD :
                "order is out of range: " + order;
        return (int)(value >>> order);
    }
}
