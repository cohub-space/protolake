package space.cohub.vdp.protolake.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * The import statements of a .proto file, read the way protoc reads them.
 *
 * <p>Comments and the contents of string literals are skipped, single and
 * double quotes both delimit strings, adjacent string literals concatenate,
 * and only file-level statements count. A line-oriented regular expression
 * gets each of these wrong: it misses {@code import 'a.proto';} and an import
 * written after another statement on the same line, which protoc accepts, and
 * it finds one inside a block comment, which protoc ignores.
 */
final class ProtoImports {

    /** An import statement: the imported path, and the 1-based position of its {@code import} keyword. */
    record Import(String path, int line, int column) {
    }

    private ProtoImports() {
    }

    /** The file's imports, plain, public and weak, in source order. */
    static List<Import> parse(String source) {
        List<Token> tokens = new Lexer(source).tokens();
        List<Import> imports = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isSymbol('{')) {
                depth++;
            } else if (token.isSymbol('}')) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && token.isWord("import") && startsStatement(tokens, i)) {
                Import parsed = importAt(tokens, i);
                if (parsed != null) {
                    imports.add(parsed);
                }
            }
        }
        return imports;
    }

    /** A file-level statement starts the file, or follows a {@code ;} or a closing brace. */
    private static boolean startsStatement(List<Token> tokens, int index) {
        if (index == 0) {
            return true;
        }
        Token previous = tokens.get(index - 1);
        return previous.isSymbol(';') || previous.isSymbol('}');
    }

    /** {@code import [public | weak] "path" ["more"...] ;}, or null when the tokens are something else. */
    private static Import importAt(List<Token> tokens, int index) {
        int next = index + 1;
        if (next < tokens.size() && (tokens.get(next).isWord("public") || tokens.get(next).isWord("weak"))) {
            next++;
        }
        StringBuilder path = new StringBuilder();
        int strings = 0;
        while (next < tokens.size() && tokens.get(next).kind() == Kind.STRING) {
            path.append(tokens.get(next).text());
            next++;
            strings++;
        }
        if (strings == 0 || next >= tokens.size() || !tokens.get(next).isSymbol(';')) {
            return null;
        }
        Token keyword = tokens.get(index);
        return new Import(path.toString(), keyword.line(), keyword.column());
    }

    private enum Kind { WORD, STRING, SYMBOL }

    /** A word, a string literal's decoded value, or one other character, at its 1-based start. */
    private record Token(Kind kind, String text, int line, int column) {
        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }

        boolean isSymbol(char symbol) {
            return kind == Kind.SYMBOL && text.length() == 1 && text.charAt(0) == symbol;
        }
    }

    /** protoc's lexical rules, as far as finding imports needs them. */
    private static final class Lexer {
        private final String source;
        private int position;
        private int line = 1;
        private int column = 1;

        Lexer(String source) {
            this.source = source;
        }

        List<Token> tokens() {
            List<Token> tokens = new ArrayList<>();
            while (position < source.length()) {
                char c = source.charAt(position);
                if (Character.isWhitespace(c) || c == '﻿') {
                    advance();
                } else if (c == '/' && peek(1) == '/') {
                    while (position < source.length() && source.charAt(position) != '\n') {
                        advance();
                    }
                } else if (c == '/' && peek(1) == '*') {
                    advance();
                    advance();
                    while (position < source.length() && !(source.charAt(position) == '*' && peek(1) == '/')) {
                        advance();
                    }
                    if (position < source.length()) {
                        advance();
                        advance();
                    }
                } else {
                    int startLine = line;
                    int startColumn = column;
                    if (c == '"' || c == '\'') {
                        tokens.add(new Token(Kind.STRING, string(c), startLine, startColumn));
                    } else if (Character.isLetter(c) || c == '_') {
                        int start = position;
                        while (position < source.length()
                                && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '_')) {
                            advance();
                        }
                        tokens.add(new Token(Kind.WORD, source.substring(start, position), startLine, startColumn));
                    } else {
                        advance();
                        tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), startLine, startColumn));
                    }
                }
            }
            return tokens;
        }

        /** A string literal's value, with its escapes decoded; it ends at its quote or at the end of the line. */
        private String string(char quote) {
            advance();
            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position);
                if (c == quote) {
                    advance();
                    break;
                }
                if (c == '\n') {
                    break;
                }
                advance();
                if (c != '\\' || position >= source.length()) {
                    value.append(c);
                    continue;
                }
                char escape = source.charAt(position);
                advance();
                switch (escape) {
                    case 'a' -> value.append('\u0007');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'v' -> value.append('\u000B');
                    case 'x', 'X' -> value.appendCodePoint(number(16, 2, 0));
                    case 'u' -> value.appendCodePoint(number(16, 4, 0));
                    case 'U' -> value.appendCodePoint(number(16, 8, 0));
                    default -> {
                        if (escape >= '0' && escape <= '7') {
                            value.appendCodePoint(number(8, 2, escape - '0'));
                        } else {
                            value.append(escape);
                        }
                    }
                }
            }
            return value.toString();
        }

        /** Up to maxDigits more digits in the radix, accumulated onto value. */
        private int number(int radix, int maxDigits, int value) {
            for (int i = 0; i < maxDigits && position < source.length(); i++) {
                int digit = Character.digit(source.charAt(position), radix);
                if (digit < 0) {
                    break;
                }
                value = value * radix + digit;
                advance();
            }
            return Character.isValidCodePoint(value) ? value : '?';
        }

        private char peek(int offset) {
            int index = position + offset;
            return index < source.length() ? source.charAt(index) : '\0';
        }

        private void advance() {
            if (source.charAt(position) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            position++;
        }
    }
}
