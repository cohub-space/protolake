package space.cohub.vdp.protolake.pipeline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Like protoc, this reads the file as bytes, not text. A string literal is
 * a byte string: a {@code \x} or octal escape is one raw byte, adjacent
 * literals join byte by byte, and the imported path is the joined bytes read
 * as UTF-8, so {@code "lib/\303\251.proto"} names {@code lib/é.proto}. Bytes
 * outside string literals are never decoded: a comment in another encoding
 * is skipped as protoc skips it. Positions count bytes from 1, with a tab
 * advancing to the next multiple of 8, as protoc counts columns.
 */
final class ProtoImports {

    /** An import statement: the imported path, and the 1-based position of its {@code import} keyword. */
    record Import(String path, int line, int column) {
    }

    private static final int TAB_WIDTH = 8;

    private ProtoImports() {
    }

    /** The imports of a .proto file's bytes, plain, public and weak, in source order. */
    static List<Import> parse(byte[] source) {
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

    /** The imports of a .proto file's text, encoded as UTF-8. */
    static List<Import> parse(String source) {
        return parse(source.getBytes(StandardCharsets.UTF_8));
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
        ByteArrayOutputStream path = new ByteArrayOutputStream();
        int strings = 0;
        while (next < tokens.size() && tokens.get(next).kind() == Kind.STRING) {
            path.writeBytes(tokens.get(next).bytes());
            next++;
            strings++;
        }
        if (strings == 0 || next >= tokens.size() || !tokens.get(next).isSymbol(';')) {
            return null;
        }
        Token keyword = tokens.get(index);
        return new Import(path.toString(StandardCharsets.UTF_8), keyword.line(), keyword.column());
    }

    private enum Kind { WORD, STRING, SYMBOL }

    /**
     * A word, a string literal, or one other byte, at its 1-based start. A
     * word or symbol's bytes are ASCII; a string's are its decoded value.
     */
    private record Token(Kind kind, byte[] bytes, int line, int column) {
        boolean isWord(String word) {
            return kind == Kind.WORD && new String(bytes, StandardCharsets.US_ASCII).equals(word);
        }

        boolean isSymbol(char symbol) {
            return kind == Kind.SYMBOL && bytes.length == 1 && bytes[0] == symbol;
        }
    }

    /** protoc's lexical rules, as far as finding imports needs them. */
    private static final class Lexer {
        private final byte[] source;
        private int position;
        private int line = 1;
        private int column;

        Lexer(byte[] source) {
            this.source = source;
        }

        List<Token> tokens() {
            List<Token> tokens = new ArrayList<>();
            if (source.length >= 3 && (source[0] & 0xFF) == 0xEF && (source[1] & 0xFF) == 0xBB
                    && (source[2] & 0xFF) == 0xBF) {
                advance();
                advance();
                advance();
            }
            while (position < source.length) {
                int c = source[position] & 0xFF;
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
                    advance();
                } else if (c == '/' && peek(1) == '/') {
                    while (position < source.length && source[position] != '\n') {
                        advance();
                    }
                } else if (c == '/' && peek(1) == '*') {
                    advance();
                    advance();
                    while (position < source.length && !(source[position] == '*' && peek(1) == '/')) {
                        advance();
                    }
                    if (position < source.length) {
                        advance();
                        advance();
                    }
                } else {
                    int startLine = line;
                    int startColumn = column + 1;
                    int start = position;
                    if (c == '"' || c == '\'') {
                        tokens.add(new Token(Kind.STRING, string(c), startLine, startColumn));
                    } else if (isWordStart(c)) {
                        while (position < source.length && isWordPart(source[position] & 0xFF)) {
                            advance();
                        }
                        tokens.add(new Token(Kind.WORD, slice(start, position), startLine, startColumn));
                    } else {
                        advance();
                        tokens.add(new Token(Kind.SYMBOL, slice(start, position), startLine, startColumn));
                    }
                }
            }
            return tokens;
        }

        /** A string literal's bytes, escapes decoded; it ends at its quote or at the end of the line. */
        private byte[] string(int quote) {
            advance();
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            while (position < source.length) {
                int c = source[position] & 0xFF;
                if (c == quote) {
                    advance();
                    break;
                }
                if (c == '\n') {
                    break;
                }
                advance();
                if (c != '\\' || position >= source.length) {
                    value.write(c);
                    continue;
                }
                int escape = source[position] & 0xFF;
                advance();
                switch (escape) {
                    case 'a' -> value.write(0x07);
                    case 'b' -> value.write('\b');
                    case 'f' -> value.write('\f');
                    case 'n' -> value.write('\n');
                    case 'r' -> value.write('\r');
                    case 't' -> value.write('\t');
                    case 'v' -> value.write(0x0B);
                    case 'x', 'X' -> value.write(number(16, 2, 0));
                    case 'u', 'U' -> unicode(value, escape);
                    default -> {
                        if (escape >= '0' && escape <= '7') {
                            value.write(number(8, 2, escape - '0'));
                        } else {
                            value.write(escape);
                        }
                    }
                }
            }
            return value.toByteArray();
        }

        /** Up to maxDigits more digits in the radix, accumulated onto value. */
        private int number(int radix, int maxDigits, int value) {
            for (int i = 0; i < maxDigits && position < source.length; i++) {
                int digit = Character.digit(source[position] & 0xFF, radix);
                if (digit < 0) {
                    break;
                }
                value = value * radix + digit;
                advance();
            }
            return value;
        }

        /**
         * A Unicode escape, the way protoc's tokenizer reads one: exactly four
         * hex digits after a lowercase u, eight after an uppercase U. A head
         * surrogate directly followed by a lowercase-u escape of a trail
         * surrogate is one code point, as in UTF-16; protoc never pairs with
         * an uppercase-U trail. protoc rejects an escape with too few digits,
         * so what this keeps for one (the letter, and the digits as plain
         * text) never reaches a build.
         */
        private void unicode(ByteArrayOutputStream value, int letter) {
            int digits = letter == 'u' ? 4 : 8;
            long codePoint = hexDigits(position, digits);
            if (codePoint < 0) {
                value.write(letter);
                return;
            }
            skip(digits);
            if (codePoint >= 0xD800 && codePoint <= 0xDBFF && peek(0) == '\\' && peek(1) == 'u') {
                long trail = hexDigits(position + 2, 4);
                if (trail >= 0xDC00 && trail <= 0xDFFF) {
                    skip(6);
                    codePoint = Character.toCodePoint((char) codePoint, (char) trail);
                }
            }
            appendUtf8(value, codePoint);
        }

        /** The value of exactly count hex digits at index, or -1 when fewer follow. */
        private long hexDigits(int index, int count) {
            long value = 0;
            for (int i = 0; i < count; i++) {
                int digit = index + i < source.length ? Character.digit(source[index + i] & 0xFF, 16) : -1;
                if (digit < 0) {
                    return -1;
                }
                value = value * 16 + digit;
            }
            return value;
        }

        /**
         * A code point in UTF-8 bytes as protoc's AppendUTF8 writes it: a
         * surrogate left unpaired still takes its three bytes, which no valid
         * file name holds, and a value past U+10FFFF is written as the text of
         * an uppercase-U escape with eight lowercase hex digits.
         */
        private static void appendUtf8(ByteArrayOutputStream out, long codePoint) {
            int cp = (int) codePoint;
            if (codePoint <= 0x7F) {
                out.write(cp);
            } else if (codePoint <= 0x7FF) {
                out.write(0xC0 | cp >> 6);
                out.write(0x80 | cp & 0x3F);
            } else if (codePoint <= 0xFFFF) {
                out.write(0xE0 | cp >> 12);
                out.write(0x80 | cp >> 6 & 0x3F);
                out.write(0x80 | cp & 0x3F);
            } else if (codePoint <= 0x10FFFF) {
                out.write(0xF0 | cp >> 18);
                out.write(0x80 | cp >> 12 & 0x3F);
                out.write(0x80 | cp >> 6 & 0x3F);
                out.write(0x80 | cp & 0x3F);
            } else {
                out.writeBytes(String.format("\\U%08x", codePoint).getBytes(StandardCharsets.US_ASCII));
            }
        }

        private void skip(int count) {
            for (int i = 0; i < count; i++) {
                advance();
            }
        }

        private static boolean isWordStart(int c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean isWordPart(int c) {
            return isWordStart(c) || (c >= '0' && c <= '9');
        }

        private byte[] slice(int start, int end) {
            byte[] bytes = new byte[end - start];
            System.arraycopy(source, start, bytes, 0, bytes.length);
            return bytes;
        }

        private int peek(int offset) {
            int index = position + offset;
            return index < source.length ? source[index] & 0xFF : -1;
        }

        /** Moves past one byte, counting lines and protoc's 0-based columns. */
        private void advance() {
            byte b = source[position];
            if (b == '\n') {
                line++;
                column = 0;
            } else if (b == '\t') {
                column += TAB_WIDTH - column % TAB_WIDTH;
            } else {
                column++;
            }
            position++;
        }
    }
}
