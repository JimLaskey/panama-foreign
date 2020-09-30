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
 *---------------------------------------------------------------------------- *
 *
 * NativeAllocator is an experimental project and not currently intended to be
 * used in a production environment.
 *
 * This allocator was originally written in C++ and small subset reimplemented
 * here in Java.
 *
 * Contact: panama-dev@openjdk.java.net
 *
 *---------------------------------------------------------------------------- *
 *
 * Quantum Based Allocation Features
 * =================================
 *
 * - There are no locks or monitors. NativeAllocator uses atomic operations
 *   to provide memory coherence between cores. This no monitor aspect means
 *   that NativeAllocator can also be used to manage the allocation of shared
 *   memory across processes.
 *
 * - There are no expensive sbrk calls. NativeAllocator uses platform virtual
 *   memory reservation to manage memory.
 *
 * - There are no best-fit searches. NativeAllocator finds an allocation fit in
 *   constant time.
 *
 * - There are no free lists. NativeAllocator never touches allocated memory.
 *   Administrative bits are all held on the sidelines.  In fact,
 *   NativeAllocator can also be used to manage memory on external devices such
 *   as GPUs.
 *
 * - NativeAllocator has minimal external fragmentation. 100% of managed
 *   memory is recoverable. This means long running processes will not suffer
 *   from uncontrolled fragmentation growth.
 *
 * - NativeAllocator is scalable. Unlike malloc-free, allocation-deallocation
 *   rates remain constant no matter the allocation size or allocation
 *   volume; from 8 bytes to 4 petabytes.
 *
 * - NativeAllocator allocations require no alignment padding.
 *   NativeAllocator guarantees that every allocation is size aligned (up to
 *   64M). Ex. 4K allocations are 4K aligned.
 *
 * - NativeAllocator queries for allocation size are returned in constant time.
 *
 * - NativeAllocator can recover the base allocation address from any
 *   arbitrary address in constant time, making NativeAllocator ideal for
 *   garbage collectors.
 *
 * Overview
 * ========
 *
 * This is an implementation of memory allocation system which supplies
 * several different allocator methodologies depending on the size of
 * allocation. For allocations less than 64M, Quantum Based Allocation
 * allocators are used. For larger allocations, a slab allocator is used.
 *
 * All allocators defined in this implementation are a sub-class of the class
 * Allocator. Each allocator provides functionality for allocating and
 * deallocating memory, as well as providing queries for allocation attributes
 * and statistics.
 *
 * Allocation begins by choosing which allocator and allocation methodology is
 * to be used. The choice is based on the power of two "order" of the allocation
 * size that will satisfy the allocation request.
 *
 *     order = trunc(log2(size - 1))
 *
 * The order of any allocation request will be a value between 0 and 48
 * (typical hardware memory address space is limited to 2^48, but this can be
 * easily reconfigured.)
 *
 * The order is then used as a index by an instance of Roster to select an
 * appropriate allocator.
 *
 *     Order     Size      Allocator
 *     -----     ----      ---------
 *
 *     0-10      0-1K      small quantum allocator (or a specialized Partition)
 *     11-18     2K-256K   medium quantum allocator (or a specialized Partition)
 *     19-26     512K-64M  large quantum allocator (or a specialized Partition)
 *     27-48     64M-256T  SlabAllocator
 *     49-64     256T-     null allocator
 *
 * The allocator's "virtual allocate" function is then invoked, which in
 * response returns the memory address of the allocation or nullptr if it is not
 * capable of satisfying the request. Any further requests are mapped by the
 * allocation memory address to the sourcing allocator.
 *
 * A top level NativeAllocator object is used to coordinate all the allocators
 * within a region of reserved memory.
 *
 * Quantum Based Allocation
 * ========================
 *
 * The NativeAllocator API provides a healthy alternative to the standard
 * library malloc-free by exploiting contemporary system APIs and
 * multi-core 64-bit hardware. The term quantum is used here to describe
 * the minimum amount of memory used to satisfy a memory allocation. All of
 * NativeAllocator's allocations are quantum-centric.
 *
 * NativeAllocator is a 64-bit address space allocator, and as such, takes
 * advantage of the vast address space available on 64-bit processors.
 * Intel processors allow for memory addresses up to 2^52 bytes (older
 * processors 2^48.) This is significantly more memory than a typical
 * application would use. Even a TensorFlow slab would not likely exceed 256
 * *terabytes* (2^40)
 *
 * So it's not unreasonable for NativeAllocator to reserve large ranges of
 * memory in advance of allocation. This type of virtual memory reservation
 * is an inexpensive bookkeeping system call that doesn't tie up resources
 * other than restricting other system requests from using the requested
 * address range.
 *
 * Once memory is reserved, the memory is then logically divided into equal size
 * partitions. Ex. a 128M reserve could be divided into 128 x 1M partitions.
 * Care is given such that the first partition's base address is aligned to the
 * size of the partition. The result of this alignment guarantees that all
 * partitions are aligned, the partition's contents are aligned and a partition
 * index can be quickly determined by the simple shifting of an arbitrary
 * address in the partition space by the partition size order, i.e., partitions
 * are indexable.
 *
 * At some point, a partition will be selected by a quantum allocator to satisfy
 * an allocation request. Once selected, the partition is designated an order,
 * which describes the size of all the quanta accessible in the partition. Ex.
 * 1M partition could contain 256 x 4K quantum. Since, all the quanta in the
 * partition are the same size, they too are indexable.
 *
 * Additionally, all the quanta in the aligned partition are also size aligned.
 *
 * The indexability of both partitions and quanta is how NativeAllocator attains
 * constant time performance.
 *
 * Registries
 * ==========
 *
 * One of the minimum requirements of any application's memory allocator is
 * thread-safety. Many allocators, such as malloc, rely on monitors to lock out
 * competing threads. This is necessary because the complexity of updating
 * structures such as linked-lists is more easily dealt with by using critical
 * regions.
 *
 * NativeAllocator avoids monitors by using simple atomic operations.
 *
 * As described in previous section, the main elements, partitions and quanta,
 * are indexable. This means that an indexed bit in a bitmap can be used to
 * represent the element's state of availability (free or in-use.) Setting the
 * bit to 1 indicates that element is in-use and clearing the bit to zero
 * indicates the element is available.
 *
 * Implementing the bitmap using atomic operations provides thread-safety, but
 * what about performance? Linear searching a large bitmap or free bits sounds
 * expensive.
 *
 * A NativeAllocator Registry object manages an atomic bitmap using a few basic
 * techniques to boost performance.
 *
 * 1. Free bits are searched using 64-bit chunks (words) and not one bit at a
 *    time. This is done by doing some simple bit-twiddling involving
 *    count-leading-zeroes/count-trailing-zeroes instructions.
 *
 * 2. Keep an atomic index of where the lowest free bit resides.
 *
 * 3. Always fill the lowest bits first. This will fill in with long lived
 *    allocations early on and keep the rare scan of multiple words near the
 *    higher end of the bitmap.
 *
 * Combining these techniques means that, much of the time, finding a free bit
 * can be done in constant time.
 *
 * Allocation Performance
 * ======================
 *
 * NativeAllocator uses registries to manage both partition and quanta allocation.
 *
 * Allocating a partition involves flipping the allocation bit in a partition
 * registry, initializing the partition admin structure and flipping a partition
 * in-use bit in the order registry to indicate deployment (online.)
 *
 * Once deployed, a partition replaces the quantum allocator in the
 * corresponding order slot of the AllocatorRoster. Further allocations go
 * directly to the partition with no intervening supervision.
 *
 * Quantum allocation just involves finding and flipping the bit in the
 * partition's quanta registry and the returning the computed address of the
 * corresponding quantum.
 *
 * Deallocation Performance
 * ========================
 *
 * Once the quantum allocator is determined (one to three tiered range checks),
 * the partition index can be determined directly from the address (a
 * subtraction and a shift).
 *
 * The quantum index can be determined by masking the address with the partition
 * order bit mask. Deallocation is then indicated by clearing the bit in
 * partition's quanta registry.
 *
 * Configurations
 * ==============
 *
 * NativeAllocator uses multiple quantum allocators with several different
 * partition sizes. This is done to keep the quantum per partition count
 * low and thus keeping the size of the quanta registry bitmaps relatively
 * small.
 *
 * Secure Mode
 * ===========
 *
 * NativeAllocator supports a mode which clears memory when deallocated.
 * This technique is faster than clearing on allocation and is more secure.
 * Newly committed memory is already clear. Reallocating recycled blocks are not
 * necessarily used right away and may get swapped out before use and clearing
 * would force a reload from backing store.
 */