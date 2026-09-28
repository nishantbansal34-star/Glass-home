package com.nishant.glasshome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, safe arithmetic evaluator: + - × ÷ ^ % ( ) with decimals. */
public class Calc {
    private final String s;
    private int pos;

    private Calc(String s) { this.s = s; }

    public static Double eval(String expr) {
        if (expr == null) return null;
        String e = expr.replace('×', '*').replace('÷', '/').replace('−', '-').replace(",", "").replace(" ", "")
                .replace("x", "*").replace("X", "*");
        if (e.isEmpty()) return null;
        try {
            Calc c = new Calc(e);
            double v = c.expr();
            if (c.pos != e.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (Exception ex) {
            return null;
        }
    }

    private double expr() {
        double v = term();
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != '+' && c != '-') break;
            pos++;
            int start = pos;
            double t = term();
            // "1200 + 18%" means 1200 plus 18% of 1200, like a shop calculator
            if (s.substring(start, pos).matches("[0-9.]+%")) t = v * t;
            v = c == '+' ? v + t : v - t;
        }
        return v;
    }

    private double term() {
        double v = power();
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '*') { pos++; v *= power(); }
            else if (c == '/') { pos++; double d = power(); if (d == 0) throw new ArithmeticException(); v /= d; }
            else break;
        }
        return v;
    }

    private double power() {
        double v = unary();
        if (pos < s.length() && s.charAt(pos) == '^') { pos++; v = Math.pow(v, power()); }
        return v;
    }

    private double unary() {
        if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -unary(); }
        if (pos < s.length() && s.charAt(pos) == '+') { pos++; return unary(); }
        double v = atom();
        while (pos < s.length() && s.charAt(pos) == '%') { pos++; v /= 100.0; }
        return v;
    }

    private double atom() {
        if (pos >= s.length()) throw new IllegalArgumentException();
        char c = s.charAt(pos);
        if (c == '(') {
            pos++;
            double v = expr();
            if (pos < s.length() && s.charAt(pos) == ')') pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        if (start == pos) throw new IllegalArgumentException();
        return Double.parseDouble(s.substring(start, pos));
    }

    /** Formats like 1,23,456.78 (Indian grouping) or plain if requested. */
    public static String format(double v, boolean grouping) {
        BigDecimal bd = new BigDecimal(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = bd.toPlainString();
        if (!grouping) return plain;
        boolean neg = plain.startsWith("-");
        if (neg) plain = plain.substring(1);
        String intPart = plain, frac = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) { intPart = plain.substring(0, dot); frac = plain.substring(dot); }
        StringBuilder sb = new StringBuilder();
        int n = intPart.length();
        if (n <= 3) sb.append(intPart);
        else {
            String last3 = intPart.substring(n - 3);
            String rest = intPart.substring(0, n - 3);
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                if (i > 0 && (rest.length() - i) % 2 == 0) r.append(',');
                r.append(rest.charAt(i));
            }
            sb.append(r).append(',').append(last3);
        }
        return (neg ? "-" : "") + sb + frac;
    }

    private static final Pattern INLINE = Pattern.compile("([0-9.,()+\\-*/×÷x^% ]*[0-9)%])\\s*=$");

    /** Detects "250*12=" at the end of typed text and returns the result, else null. */
    public static String inlineResult(CharSequence before) {
        if (before == null) return null;
        String t = before.toString();
        if (t.length() > 60) t = t.substring(t.length() - 60);
        Matcher m = INLINE.matcher(t);
        if (!m.find()) return null;
        String expr = m.group(1).trim();
        // needs at least one operator so plain "5=" doesn't trigger
        if (!expr.matches(".*[0-9)%]\\s*[+\\-*/×÷x^].*") && !expr.contains("%")) return null;
        // trim leading words that are not part of the expression
        int k = 0;
        while (k < expr.length() && !(Character.isDigit(expr.charAt(k)) || expr.charAt(k) == '(' || expr.charAt(k) == '-')) k++;
        Double v = eval(expr.substring(k));
        return v == null ? null : format(v, false);
    }
}
