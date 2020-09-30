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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;

/**
 * This class is used to monitor the allocation and deallocation of enumerable
 * resources in a non-shared context.
 */
public class Registry {
    /**
     * Used to access the lowest field either normally or atomically.
     */
    private final static VarHandle LOWEST;

    /**
     * Used to access the bits array either normally or atomically.
     */
    private final static VarHandle BITS;

    static {
        VarHandle lowest;

        try {
            Lookup lookup = MethodHandles.lookup();
            lowest = lookup.findVarHandle(Registry.class, "lowest", int.class);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }

        LOWEST = lowest;
        BITS = MethodHandles.arrayElementVarHandle(long[].class);
    }

    /**
     * Maximum bit index.
     */
    protected final int maximum;

    /**
     * Maximum bit index.
     */
    protected final int maximumWords;

    /**
     * Index of lowest bitmap word containing free bits.
     */
    protected volatile int lowest;

    /**
     * Bits used for bitmap.
     */
    protected final long[] bits;

    /**
     * Constructor.
     *
     * @param maximum  maximum bit index.
     */
    protected Registry(int maximum) {
        assert 0 <= maximum && maximum <= Common.MAX_PARTITION_QUANTUM :
                "maximum out of range: " + maximum;
        this.maximum = maximum;
        this.maximumWords = wordsNeeded(maximum);
        this.lowest = 0;
        this.bits = new long[this.maximumWords];
    }


    /**
     * Create a new instance.
     *
     * @param maximum  maximum bit index.
     */
    public static Registry create(int maximum) {
        return new Registry(maximum);
    }

    /**
     * Create a new instance.
     *
     * @param isShared  true if the registry is shared by threads.
     * @param maximum   maximum bit index.
     */
    public static Registry create(boolean isShared, int maximum) {
        return isShared ? AtomicRegistry.create(maximum) :
                          Registry.create(maximum);
    }

    /**
     * The number of words needed to represent 'maximum' bits.
     *
     * @param maximum  maximum number of bits needed.
     *
     * @return number of words needed.
     */
    protected static int wordsNeeded(int maximum) {
        assert 0 <= maximum && maximum <= Common.MAX_PARTITION_QUANTUM :
                "maximum out of range: " + maximum;
        return Common.orderDiv(maximum + Common.BITS_PER_WORD - 1, Common.BITS_PER_WORD_ORDER);
    }

    /*
     * validates a bit index.
     *
     * @param index  value that should be be between 0 and maximum.
     *
     * @return true if index is valid.
     */
    protected boolean isValidIndex(int index) {
        return 0 <= index && index < maximum;
    }

    /*
     * validates a word index.
     *
     * @param wordIndex  index that should be between 0 and bits.length.
     *
     * @return true if wordIndex is valid.
     */
    protected boolean isValidWordIndex(int wordIndex) {
        return 0 <= wordIndex && wordIndex < bits.length;
    }

    /*
     * Combines a wordIndex and bitIndex into a single bit index reference.
     *
     * @param wordIndex  value between 0 and maximum.
     * @param bitIndex   value between 0 and BITS_PER_WORD.
     *
     * @return bit index reference.
     */
    protected static int getIndex(int wordIndex, int bitIndex) {
        assert 0 <= wordIndex : "wordIndex should be positive: " + wordIndex;
        assert 0 <= bitIndex : "wordIndex should be positive: " + bitIndex;

        return (wordIndex << Common.BITS_PER_WORD_ORDER) + bitIndex;
    }

    /*
     * Returns the word index of word containing the index bit.
     *
     * @param index  value between 0 and maximum.
     */
    protected static int getWordIndex(int index) {
        assert 0 <= index : "index should be positive: " + index;

        return index >> Common.BITS_PER_WORD_ORDER;
    }

    /*
     * Returns the bit index (from lowest bit) in word containing the index bit.
     *
     * @param index  value between 0 and maximum.
     */
    protected static int getBitIndex(int index) {
        assert 0 <= index : "index should be positive: " + index;

        return index & (int) Common.BITS_MASK;
    }

    /**
     * Atomically safe fetch of the lowest field.
     *
     * @return  index of lowest bitmap word containing free bits.
     */
    protected int getLowest() {
        assert LOWEST != null : "lowest field VarHandle not set";
        return (int)LOWEST.getVolatile(this);
    }

    /**
     * Atomically safe store of the lowest field.
     *
     * @param value  new index of lowest bitmap word containing free bits.
     */
    protected void setLowest(int value) {
        assert LOWEST != null : "lowest field VarHandle not set";
        LOWEST.setVolatile(this, value);
    }

