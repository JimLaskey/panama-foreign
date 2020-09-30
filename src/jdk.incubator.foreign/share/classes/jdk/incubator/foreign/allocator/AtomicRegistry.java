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
 * This class is used to monitor the allocation and deallocation of enumerable
 * resources in a shared context.
 */
final public class AtomicRegistry
        extends Registry {

    /**
     * Constructor.
     *
     * @param maximum  maximum bit index.
     */
    private AtomicRegistry(int maximum) {
        super(maximum);
    }

    /**
     * Create a new instance.
     *
     * @param maximum  maximum bit index.
     */
    public static Registry create(int maximum) {
        return new AtomicRegistry(maximum);
    }

    @Override
    protected int incrementLowestIndex(int wordIndex) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;
        /*
         * Make a single attempt to increment lowest index.
         */
        int next = wordIndex + 1;
        int was = swapLowestAtomic(wordIndex, next);

        return was == wordIndex ? next : was;
    }

    /**
     * Updates the lowest free word index if the specified word is lower.
     *
     * @param wordIndex  value between 0 and bits.length.
     */
    protected void lowerLowest(int wordIndex) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;
        /*
         * Sample current lowest free word index.
         */
        int expected = getLowest();

        /*
         * Loop while wordIndex is less than current lowest free word index.
         */
        while (wordIndex < expected) {
            /*
             * Attempt to set.
             */
            int found = swapLowestAtomic(expected, wordIndex);

            /*
             * If successful.
             */
            if (found == expected) {
                break;
            }

            /*
             * Try again with new value.
             */
            expected = found;
        }
    }

    @Override
    protected long andBits(int wordIndex, long value) {
        while (true) {
            long bits = getBits(wordIndex);

            if (setBitsAtomic(wordIndex, bits, bits & value)) {
                return bits;
            }
        }
    }

    @Override
    protected long orBits(int wordIndex, long value) {
        while (true) {
            long bits = getBits(wordIndex);

            if (setBitsAtomic(wordIndex, bits, bits | value)) {
                return bits;
            }
        }
    }

    @Override
    public int findFree() {
        /*
         * Get current lowest word.
         */
        int wordIndex = getLowest();

        /*
         * If full.
         */
        if (wordIndex == maximumWords) {
            return Common.NOT_FOUND;
        }

        /*
         * Sample the current word.
         */
        long value = getBits(wordIndex);

        /*
         * Loop until exhaust words.
         */
        do {
            if (Common.isAllOnes(value)) {
                /*
                 * Try bumping up word index, but may get index updated
                 * by another thread.
                 */
                wordIndex = incrementLowestIndex(wordIndex);

                /*
                 * Try again.
                 */
                continue;
            }

            /*
             * Get bit index of lowest zero bit.
             */
            int bitIndex = Long.numberOfTrailingZeros(~value);

            /*
             * Combine wordIndex and bitIndex to create a registry bit index.
             */
            int index = getIndex(wordIndex, bitIndex);

            /*
             * May exceed the count of the registry (free bits in unused
             * portion of last word.
             */
            if (maximum <= index) {
                return Common.NOT_FOUND;
            }

            long newValue = value | Common.orderToSize(bitIndex);

            /*
             * Attempt to update word with bit set.
             */
            long oldValue = swapBitsAtomic(wordIndex, value, newValue);

            if (oldValue == value) {
                /*
                 * Successfully set bit.
                 */
                return index;
            }

            /*
             * Update value and try again.
             */
            value = oldValue;
        } while (wordIndex != maximumWords);

        return Common.NOT_FOUND;
    }
}
