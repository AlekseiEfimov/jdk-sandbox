/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package jdk.jfr.api.consumer.recordingstream;

import java.util.concurrent.CountDownLatch;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.consumer.RecordingStream;

/**
 * @test
 * @summary Tests RecordingStream::onEvent(...)
 * @key jfr
 * @requires vm.hasJFR
 * @library /test/lib
 * @run main/othervm jdk.jfr.api.consumer.recordingstream.TestOnEvent
 */
public class TestOnEvent {

    @Name("A")
    static class EventA extends Event {
    }

    @Name("A")
    static class EventAlsoA extends Event {
    }

    @Name("C")
    static class EventC extends Event {
    }

    public static void main(String... args) throws Exception {
        testOnEventNull();
        testOnEvent();
        testNamedEvent();
        testTwoEventWithSameName();
    }

    private static void testOnEventNull() {
        try (RecordingStream rs = new RecordingStream()) {
           try {
               rs.onEvent(null);
               throw new AssertionError("Expected NullPointerException from onEvent(null)");
           } catch (NullPointerException npe) {
               // OK; as expected
           }
           try {
               rs.onEvent("A", null);
               throw new AssertionError("Expected NullPointerException from onEvent(\"A\", null)");

           } catch (NullPointerException npe) {
               // OK; as expected
           }
           try {
               String s = null;
               rs.onEvent(s, null);
               throw new AssertionError("Expected NullPointerException from onEvent(null, null)");
           } catch (NullPointerException npe) {
               // OK; as expected
           }
        }
     }

    private static void testTwoEventWithSameName() throws Exception {
        CountDownLatch eventA = new CountDownLatch(2);
        try (RecordingStream r = new RecordingStream()) {
            r.onEvent("A", e -> {
                System.out.println("testTwoEventWithSameName" +  e);
                eventA.countDown();
            });
            r.startAsync();
            EventA a1 = new EventA();
            a1.commit();
            EventAlsoA a2 = new EventAlsoA();
            a2.commit();
            eventA.await();
        }
    }

    private static void testNamedEvent() throws Exception {
        try (RecordingStream r = new RecordingStream()) {
            CountDownLatch eventA = new CountDownLatch(1);
            CountDownLatch eventC = new CountDownLatch(1);
            r.onEvent("A", e -> {
                System.out.println("TestNamedEvent:" + e);
                if (e.getEventType().getName().equals("A")) {
                    eventA.countDown();
                }
            });
            r.onEvent("C", e -> {
                System.out.println("TestNamedEvent:" + e);
                if (e.getEventType().getName().equals("C")) {
                    eventC.countDown();
                }
            });

            r.startAsync();
            EventA a = new EventA();
            a.commit();
            EventC c = new EventC();
            c.commit();
            eventA.await();
            eventC.await();
        }
    }

    private static void testOnEvent() throws Exception {
        try (RecordingStream r = new RecordingStream()) {
            CountDownLatch event = new CountDownLatch(1);
            r.onEvent(e -> {
                event.countDown();
            });
            r.startAsync();
            EventA a = new EventA();
            a.commit();
            event.await();
        }
    }
}
