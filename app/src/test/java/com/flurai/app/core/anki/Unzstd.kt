package com.flurai.app.core.anki

import io.airlift.compress.zstd.ZstdInputStream
import java.io.InputStream

/** Decompresses zstd on the JVM for tests (the app uses zstd-jni, whose native code is built for Android). */
fun unzstd(input: InputStream): InputStream = ZstdInputStream(input)
