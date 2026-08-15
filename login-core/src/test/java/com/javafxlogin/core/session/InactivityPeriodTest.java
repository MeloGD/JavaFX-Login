package com.javafxlogin.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The one value that says how long a Session may idle, or that it never does. */
class InactivityPeriodTest {

  @Test
  void carriesTheDurationItWasGiven() {
    InactivityPeriod period = InactivityPeriod.of(Duration.ofMinutes(20));

    assertEquals(Optional.of(Duration.ofMinutes(20)), period.expiresAfter());
    assertFalse(period.isDisabled());
  }

  @Test
  void switchedOffHasNoDuration() {
    InactivityPeriod disabled = InactivityPeriod.disabled();

    assertTrue(disabled.isDisabled());
    assertEquals(Optional.empty(), disabled.expiresAfter());
  }

  /** A Session that expires the moment it is granted is a refusal written in the wrong place. */
  @Test
  void refusesAPeriodThatIsNotSomeTime() {
    assertThrows(IllegalArgumentException.class, () -> InactivityPeriod.of(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> InactivityPeriod.of(Duration.ofMinutes(-1)));
  }

  /** One text form serves the store and the wire, so what was configured cannot drift. */
  @Test
  void survivesBeingWrittenDownAndReadBack() {
    InactivityPeriod period = InactivityPeriod.of(Duration.ofMinutes(15));

    assertEquals(period, InactivityPeriod.parse(period.text()));
    assertEquals(
        InactivityPeriod.disabled(), InactivityPeriod.parse(InactivityPeriod.disabled().text()));
  }

  @Test
  void isWrittenAsAnIsoDurationOrTheWordDisabled() {
    assertEquals("PT15M", InactivityPeriod.of(Duration.ofMinutes(15)).text());
    assertEquals("disabled", InactivityPeriod.disabled().text());
  }

  @Test
  void refusesTextThatIsNotAPeriodThisBuildWrote() {
    assertThrows(IllegalArgumentException.class, () -> InactivityPeriod.parse("fifteen minutes"));
    assertThrows(IllegalArgumentException.class, () -> InactivityPeriod.parse("PT0S"));
    assertThrows(IllegalArgumentException.class, () -> InactivityPeriod.parse(""));
  }

  @Test
  void theDefaultIsFifteenMinutes() {
    assertEquals(Optional.of(Duration.ofMinutes(15)), InactivityPeriod.DEFAULT.expiresAfter());
  }
}
