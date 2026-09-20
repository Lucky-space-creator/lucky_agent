package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 条件表达式求值器（流转条件控制）。
 *
 * <p>采用自实现的递归下降解析器，支持安全子集，<b>不依赖 ScriptEngine</b>，避免脚本注入风险：</p>
 * <pre>
 *   expr   := or
 *   or     := and ( '||' and )*
 *   and    := not ( '&&' not )*
 *   not    := '!' not | comparison
 *   cmp    := primary ( ('=='|'!='|'>='|'<='|'>'|'<') primary )?
 *   primary:= '(' expr ')' | number | 'string' | true | false | null | path
 * </pre>
 * 变量通过与 {@link MappingEvaluator} 一致的点路径在上文作用域中解析；
 * 示例：{@code "amount > 100 && status == 'PAID'"}。
 */
public class ConditionEvaluator {

    private final MappingEvaluator mappingEvaluator;

    public ConditionEvaluator() {
        this(new MappingEvaluator());
    }

    public ConditionEvaluator(MappingEvaluator mappingEvaluator) {
        this.mappingEvaluator = mappingEvaluator;
    }

    /** 求值为布尔；空表达式视为无条件（true）。 */
    public boolean evaluate(String expression, VariableScope scope) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        List<Token> tokens = new Lexer(expression).tokenize();
        return toBoolean(new Parser(tokens, scope).parseExpression());
    }

    // ---------------- 求值语义 ----------------

    private static boolean toBoolean(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isBlank() && !"false".equalsIgnoreCase(s);
        }
        return true;
    }

    private static boolean equalsValue(Object l, Object r) {
        if (l instanceof Number a && r instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (l == null || r == null) {
            return l == r;
        }
        if (l.getClass().equals(r.getClass())) {
            return l.equals(r);
        }
        return String.valueOf(l).equals(String.valueOf(r));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareOrder(Object l, Object r) {
        if (l instanceof Number a && r instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (l == null || r == null) {
            throw new WorkflowException("条件比较的运算数为 null");
        }
        if (l instanceof Comparable && l.getClass().equals(r.getClass())) {
            return ((Comparable) l).compareTo(r);
        }
        return String.valueOf(l).compareTo(String.valueOf(r));
    }

    // ---------------- 词法 ----------------

    private enum T {
        IDENT, NUMBER, STRING, BOOL, NULL, LPAREN, RPAREN,
        EQ, NEQ, GT, GTE, LT, LTE, AND, OR, NOT, END
    }

    private record Token(T type, String text) {
    }

    private static final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '(') {
                    out.add(new Token(T.LPAREN, "("));
                    pos++;
                } else if (c == ')') {
                    out.add(new Token(T.RPAREN, ")"));
                    pos++;
                } else if (c == '&' && peek(1) == '&') {
                    out.add(new Token(T.AND, "&&"));
                    pos += 2;
                } else if (c == '|' && peek(1) == '|') {
                    out.add(new Token(T.OR, "||"));
                    pos += 2;
                } else if (c == '=' && peek(1) == '=') {
                    out.add(new Token(T.EQ, "=="));
                    pos += 2;
                } else if (c == '!' && peek(1) == '=') {
                    out.add(new Token(T.NEQ, "!="));
                    pos += 2;
                } else if (c == '>' && peek(1) == '=') {
                    out.add(new Token(T.GTE, ">="));
                    pos += 2;
                } else if (c == '<' && peek(1) == '=') {
                    out.add(new Token(T.LTE, "<="));
                    pos += 2;
                } else if (c == '>') {
                    out.add(new Token(T.GT, ">"));
                    pos++;
                } else if (c == '<') {
                    out.add(new Token(T.LT, "<"));
                    pos++;
                } else if (c == '!') {
                    out.add(new Token(T.NOT, "!"));
                    pos++;
                } else if (c == '\'' || c == '"') {
                    out.add(new Token(T.STRING, readString(c)));
                } else if (Character.isDigit(c) || (c == '-' && Character.isDigit(peek(1)))) {
                    out.add(new Token(T.NUMBER, readNumber()));
                } else if (Character.isLetter(c) || c == '_') {
                    out.add(readIdentifier());
                } else {
                    throw new WorkflowException("条件表达式含非法字符: '" + c + "'");
                }
            }
            out.add(new Token(T.END, ""));
            return out;
        }

        private String readString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                sb.append(src.charAt(pos++));
            }
            if (pos >= src.length()) {
                throw new WorkflowException("条件表达式字符串未闭合: " + src);
            }
            pos++;
            return sb.toString();
        }

        private String readNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private Token readIdentifier() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            String id = src.substring(start, pos);
            if (id.equalsIgnoreCase("true") || id.equalsIgnoreCase("false")) {
                return new Token(T.BOOL, id.toLowerCase());
            }
            if (id.equalsIgnoreCase("null")) {
                return new Token(T.NULL, id);
            }
            return new Token(T.IDENT, id);
        }

        private char peek(int offset) {
            int i = pos + offset;
            return i < src.length() ? src.charAt(i) : '\0';
        }
    }

    // ---------------- 语法/求值 ----------------

    private final class Parser {
        private final List<Token> tokens;
        private final VariableScope scope;
        private int idx;

        Parser(List<Token> tokens, VariableScope scope) {
            this.tokens = tokens;
            this.scope = scope;
        }

        Object parseExpression() {
            return parseOr();
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (check(T.OR)) {
                advance();
                Object right = parseAnd();
                left = toBoolean(left) || toBoolean(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (check(T.AND)) {
                advance();
                Object right = parseNot();
                left = toBoolean(left) && toBoolean(right);
            }
            return left;
        }

        private Object parseNot() {
            if (check(T.NOT)) {
                advance();
                return !toBoolean(parseNot());
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            T op = peekType();
            if (op == T.EQ || op == T.NEQ || op == T.GT || op == T.GTE || op == T.LT || op == T.LTE) {
                advance();
                Object right = parsePrimary();
                return switch (op) {
                    case EQ -> equalsValue(left, right);
                    case NEQ -> !equalsValue(left, right);
                    case GT -> compareOrder(left, right) > 0;
                    case GTE -> compareOrder(left, right) >= 0;
                    case LT -> compareOrder(left, right) < 0;
                    case LTE -> compareOrder(left, right) <= 0;
                    default -> throw new WorkflowException("未知比较运算符: " + op);
                };
            }
            return left;
        }

        private Object parsePrimary() {
            Token t = advance();
            return switch (t.type()) {
                case LPAREN -> {
                    Object v = parseExpression();
                    expect(T.RPAREN);
                    yield v;
                }
                case NUMBER -> Double.parseDouble(t.text());
                case STRING -> t.text();
                case BOOL -> Boolean.parseBoolean(t.text());
                case NULL -> null;
                case IDENT -> mappingEvaluator.resolve(t.text(), scope);
                default -> throw new WorkflowException("条件表达式语法错误，意外符号: '" + t.text() + "'");
            };
        }

        private boolean check(T type) {
            return peekType() == type;
        }

        private T peekType() {
            return tokens.get(idx).type();
        }

        private Token advance() {
            return tokens.get(idx++);
        }

        private void expect(T type) {
            Token t = advance();
            if (t.type() != type) {
                throw new WorkflowException("条件表达式语法错误，期望 " + type + " 实际 " + t.type());
            }
        }
    }

    /** 供外部快速灰度判断：表达式是否为空。 */
    public static boolean isEmpty(String expr) {
        return Objects.isNull(expr) || expr.isBlank();
    }
}
