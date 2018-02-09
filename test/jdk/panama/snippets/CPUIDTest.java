/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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


/*
 * @test
 * @modules jdk.internal.vm.ci/jdk.vm.ci.panama.amd64
 * @run main/othervm panama.snippets.CPUIDTest
 */

package panama.snippets;

import jdk.vm.ci.panama.amd64.CPUID;

public class CPUIDTest {
    public static void main(String[] args) {
        if (!CPUID.isSupported())  return; // Not supported

        for (int i = 0; i < 20_000; i++) {
            CPUID.cpuid(0, 0);
        }
        int max = CPUID.cpuid(0, 0).eax;
        for (int i = 0; i < max; i++) {
            System.out.printf("0x%02xH: %s\n", i, CPUID.cpuid(i, 0));
        }
    }
}

