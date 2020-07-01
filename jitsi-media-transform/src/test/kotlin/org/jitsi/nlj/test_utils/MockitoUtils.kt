package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.UseConstructor
import com.nhaarman.mockitokotlin2.mock
import org.mockito.Mockito

/**
 * Like Mockito spy(), but with stubOnly set to avoid excessive memory consumption.
 */
inline fun <reified T : Any> stubOnlySpy(): T {
    return mock(
        stubOnly = true,
        useConstructor = UseConstructor.parameterless(),
        defaultAnswer = Mockito.CALLS_REAL_METHODS
    )
}