    /**
     * Atomically safe swap store of the lowest field.
     *
     * @param expected value expected in the lowest field.
     * @param value    new index of lowest bitmap word containing free bits.
     *
     * @return old value of field.
     */
    protected int swapLowestAtomic(int expected, int value) {
        assert LOWEST != null : "lowest field VarHandle not set";
        return (int)LOWEST.compareAndExchange(this, expected, value);
    }

    /**
     * Attempts to increment the lowest free word index.
     * If it fails to do so then it returns the value set by other thread.
     *
     * @param wordIndex  expected value of lowest free word index.
     *
     * @return new value of lowest free word index.
     */
    protected int incrementLowestIndex(int wordIndex) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;
        int next = wordIndex + 1;
        setLowest(next);

        return next;
    }

    /*
     * Updates the lowest free word index if the
     * specified word is lower.
     *
     * @param wordIndex  value between 0 and bits.length.
     */
    protected void lowerLowest(int wordIndex) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;
        int expected = getLowest();

        if (wordIndex < expected) {
            setLowest(wordIndex);
        }
    }

    /**
     * Atomically safe fetch of a bits array element at index.
     *
     * @param index  bits array index.
     *
     * @return bits array element at index.
     */
    protected long getBits(int index) {
        assert 0 <= index && index < bits.length :
                "index out of range: " + index;
        return (long)BITS.getVolatile(bits, index);
    }

    /**
     * Atomically safe store of a bits array element at index.
     *
     * @param index  bits array index.
     * @param value  value to store.
     */
    protected void setBits(int index, long value) {
        assert 0 <= index && index < bits.length :
                "index out of range: " + index;
        BITS.setVolatile(bits, index, value);
    }

    /**
     * Atomically safe compare store of a bits array element at index.
     *
     * @param index     bits array index.
     * @param expected  value to expect.
     * @param value     value to store.
     *
     * @return true if successful.
     */
    protected boolean setBitsAtomic(int index, long expected, long value) {
        assert 0 <= index && index < bits.length :
                "index out of range: " + index;
        return (boolean)BITS.weakCompareAndSet(bits, index, expected, value);
    }

    /**
     * Atomically safe swap store of a bits array element at index.
     *
     * @param index     bits array index.
     * @param expected  value to expect.
     * @param value     value to store.
     *
     * @return old value of bits array element.
     */
    protected long swapBitsAtomic(int index, long expected, long value) {
        assert 0 <= index && index < bits.length :
                "index out of range: " + index;
        return (long)BITS.compareAndExchange(bits, index, expected, value);
    }

    /**
     * Bitwise and of the bits for the specified word index.
     *
     * @param wordIndex  index that should be between 0 and bits.length.
     * @param value      value to bitwise and in.
     *
     * @return old value at that word index.
     */
    protected long andBits(int wordIndex, long value) {
        long bits = getBits(wordIndex);
        setBits(wordIndex, bits & value);

        return bits;
    }

    /**
     * Bitwise or of the bits for the specified word index.
     *
     * @param wordIndex  index that should be between 0 and bits.length.
     * @param value      value to bitwise or in.
     *
     * @return old value at that word index.
     */
    protected long orBits(int wordIndex, long value) {
        long bits = getBits(wordIndex);
        setBits(wordIndex, bits | value);

        return bits;
    }

    /*
     * Weakly tests if bit is set. State may change after reading unless
     * reader "owns" (has allocated) bit.
     *
     * index - int index between 0 and _maximumIndex of bit to test.
     */
    protected boolean isSet(int index) {
        assert isValidIndex(index) : "index out of range: " + index;

        int wordIndex = getWordIndex(index);
        int bitIndex = getBitIndex(index);
        long bit = Common.orderToSize(bitIndex);
        long value = getBits(wordIndex);

        return (value & bit) != Common.ZERO;
    }

    /*
     * Unconditionally sets the mask bits in the word indexed by wordIndex.
     *
     * @param wordIndex  value that is between 0 and bits.length.
     * @param mask       bits that should be set to one.
     *
     * @return true if affected bits were all previously zeroes.
     */
    protected boolean setMask(int wordIndex, long mask) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;

        return mask == Common.ZERO || (orBits(wordIndex, mask) & mask) == Common.ZERO;
    }

    /*
     * Unconditionally clears the mask bits in the word indexed by
     * wordIndex.
     *
     * @param wordIndex  value that is between 0 and bits.length.
     * @param mask       bits that should be set to zero.
     *
     * @return true if bits were previously ones.
     */
    protected boolean clearMask(int wordIndex, long mask) {
        assert isValidWordIndex(wordIndex) :
                "wordIndex out of range: " + wordIndex;

        return mask == Common.ZERO || (andBits(wordIndex, ~mask) & mask) != Common.ZERO;
    }

    /*
     * Conditionally sets a bit.
     *
     * @param index - value between 0 and maximum of bit to set.
     *
     * @return true if successful.
     */
    protected boolean set(int index) {
        assert isValidIndex(index) : "index out of range: " + index;

        int wordIndex = getWordIndex(index);
        int bitIndex = getBitIndex(index);

        return setMask(wordIndex, Common.orderToSize(bitIndex));
    }

    /*
     * Conditionally clears a bit.
     *
     * @param index - value between 0 and maximum of bit to set.
     *
     * @return true if successful.
     */
    protected boolean clear(int index) {
        assert isValidIndex(index) : "index out of range";

        int wordIndex = getWordIndex(index);
        int bitIndex = getBitIndex(index);

        return clearMask(wordIndex, Common.orderToSize(bitIndex));
    }

    /*
     * Finds the lowest free bit in the registry.
     *
     * @return the index or NOT_FOUND if no free bits.
     */
    public int findFree() {
        /*
         * Loop until exhaust words.
         */
        for (int wordIndex = getLowest();
             wordIndex != maximumWords;
        ) {
            /*
             * Sample the current word.
             */
            long value = getBits(wordIndex);

            if (Common.isAllOnes(value)) {
                /*
                 * Try bumping up word index, but may get index pointed to
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
             * Combine wordIndex and bitIndex to create a registry bit
             * index.
             */
            int index = getIndex(wordIndex, bitIndex);

            /*
             * May exceed the count of the registry (free bits in unused
             * portion of word.
             */
            if (maximum <= index) {
                return Common.NOT_FOUND;
            }

            long newValue = value | Common.orderToSize(bitIndex);

            /*
             * Update word with bit set.
             */
            setBits(wordIndex, newValue);

            return index;
        }

        return Common.NOT_FOUND;
    }

    /*
     * Clears the bit at index and then updates the lowest index.
     *
     * @param index - value between 0 and maximum of bit to clear.
     */
    public void free(int index) {
        assert isValidIndex(index) : "index out of range";
        clear(index);
        lowerLowest(getWordIndex(index));
    }

    /*
     * Makes a best guess attempt to see if registry is empty. Can be accurate
     * if registry is offline. Best used to sample if possibly empty then
     * offline registry and the try again for accurate result.
     *
     * @return true if no bits set.
     */
    boolean isEmpty() {
        if (getLowest() == 0) {
            for (int i = 0; i < maximum; i++) {
                if (getBits(i) != Common.ZERO) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /*
     * Sample enumeration of an active registry's set bits.
     *
     * @return a sample count of bits set.
     */
    int count() {
        int c = 0;

        for (int i = 0; i < bits.length; i++) {
            c += Long.bitCount(getBits(i));
        }

        return c;
    }

    /**
     * Creates a new RegistryIsSetIterator with an initial index.
     *
     * @param initial  initial start point.
     *
     * @return a new RegistryIsSetIterator.
     */
    RegistryIsSetIterator isSetIterator(int initial) {
        return new RegistryIsSetIterator(initial);
    }

    /**
     * Creates a new RegistryIsSetIterator starting at zero.
     *
     * @return a new RegistryIsSetIterator.
     */
    RegistryIsSetIterator isSetIterator() {
        return new RegistryIsSetIterator(0);
    }

    public class RegistryIsSetIterator {

        /**
         * Last index visited.
         */
        private int last;

        /**
         * Constructor.
         *
         * @param initial  initial start point.
         */
        RegistryIsSetIterator(int initial) {
            this.last = initial;
        }

        /**
         * Next bit index in the bitmap.
         *
         * @return next bit index in the bitmap.
         */
        public int nextSet() {
            int bitIndex = getBitIndex(last);

            /*
             * Iterate through bitmap words.
             */
            for (int wordIndex = getWordIndex(last);
                 wordIndex < maximumWords;
                 wordIndex++)
            {
                /*
                 * Fetch word value and mask out "seen" bits.
                 */
                long value = getBits(wordIndex);
                value &= ~Common.mask(Common.orderToSize(bitIndex));

                //
                // If any bits remaining
                //
                if (value != Common.ZERO) {
                    //
                    // Locate lowest bit.
                    //
                    bitIndex = Long.numberOfTrailingZeros(value);

                    //
                    // Combine word and bit index for result.
                    //
                    int index = getIndex(wordIndex, bitIndex);

                    if (maximum <= index) {
                        //
                        // Invalid result.
                        //
                        break;
                    }

                    //
                    // Have valid index, then update saved index and return result.
                    //
                    last = index + 1;

                    return index;
                }

                //
                // Reset bit index for next word.
                //
                bitIndex = 0;
            }

            last = maximum;

            return Common.NOT_FOUND;
        }
    }
}

