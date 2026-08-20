# The interface language is the client's, and it is chosen twice

Issue #13 asks for the application in the language of the person using it: the
product's interface is Spanish, the code and documentation stay English, and
adding a language must touch no code. Two decisions in it are worth recording,
because both draw a line about where a language lives and when it is allowed to
change.

**The decision.**

- **Every sentence lives in `login-ui`, in a `ResourceBundle`, and nowhere else.**
  `messages.properties` is English and is the base bundle; `messages_es.properties`
  is Spanish. `languages.properties` beside them says which languages this build
  offers, in the order the selector lists them. Adding a language is those two
  files — one new bundle and one tag — and no class, because nothing in this
  project names a language.
- **The AuthenticationService names things and never words them, and that now
  includes languages.** It records the BCP 47 tag it is given, refuses nothing on
  the grounds that no bundle answers to it, and holds no list of languages. A
  service that held the list would have to be changed, and restarted privileged,
  to add one — which is the change of shape this ticket exists to avoid, and the
  reason V006 shipped a plain nullable `TEXT` column with no `CHECK` constraint.
- **The language is chosen twice, and the order is the point.** The login screen
  and the FirstRunWizard have to be readable *before* anybody has authenticated,
  so they follow the machine's display locale, and the login screen carries a
  selector for when that locale is not the language of whoever is at the keyboard.
  Only an admission can apply an Account's own `LanguagePreference`, because
  until a password has been offered and found right there is a name somebody
  typed and no Account it has been proved to be. The preference therefore rides
  on `Granted`, alongside the password-reset notice, and on nothing earlier.
- **A window says what it is drawn in, and hands on a key for what it is not.**
  A Session that ends is discovered by the window that is closing and said by the
  login screen behind it, which is drawn in another language. So `SessionGuard`,
  `SessionEndedText`, `AdministrationRefusedText` and `GateAttempt` answer with
  the *key* of a sentence, and whichever window draws it words it. The reverse —
  wording it where it is decided — would have this application say goodbye to
  whoever walks up next in the language of the person who has just left.
- **The login screen does not keep the language of the Account that was
  admitted.** It goes back to the machine's, or to what the selector was set to.
  A screen that had learned somebody's language would be telling the next person
  at that machine what the last one reads, which is a fact about an Account and
  is exactly what ADR-0002 keeps out of an unprivileged reach elsewhere.
- **An Account's language is recorded by the Administrator and applies at the
  next admission.** `ChangeLanguagePreference` is an administration request like
  every other one about somebody else's Account: refused unless the Session is an
  Administrator's, refused for a name no Account holds, and recorded as an
  `AuthenticationEvent` — `LANGUAGE_PREFERENCE_CHANGED`, against the Account it
  is about, without saying which language, because the record is read with other
  tools and a copy of the tag buys nothing.
- **A language this build has no wording for is drawn in the first one offered,
  not in a mixture.** `ResourceBundle` would happily fall back key by key; the
  candidates here stop at the base bundle and never reach the machine's own
  locale, so a screen is in one language rather than in two. A regional variant
  is matched to the language it varies (`es-MX` reads Spanish), the way BCP 47
  says to match one.

## What was considered and rejected

- **Letting the service decide the language.** It knows the tag, so it could word
  its own refusals. Rejected: it would put every sentence in the product inside
  the privileged process, make adding a language a privileged deployment, and
  hand a client sentences it cannot check — the opposite of the arrangement where
  the service names a `PolicyViolation` and the window words it.
- **A selector on the FirstRunWizard as well.** The ticket puts one on the login
  screen, and the wizard is seen once, by whoever installed the product on a
  machine whose locale they set. It is a small addition if it turns out to be
  wanted; it is not shipped unasked.
- **Remembering the selector's choice on disk.** The override lasts as long as
  the run, which is what the ticket asks for. Writing it somewhere would be this
  application keeping a preference for a person it has not identified, on a
  machine where the next person may be somebody else — and the durable answer
  already exists as the `LanguagePreference` of an Account.
- **Applying a language change to the panel that made it.** An Administrator
  setting their own Account's language sees it at their next admission, like
  everybody else. Redrawing the open window would make one Account's preference
  behave differently from every other one's for no reason a person could state.

## Consequences

- `Granted` and `Admitted` carry an `Optional<Locale>`, written on the wire as an
  explicit `null` where the Account has said nothing — the two are different
  facts, and this codec does not turn one into the other.
- `LoginGate` gains `useLanguagePreference`, and the `Request` set gains
  `ChangeLanguagePreference`. The panel gains one selector and one button; the
  login screen gains one selector beside its title.
- Every window is loaded with a bundle, so the FXML holds `%keys` and not
  sentences. A key missing from any offered language fails in the tests: the
  bundles are compared key for key, every screen is loaded in every language, and
  every enum the service can name is worded in each of them.
- A message with an argument is a `MessageFormat` pattern, so a sentence that
  changes with a number — the minutes left of a Lockout — chooses inside the
  bundle rather than in the code that formats it, and a language that counts
  differently is an edit to a properties file.
