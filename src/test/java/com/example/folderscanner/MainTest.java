package com.example.folderscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Locks in Main's utility-class shape: only the static main(String[]) entry point should be
 * reachable, so a no-arg constructor must exist (Java requires one) but must be private to
 * stop any caller from instantiating a class that has no instance state worth holding.
 */
final class MainTest {

    @Test
    void main_class_has_exactly_one_constructor_and_it_is_private() throws Exception {
        Constructor<?>[] ctors = Main.class.getDeclaredConstructors();
        assertEquals(1, ctors.length,
                "Main should expose exactly one constructor (the no-arg sealing one)");

        Constructor<?> ctor = ctors[0];
        assertEquals(0, ctor.getParameterCount(), "Main's constructor should take no arguments");
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "Main's constructor must be private to forbid instantiation");
    }
}
