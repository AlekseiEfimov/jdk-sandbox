/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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

/* @test
 * @summary Basic functional test of Optional
 * @author Mike Duigou
 * @run testng Basic
 */

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

import static org.testng.Assert.*;
import org.testng.annotations.Test;


public class Basic {

    /**
     * Asserts that the runnable throws an exception of the given type.
     * Fails if no exception is thrown, or if an exception of the wrong
     * type is thrown. This is used instead of @Test(expectedExceptions)
     * because that can be applied only at the granularity of a test.
     */
    void assertThrows(Runnable r, Class<? extends Exception> clazz) {
        try {
            r.run();
            fail();
        } catch (Exception ex) {
            assertTrue(clazz.isInstance(ex));
        }
    }

    /**
     * Checks a block of assertions over an empty Optional.
     */
    void checkEmpty(Optional<String> empty) {
        assertTrue(empty.equals(Optional.empty()));
        assertTrue(Optional.empty().equals(empty));
        assertFalse(empty.equals(Optional.of("")));
        assertFalse(Optional.of("").equals(empty));
        assertFalse(empty.isPresent());
        assertEquals(empty.hashCode(), 0);
        assertEquals(empty.orElse("x"), "x");
        assertEquals(empty.orElseGet(() -> "y"), "y");

        assertThrows(() -> empty.get(), NoSuchElementException.class);
        assertThrows(() -> empty.orElseThrow(), NoSuchElementException.class);
        assertThrows(() -> empty.orElseThrow(ObscureException::new), ObscureException.class);

        var b = new AtomicBoolean();
        empty.ifPresent(s -> b.set(true));
        assertFalse(b.get());

        var b1 = new AtomicBoolean(false);
        var b2 = new AtomicBoolean(false);
        empty.ifPresentOrElse(s -> b1.set(true), () -> b2.set(true));
        assertFalse(b1.get());
        assertTrue(b2.get());
    }

    /**
     * Checks a block of assertions over an Optional that is expected to
     * have a particular value present.
     */
    void checkPresent(Optional<String> opt, String expected) {
        assertFalse(opt.equals(Optional.empty()));
        assertFalse(Optional.empty().equals(opt));
        assertTrue(opt.equals(Optional.of(expected)));
        assertTrue(Optional.of(expected).equals(opt));
        assertFalse(opt.equals(Optional.of("unexpected")));
        assertFalse(Optional.of("unexpected").equals(opt));
        assertTrue(opt.isPresent());
        assertEquals(opt.hashCode(), expected.hashCode());
        assertEquals(opt.orElse("unexpected"), expected);
        assertEquals(opt.orElseGet(() -> "unexpected"), expected);

        assertEquals(opt.get(), expected);
        assertEquals(opt.orElseThrow(), expected);
        assertEquals(opt.orElseThrow(ObscureException::new), expected);

        var b = new AtomicBoolean(false);
        opt.ifPresent(s -> b.set(true));
        assertTrue(b.get());

        var b1 = new AtomicBoolean(false);
        var b2 = new AtomicBoolean(false);
        opt.ifPresentOrElse(s -> b1.set(true), () -> b2.set(true));
        assertTrue(b1.get());
        assertFalse(b2.get());
    }

    @Test
    public void testEmpty() {
        checkEmpty(Optional.empty());
    }

    @Test
    public void testOfNull() {
        assertThrows(() -> Optional.of(null), NullPointerException.class);
    }

    @Test
    public void testOfPresent() {
        checkPresent(Optional.of("xyzzy"), "xyzzy");
    }

    @Test
    public void testOfNullableNull() {
        checkEmpty(Optional.ofNullable(null));
    }

    @Test
    public void testOfNullablePresent() {
        checkPresent(Optional.of("xyzzy"), "xyzzy");
    }

    @Test
    public void testFilterEmpty() {
        checkEmpty(Optional.<String>empty().filter(s -> { fail(); return true; }));
    }

    @Test
    public void testFilterFalse() {
        checkEmpty(Optional.of("xyzzy").filter(s -> s.equals("plugh")));
    }

    @Test
    public void testFilterTrue() {
        checkPresent(Optional.of("xyzzy").filter(s -> s.equals("xyzzy")), "xyzzy");
    }

    @Test
    public void testMapEmpty() {
        checkEmpty(Optional.empty().map(s -> { fail(); return ""; }));
    }

    @Test
    public void testMapPresent() {
        checkPresent(Optional.of("xyzzy").map(s -> s.replace("xyzzy", "plugh")), "plugh");
    }

    @Test
    public void testFlatMapEmpty() {
        checkEmpty(Optional.empty().flatMap(s -> { fail(); return Optional.of(""); }));
    }

    @Test
    public void testFlatMapPresentReturnEmpty() {
        checkEmpty(Optional.of("xyzzy")
                           .flatMap(s -> { assertEquals(s, "xyzzy"); return Optional.empty(); }));
    }

    @Test
    public void testFlatMapPresentReturnPresent() {
        checkPresent(Optional.of("xyzzy")
                             .flatMap(s -> { assertEquals(s, "xyzzy"); return Optional.of("plugh"); }),
                     "plugh");
    }

    @Test
    public void testOrEmptyEmpty() {
        checkEmpty(Optional.<String>empty().or(() -> Optional.empty()));
    }

    @Test
    public void testOrEmptyPresent() {
        checkPresent(Optional.<String>empty().or(() -> Optional.of("plugh")), "plugh");
    }

    @Test
    public void testOrPresentDontCare() {
        checkPresent(Optional.of("xyzzy").or(() -> { fail(); return Optional.of("plugh"); }), "xyzzy");
    }

    @Test
    public void testStreamEmpty() {
        assertEquals(Optional.empty().stream().collect(toList()), List.of());
    }

    @Test
    public void testStreamPresent() {
        assertEquals(Optional.of("xyzzy").stream().collect(toList()), List.of("xyzzy"));
    }

    private static class ObscureException extends RuntimeException { }
}
