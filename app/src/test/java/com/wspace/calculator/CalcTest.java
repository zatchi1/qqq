package com.wspace.calculator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class CalcTest {

    private static String ev(String e) { return Calc.evaluateToString(e); }

    @Test public void precedenceMulBeforeAdd() { assertEquals("13", ev("8+4*2-3")); }
    @Test public void displayGlyphsAccepted() { assertEquals("13", ev("8+4×2−3")); }
    @Test public void divisionPrecedence() { assertEquals("11", ev("5+12/2")); }
    @Test public void leftAssociative() { assertEquals("1", ev("10-5-4")); assertEquals("2", ev("16/4/2")); }
    @Test public void decimalsAreExact() { assertEquals("0.3", ev("0.1+0.2")); }
    @Test public void divisionRounds() { assertEquals("0.333333333333", ev("1/3")); }
    @Test public void unaryMinus() { assertEquals("-3", ev("-5+2")); assertEquals("-6", ev("3*-2")); assertEquals("5", ev("--5")); }
    @Test public void percent() { assertEquals("0.5", ev("50%")); assertEquals("10", ev("200*5%")); }
    @Test public void bigNumbers() { assertEquals("1E15", ev("1000000*1000000000")); }
    @Test public void zeroResult() { assertEquals("0", ev("5-5")); assertEquals("0", ev("0*7")); }
    @Test public void divideByZero() { assertThrows(ArithmeticException.class, () -> ev("4/0")); }
    @Test public void malformed() {
        assertThrows(IllegalArgumentException.class, () -> ev("*3"));
        assertThrows(IllegalArgumentException.class, () -> ev("3..4+1"));
        assertThrows(IllegalArgumentException.class, () -> ev("."));
    }

    @Test public void countOperations() {
        assertEquals(0, Calc.countOperations("42"));
        assertEquals(1, Calc.countOperations("1+2"));
        assertEquals(3, Calc.countOperations("8+4*2-3"));
        assertEquals(3, Calc.countOperations("8+4×2−3"));
        assertEquals(1, Calc.countOperations("-5+2"));      // leading sign is not an operation
        assertEquals(1, Calc.countOperations("3*-2"));      // sign after operator is not an operation
        assertEquals(1, Calc.countOperations("50%+1"));     // percent is not an operation
    }

    @Test public void stripTrailing() {
        assertEquals("5", Calc.stripTrailingOperators("5+"));
        assertEquals("5", Calc.stripTrailingOperators("5*-"));
        assertEquals("", Calc.stripTrailingOperators("-"));
    }
}
