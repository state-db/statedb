package app.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.NameGrammar;
import org.junit.jupiter.api.Test;

class NameGrammarTest {

  @Test
  void acceptsPulumiStyleNames() {
    assertTrue(NameGrammar.isValid("dev"));
    assertTrue(NameGrammar.isValid("my-stack_2.prod"));
    assertTrue(NameGrammar.isValid("a"));
    assertTrue(NameGrammar.isValid("A".repeat(100)));
  }

  @Test
  void rejectsSeparatorsAndOversizedNames() {
    // '/' and ':' would collide Cedar resource ids and confuse path parsing.
    assertFalse(NameGrammar.isValid("prod/web"));
    assertFalse(NameGrammar.isValid("statedb:org:x"));
    assertFalse(NameGrammar.isValid(""));
    assertFalse(NameGrammar.isValid(null));
    assertFalse(NameGrammar.isValid("a".repeat(101)));
    assertFalse(NameGrammar.isValid("with space"));
    assertFalse(NameGrammar.isValid("new\nline"));
  }
}
