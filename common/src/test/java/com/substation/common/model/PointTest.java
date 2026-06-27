package com.substation.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointTest {

    @Test
    void manhattanDistance() {
        Point a = new Point(5, 10);
        Point b = new Point(10, 20);

        assertEquals(15, a.manhattanDistance(b));
        assertEquals(0, a.manhattanDistance(new Point(5, 10)));
    }

    @Test
    void jsonRoundTrip() {
        Point original = new Point(5, 10);
        String json = original.toJson();
        Point parsed = Point.fromJson(json);

        assertEquals(original, parsed);
    }

    @Test
    void equalsAndHashCode() {
        Point a = new Point(5, 10);
        Point b = new Point(5, 10);
        Point c = new Point(10, 5);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
