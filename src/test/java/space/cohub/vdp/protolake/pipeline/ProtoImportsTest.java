package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Imports read the way protoc reads them: every form protoc accepts is found,
 * at its {@code import} keyword, and nothing protoc would skip is.
 */
class ProtoImportsTest {

    private static final String EXPOSURE = "cohub/api/v1/exposure.proto";

    @Test
    void parse_plainPublicAndWeakImports_atTheirKeywords() {
        String source = "syntax = \"proto3\";\n\nimport \"a/b.proto\";\n  import public \"c/d.proto\";\nimport weak 'e.proto';\n";

        assertThat(ProtoImports.parse(source)).containsExactly(
                new ProtoImports.Import("a/b.proto", 3, 1),
                new ProtoImports.Import("c/d.proto", 4, 3),
                new ProtoImports.Import("e.proto", 5, 1));
    }

    @Test
    void parse_singleQuotesAndAnImportAfterAnotherStatementOnTheSameLine() {
        String source = "syntax = \"proto3\"; import '" + EXPOSURE + "';\npackage x;import\"f.proto\";\n";

        assertThat(ProtoImports.parse(source)).containsExactly(
                new ProtoImports.Import(EXPOSURE, 1, 20),
                new ProtoImports.Import("f.proto", 2, 11));
    }

    @Test
    void parse_adjacentStringLiteralsConcatenate() {
        assertThat(ProtoImports.parse("import \"cohub/api/\"\n  'v1/exposure.proto';\n"))
                .containsExactly(new ProtoImports.Import(EXPOSURE, 1, 1));
    }

    @Test
    void parse_escapesAreDecoded() {
        assertThat(ProtoImports.parse("import \"cohub\\x2fapi\\057v1/exposure.proto\";"))
                .extracting(ProtoImports.Import::path).containsExactly(EXPOSURE);
    }

    @Test
    void parse_byteEscapesSpellUtf8_asProtocReadsThem() {
        // protoc reads a literal as bytes and the path as those bytes in UTF-8: all three name lib/é.proto.
        String expected = "lib/é.proto";

        assertThat(ProtoImports.parse("import \"lib/\\303\\251.proto\";")).extracting(ProtoImports.Import::path)
                .containsExactly(expected);
        assertThat(ProtoImports.parse("import \"lib/\\xc3\\xa9.proto\";")).extracting(ProtoImports.Import::path)
                .containsExactly(expected);
        assertThat(ProtoImports.parse("import \"lib/\\303\" '\\251.proto';")).extracting(ProtoImports.Import::path)
                .containsExactly(expected);
    }

    @Test
    void parse_unicodeEscapesAndLiteralCharacters_areUtf8Too() {
        String expected = "lib/é.proto";

        assertThat(ProtoImports.parse("import \"lib/\\u00e9.proto\";")).extracting(ProtoImports.Import::path)
                .containsExactly(expected);
        assertThat(ProtoImports.parse("import \"lib/é.proto\";")).extracting(ProtoImports.Import::path)
                .containsExactly(expected);
    }

    @Test
    void parse_aCommentInAnotherEncoding_isSkippedLikeAnyComment() {
        byte[] latin1 = "syntax = \"proto3\";\n// café in Latin-1\nimport \"a.proto\";\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThat(ProtoImports.parse(latin1)).containsExactly(new ProtoImports.Import("a.proto", 3, 1));
    }

    @Test
    void parse_columnsCountBytesAndTabStops_asProtocDoes() {
        assertThat(ProtoImports.parse("\timport \"a.proto\";\n/* é */ import \"b.proto\";\n")).containsExactly(
                new ProtoImports.Import("a.proto", 1, 9),
                new ProtoImports.Import("b.proto", 2, 10));
    }

    @Test
    void parse_importsInCommentsAndStrings_areNotImports() {
        String source = "syntax = \"proto3\";\n"
                + "/* import \"" + EXPOSURE + "\";\n   import 'x.proto'; */\n"
                + "// import \"" + EXPOSURE + "\";\n"
                + "option java_package = \"import 'y.proto';\";\n";

        assertThat(ProtoImports.parse(source)).isEmpty();
    }

    @Test
    void parse_aFieldOrOptionNamedImport_isNotAnImport() {
        String source = "syntax = \"proto3\";\n"
                + "message M {\n  string import = 1;\n  import \"inside.proto\";\n}\n"
                + "option (my.import) = \"z.proto\";\n";

        assertThat(ProtoImports.parse(source)).isEmpty();
    }

    @Test
    void parse_anImportAfterAMessage_isFound() {
        String source = "message M { message N { int32 a = 1; } }\nimport \"late.proto\";\n";

        assertThat(ProtoImports.parse(source)).containsExactly(new ProtoImports.Import("late.proto", 2, 1));
    }

    @Test
    void parse_unterminatedCommentOrString_endsQuietly() {
        // protoc rejects both files; the parse only has to stop without failing.
        assertThat(ProtoImports.parse("import \"a.proto\";\n/* never closed")).hasSize(1);
        assertThat(ProtoImports.parse("import \"never closed\n")).isEmpty();
    }
}
