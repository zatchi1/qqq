package com.wspace.calculator;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Pure-Java expression engine (no Android dependencies, unit-testable).
 *
 * Internal alphabet: digits, '.', '+', '-', '*', '/', '%' (postfix percent).
 * The display glyphs × ÷ − are also accepted and normalised.
 * Precedence: unary minus > % > * / > + -  (left associative).
 */
public final class Calc {

    private Calc() {}

    private static final MathContext DIV = new MathContext(20);
    private static final MathContext OUT = new MathContext(12);
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    public static String normalize(String s) {
        return s.replace('×', '*').replace('÷', '/').replace('−', '-');
    }

    public static boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    /** Removes dangling operators at the end, e.g. "5+" -> "5". */
    public static String stripTrailingOperators(String expr) {
        String s = normalize(expr);
        int end = s.length();
        while (end > 0 && isOperator(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /**
     * Number of binary operations (+ - * /). A minus that acts as a sign
     * (at the start or right after another operator) is not counted, and
     * neither is the postfix percent sign.
     */
    public static int countOperations(String expr) {
        String s = normalize(expr);
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isOperator(c)) continue;
            if (i == 0) continue;
            char prev = s.charAt(i - 1);
            if (Character.isDigit(prev) || prev == '.' || prev == '%') count++;
        }
        return count;
    }

    /** Evaluates and returns the raw BigDecimal result. */
    public static BigDecimal evaluate(String expr) {
        Parser p = new Parser(normalize(expr));
        BigDecimal v = p.parseExpression();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("Unexpected '" + p.s.charAt(p.pos) + "'");
        return v;
    }

    /** Evaluates and formats for display (ASCII minus). Throws on error. */
    public static String evaluateToString(String expr) {
        return format(evaluate(expr));
    }

    public static String format(BigDecimal v) {
        BigDecimal r = v.round(OUT);
        if (r.signum() == 0) return "0";
        r = r.stripTrailingZeros();
        BigDecimal abs = r.abs();
        if (abs.compareTo(new BigDecimal("1E15")) >= 0 || abs.compareTo(new BigDecimal("1E-9")) < 0) {
            return r.toString().replace("E+", "E");
        }
        return r.toPlainString();
    }

    /** Converts internal text to display text (× ÷ −). */
    public static String toDisplay(String internal) {
        return internal.replace('*', '×').replace('/', '÷').replace('-', '−');
    }

    // ------------------------------------------------------------------
    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s; }

        BigDecimal parseExpression() {
            BigDecimal v = parseTerm();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v = v.add(parseTerm()); }
                else if (c == '-') { pos++; v = v.subtract(parseTerm()); }
                else break;
            }
            return v;
        }

        BigDecimal parseTerm() {
            BigDecimal v = parseUnary();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v = v.multiply(parseUnary()); }
                else if (c == '/') {
                    pos++;
                    BigDecimal d = parseUnary();
                    if (d.signum() == 0) throw new ArithmeticException("Division by zero");
                    v = v.divide(d, DIV);
                } else break;
            }
            return v;
        }

        BigDecimal parseUnary() {
            if (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '-') { pos++; return parseUnary().negate(); }
                if (c == '+') { pos++; return parseUnary(); }
            }
            return parsePostfix();
        }

        BigDecimal parsePostfix() {
            BigDecimal v = parseNumber();
            while (pos < s.length() && s.charAt(pos) == '%') {
                pos++;
                v = v.divide(HUNDRED, DIV);
            }
            return v;
        }

        BigDecimal parseNumber() {
            int start = pos;
            boolean dot = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' && !dot) { dot = true; pos++; }
                else break;
            }
            if (start == pos) throw new IllegalArgumentException("Number expected");
            String t = s.substring(start, pos);
            if (t.equals(".")) throw new IllegalArgumentException("Bad number");
            return new BigDecimal(t);
        }
    }
}
