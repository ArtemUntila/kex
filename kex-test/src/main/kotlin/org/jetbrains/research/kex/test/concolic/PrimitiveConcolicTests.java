package org.jetbrains.research.kex.test.concolic;

import org.jetbrains.research.kex.intrinsics.AssertIntrinsics;

public class PrimitiveConcolicTests {
    public void testInt(int a) {
        if (a > 0) {
            AssertIntrinsics.kexAssert(true);
        } else if (a == 0) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }

    public void testDouble(double d) {
        if (d > 0.0) {
            AssertIntrinsics.kexAssert(true);
        } else if (d == 0.0) {
            AssertIntrinsics.kexAssert(true);
        } else {
            AssertIntrinsics.kexAssert(true);
        }
    }
}