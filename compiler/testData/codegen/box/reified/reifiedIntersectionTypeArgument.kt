// WITH_RUNTIME
// KJS_WITH_FULL_RUNTIME

// See KT-37163

import kotlin.reflect.typeOf

class In<in T>

interface A
interface B

// TODO check real effects to fix the behavior when we reach consensus
//  and to be sure that something is not dropped by optimizations.

@OptIn(kotlin.ExperimentalStdlibApi::class)
inline fun <reified K> select(x: K) where K : A, K : B {
    x is K
    x as K
    K::class
    typeOf<K>()
    Array<K>(1) { x }
}

fun test(a: Any) {
    if (a is A && a is B) {
        select(a)
    }
}

fun box(): String {
    test(object : A, B {})
    test(object : A {})
    test(object : B {})
    test(object {})
    test(Any())
    return "OK"
}
