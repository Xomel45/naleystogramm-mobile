package com.xomel45.naleystogramm.crypto

// Mirrors: src/crypto/securedata.h — guaranteed zeroing of key material.
// JVM GC cannot guarantee timing, but fill(0) is the best available option.
fun ByteArray.secureZero() {
    fill(0)
}
