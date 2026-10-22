package io.github.hyperisland.xposed.hook.SystemUI.geometry

import org.junit.Assert.*
import org.junit.Test

class IslandWidthPerformanceTest {
    open class Data(private val payload: Any) { open fun getExtras(): Any = payload }
    open class Content(var data: Data?) { open fun getCurrentIslandData(): Data? = data }
    class OtherContent(data: Data?) : Content(data) {
        override fun getCurrentIslandData(): Data? = data
    }
    class ErasedContent(var data: Any?) { fun getCurrentIslandData(): Any? = data }

    @Test fun reflectionMetadataIsResolvedOnceAndValuesStayLive() {
        var lookups = 0
        val access = IslandWidthAccess(Content::class.java) { type, name ->
            lookups++; type.getMethod(name)
        }
        val content = Content(Data("first"))
        repeat(200) { assertEquals("first", access.extras(content)) }
        assertEquals(2, lookups)
        content.data = Data("second")
        assertEquals("second", access.extras(content))
        content.data = null
        assertNull(access.extras(content))
        assertEquals(2, lookups)
    }

    @Test fun baseMethodStillDispatchesToRuntimeOverride() {
        val access = IslandWidthAccess(Content::class.java)
        assertEquals("child", access.extras(OtherContent(Data("child"))))
    }

    @Test fun erasedReturnTypeRetainsRuntimeLookupFallback() {
        val access = IslandWidthAccess(ErasedContent::class.java)
        val content = ErasedContent(Data("legacy"))
        assertEquals("legacy", access.extras(content))
        content.data = null
        assertNull(access.extras(content))
    }

    @Test fun accessorNeverStoresAContentOrDataInstance() {
        val fields = IslandWidthAccess::class.java.declaredFields
        val (metadata, instanceFields) = fields.partition { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        // Compose adds a static primitive stability marker in the Android build.
        // Do not ignore synthetic/reference fields: every instance field is checked.
        assertTrue(metadata.all { it.name == "\$stable" && it.type == Int::class.javaPrimitiveType })
        assertEquals(setOf("currentData", "extras"), instanceFields.map { it.name }.toSet())
        assertTrue(instanceFields.all { it.type == java.lang.reflect.Method::class.java })
    }

    @Test fun sameCalculationOwnerReusesEvenExplicitSystemWidth() {
        val scope = IslandWidthScope()
        val owner = Any()
        scope.withWidth(0, owner) {
            repeat(100) { assertEquals(0, scope.currentFor(owner)) }
            assertNull(scope.currentFor(Any()))
            assertNull(scope.currentFor(null))
        }
        assertNull(scope.currentFor(owner))
    }

    @Test fun nestedOtherAppAndFailureRestoreParentOnly() {
        val scope = IslandWidthScope()
        val first = Any(); val second = Any()
        scope.withWidth(320, first) {
            runCatching {
                scope.withWidth(280, second) {
                    assertNull(scope.currentFor(first))
                    assertEquals(280, scope.currentFor(second))
                    error("native failed")
                }
            }
            assertEquals(320, scope.currentFor(first))
            assertNull(scope.currentFor(second))
        }
        assertNull(scope.current()); assertNull(scope.currentFor(first))
    }

    @Test fun nextCalculationForSameOwnerReadsNewWidth() {
        val scope = IslandWidthScope(); val owner = Any()
        scope.withWidth(320, owner) { assertEquals(320, scope.currentFor(owner)) }
        scope.withWidth(300, owner) { assertEquals(300, scope.currentFor(owner)) }
        assertNull(scope.currentFor(owner))
    }

    @Test fun ownerIdentityDoesNotCrossThreads() {
        val scope = IslandWidthScope(); val owner = Any()
        scope.withWidth(320, owner) {
            var result: Int? = -1
            Thread { result = scope.currentFor(owner) }.apply { start(); join() }
            assertNull(result)
        }
    }
}
