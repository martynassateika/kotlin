// WITH_RUNTIME
// KJS_WITH_FULL_RUNTIME

// See KT-37128

import kotlin.reflect.typeOf

@OptIn(kotlin.ExperimentalStdlibApi::class)
inline fun <reified T> T.causeBug() {
    val x = this
    x is T
    x as T
    T::class
    typeOf<T>()
    Array<T>(1) { x }
}

interface SomeToImplement<SELF_TVAR>

class Y : SomeToImplement<Y>

class Something<T> where T: SomeToImplement<T> {
    fun op() = causeBug()
}

// TODO check real effects to fix the behavior when we reach consensus
//  and to be sure that something is not dropped by optimizations.

//@OptIn(kotlin.ExperimentalStdlibApi::class)
//inline fun <reified K> select(x: K, y: K) {
//    x is K
//    x as K
//    K::class
//    typeOf<K>()
//    Array<K>(1) { x }
//}

fun box(): String {
    Something<Y>().op()
    return "OK"
}
