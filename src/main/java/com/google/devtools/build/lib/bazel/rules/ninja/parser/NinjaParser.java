// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.rules.ninja.parser;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.bazel.rules.ninja.file.GenericParsingException;
import com.google.devtools.build.lib.bazel.rules.ninja.lexer.NinjaLexer;
import com.google.devtools.build.lib.bazel.rules.ninja.lexer.NinjaToken;
import com.google.devtools.build.lib.util.Pair;
import java.nio.charset.StandardCharsets;

/** Ninja files parser. The types of tokens: {@link NinjaToken}. Ninja lexer: {@link NinjaLexer}. */
public class NinjaParser {
  private final NinjaLexer lexer;

  public NinjaParser(NinjaLexer lexer) {
    this.lexer = lexer;
  }

  /** Parses variable at the current lexer position. */
  public Pair<String, NinjaVariableValue> parseVariable() throws GenericParsingException {
    String name = asString(parseExpected(NinjaToken.IDENTIFIER));
    parseExpected(NinjaToken.EQUALS);

    NinjaVariableValue value = parseVariableValue(true, name);
    return Pair.of(name, value);
  }

  @VisibleForTesting
  public NinjaVariableValue parseVariableValue(boolean allowUnescapedColon, String name)
      throws GenericParsingException {
    return parseVariableValueImpl(
        allowUnescapedColon, () -> String.format("Variable '%s' has no value.", name));
  }

  private NinjaVariableValue parseVariableValueImpl(
      boolean allowUnescapedColon, Supplier<String> messageForNoValue)
      throws GenericParsingException {
    NinjaVariableValue.Builder varBuilder = NinjaVariableValue.builder();
    int previous = -1;
    while (lexer.hasNextToken()) {
      lexer.expectTextUntilEol();
      NinjaToken token = lexer.nextToken();
      if (NinjaToken.VARIABLE.equals(token)) {
        if (previous >= 0) {
          // add space interval between tokens
          varBuilder.addText(
              asString(lexer.getFragment().getBytes(previous, lexer.getLastStart())));
        }
        varBuilder.addVariable(normalizeVariableName(asString(lexer.getTokenBytes())));
      } else if (NinjaToken.TEXT.equals(token)
          || NinjaToken.ESCAPED_TEXT.equals(token)
          || (allowUnescapedColon && NinjaToken.COLON.equals(token))) {
        // Add text together with the spaces between current and previous token.
        int start = previous >= 0 ? previous : lexer.getLastStart();
        String rawText = asString(lexer.getFragment().getBytes(start, lexer.getLastEnd()));
        String text = NinjaToken.ESCAPED_TEXT.equals(token) ? unescapeText(rawText) : rawText;
        varBuilder.addText(text);
      } else {
        lexer.undo();
        break;
      }
      previous = lexer.getLastEnd();
    }
    if (previous == -1) {
      // We read no value.
      throw new GenericParsingException(messageForNoValue.get());
    }
    return varBuilder.build();
  }

  private static String unescapeText(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '$') {
        Preconditions.checkState(i + 1 < text.length());
        sb.append(text.charAt(i + 1));
        i++;
      } else {
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  public NinjaVariableValue parseIncludeStatement() throws GenericParsingException {
    return parseIncludeOrSubNinja(NinjaToken.INCLUDE);
  }

  public NinjaVariableValue parseSubNinjaStatement() throws GenericParsingException {
    return parseIncludeOrSubNinja(NinjaToken.SUBNINJA);
  }

  private NinjaVariableValue parseIncludeOrSubNinja(NinjaToken token)
      throws GenericParsingException {
    parseExpected(token);
    NinjaVariableValue value =
        parseVariableValueImpl(
            true,
            () -> String.format("%s statement has no path.", Ascii.toLowerCase(token.name())));
    if (lexer.hasNextToken()) {
      parseExpected(NinjaToken.NEWLINE);
      lexer.undo();
    }
    return value;
  }

  /** Parses Ninja rule at the current lexer position. */
  public NinjaRule parseNinjaRule() throws GenericParsingException {
    parseExpected(NinjaToken.RULE);
    String name = asString(parseExpected(NinjaToken.IDENTIFIER));

    ImmutableSortedMap.Builder<NinjaRuleVariable, NinjaVariableValue> variablesBuilder =
        ImmutableSortedMap.naturalOrder();
    variablesBuilder.put(NinjaRuleVariable.NAME, NinjaVariableValue.createPlainText(name));

    parseExpected(NinjaToken.NEWLINE);
    while (lexer.hasNextToken()) {
      parseExpected(NinjaToken.INDENT);
      String variableName = asString(parseExpected(NinjaToken.IDENTIFIER));
      parseExpected(NinjaToken.EQUALS);
      NinjaVariableValue value = parseVariableValue(true, variableName);

      NinjaRuleVariable ninjaRuleVariable = NinjaRuleVariable.nullOrValue(variableName);
      if (ninjaRuleVariable == null) {
        throw new GenericParsingException(String.format("Unexpected variable '%s'", variableName));
      }
      variablesBuilder.put(ninjaRuleVariable, value);
      if (lexer.hasNextToken()) {
        parseExpected(NinjaToken.NEWLINE);
      }
    }
    return new NinjaRule(variablesBuilder.build());
  }

  @VisibleForTesting
  public static String normalizeVariableName(String raw) {
    // We start from 1 because it is always at least $ marker symbol in the beginning
    int start = 1;
    for (; start < raw.length(); start++) {
      char ch = raw.charAt(start);
      if (' ' != ch && '$' != ch && '{' != ch) {
        break;
      }
    }
    int end = raw.length() - 1;
    for (; end > start; end--) {
      char ch = raw.charAt(end);
      if (' ' != ch && '}' != ch) {
        break;
      }
    }
    return raw.substring(start, end + 1);
  }

  private static String asString(byte[] value) {
    return new String(value, StandardCharsets.ISO_8859_1);
  }

  private byte[] parseExpected(NinjaToken expectedToken) throws GenericParsingException {
    if (!lexer.hasNextToken()) {
      String message;
      if (lexer.haveReadAnyTokens()) {
        message =
            String.format(
                "Expected %s after '%s'",
                asString(expectedToken.getBytes()), asString(lexer.getTokenBytes()));
      } else {
        message =
            String.format(
                "Expected %s, but found no text to parse", asString(expectedToken.getBytes()));
      }
      throw new GenericParsingException(message);
    }
    NinjaToken token = lexer.nextToken();
    if (!expectedToken.equals(token)) {
      String actual =
          NinjaToken.ERROR.equals(token)
              ? String.format("error: '%s'", lexer.getError())
              : asString(token.getBytes());
      throw new GenericParsingException(
          String.format("Expected %s, but got %s", asString(expectedToken.getBytes()), actual));
    }
    return lexer.getTokenBytes();
  }
}
