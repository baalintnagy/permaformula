# Java source-style tooling

Use JDK 25 and run these commands from the repository root:

```bash
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn verify
```

`spotless:apply` updates maintained Java sources using the pinned Eclipse JDT
formatter and [the existing profile](../eclipse/java-style.properties), orders
imports, and removes unused imports. Wildcard imports fail the check; replace them
with explicit type or static-member imports.

`spotless:check checkstyle:check` checks source style without modifying files or
requiring compilation. Both checks also run in Maven's `verify` phase, so style
violations fail the build. Plugin and formatter versions are pinned in the parent
POM. The Checkstyle engine is pinned separately to support modern Java syntax.

The root build checks production and test sources in all four modules, plus the
standalone `tools/antisamy-trial` Java source. Generated protobuf code, build output,
vendored code, and temporary development utilities are outside the source patterns.
The standalone trial POM does not inherit the reactor parent; run the root style
commands to check it.

### Generated sources in IDEs

Generated code must stay on the compile classpath, but should not produce optional
style or null-analysis diagnostics. In Eclipse, open **Project Properties → Java
Build Path → Source**, select each generated source folder, and set **Ignore
optional compile problems** to **Yes**. This uses JDT's supported source-folder
attribute, while retaining required compilation errors:

```xml
<attribute name="ignore_optional_problems" value="true"/>
```

Apply it only to generated source roots, including protobuf `java` and `grpc-java`
folders and annotation-processor output. Keep checks enabled on `src/main/java`
and `src/test/java`. Do not use classpath exclusion filters or remove generated
roots; application code needs their classes for compilation and navigation.

VS Code's Java tooling also uses JDT, but Maven imports may keep their project
metadata in the language-server workspace rather than the repository. The
attribute must be present on the generated source roots in the active JDT
project. Local Eclipse metadata is intentionally not versioned here. After a
Maven re-import, check whether the attribute was retained; m2e can regenerate
these entries. Refresh or rebuild the project after changing the attribute.
Hiding `target` in the file explorer does not suppress Java diagnostics.

Checkstyle enforces:

- A 100-character source-line limit, with package and import declarations excluded.
- Explicit, used, nonredundant imports.
- Braces around conditional and loop bodies.
- Member and type annotation placement, leaving parameter annotations inline.
- Spaces rather than literal tab characters.
- Text-block opening and closing delimiters on their own lines at the same
  indentation, with text indented at least to the delimiters.
- Ordinary quoted strings for short, simple single-line values instead of
  unnecessary text blocks.

Spotless applies the formatter profile's indentation and wrapping rules, but does
not automatically rewrite long strings into text blocks. Wrap these with Java
text-block line continuations while preserving the string value, including spaces
and trailing newlines. Other project conventions, such as `.formatted()` instead
of string concatenation, static imports for recurring static calls, separate
continuation lines for arguments containing function calls, constructor calls or
supplier/lambda expressions, and choosing streams or pattern matching, still
require code review. Eclipse's argument wrapping is width-based and does not
selectively force these argument kinds onto new lines. These tools do not enforce every
semantic convention in the project guidance.

Put each method invocation after the first call in a call chain on its own line,
with a leading dot and one four-space continuation indentation level. Keep the
receiver and its first call together when they fit within 100 columns; a literal
is not a call. This applies to fluent builders and stream stages, including short
chains, for separate breakpoints. Spotless enforces this through Eclipse's
`alignment_for_selector_in_method_invocation=81` (forced wrapping, subsequent
selectors on separate lines) and `continuation_indentation=1`.
A Spotless regex replacement rejoins previously split receivers
and their initial calls before Eclipse runs, while skipping comments and literals.
An additional literal-aware replacement keeps ordinary string literals with their
first call. Normal width-based wrapping still applies. Comments and text-block
contents are preserved.

The Eclipse 4.41 formatter automatically places text-block delimiters on their
own lines. Text blocks used as continuations receive one additional indentation
level (four spaces), including both delimiters and the content. Keep
`put_text_block_quotes_on_new_line=true` and `text_block_indentation=1` in the
formatter profile. Eclipse supplies argument continuation indentation. Spotless's
JSR223 extension aligns the delimiters and adds the missing continuation level
for assignments and standalone return, yield and throw expressions. It preserves
relative content indentation and the string value. Do not add another indentation
level to arguments that Eclipse has already indented.
Keep Checkstyle's `TextBlockGoogleStyleFormatting` check enabled to verify their
alignment. Spotless checks the continuation layout; Checkstyle also retains the
100-column limit. Line continuations preserve string values when wrapping content
or moving a closing delimiter away from the last content line.

Prefer an ordinary quoted string when a single-line value fits within 100 columns
after moving it to a continuation line if needed. Count indentation, escaped
quotes and backslashes, and surrounding punctuation. Use text blocks for actual
multiline values, necessary source wrapping, or substantially clearer quoting.
Preserve meaningful whitespace and trailing newlines.

Spotless's configured regex replacement and Checkstyle's matching multiline
regex check automate the unambiguous subset: one physical content line ending in
a newline continuation, no embedded quotes or backslashes, and at most 92 columns
before that continuation. This leaves room for continuation indentation, ordinary
quotes and punctuation. A following Spotless replacement wraps long quoted
`String` assignments and `description`/`example` annotation values that Eclipse
would otherwise join onto an overlong line.
Keep both regex configurations in sync. More complex escaping, borderline line
widths, and readability tradeoffs remain review requirements; no independent
formatter is used for these cases.

## Maintaining the rules

Update project guidance, tool configuration, and this coverage documentation
together when adding or changing a source-style rule. Prefer existing Spotless,
Checkstyle, Eclipse, and Prettier settings, checks, and extension points over
independent formatters or source-rewriting utilities. Document any rule that must
remain a code-review requirement because the configured tools cannot enforce it.

Validate configuration changes with the style checks and confirm that the new
rule rejects a representative violation. When automatic fixes are supported,
check that applying them produces stable output accepted by both tools.

## Protobuf schemas

Run `buf lint` and `buf format --diff --exit-code` from the repository root;
use `buf format -w` to apply Buf's native formatting (two-space indentation).
The root `buf.yaml` selects only maintained schemas under
`render-contract/src/main/proto`, excluding generated and dependency sources.
VS Code's Buf extension uses the same configuration. These commands require
the Buf CLI; the configuration was verified with version 1.75.0. They are separate
from the Maven Java style checks.

Keep proto sources free of compiler and lint diagnostics. Package names must
match directories beneath the proto root. Preserve field numbers and assess actual
consumers when changing wire names. The gRPC clients and servers are internal
project modules maintained together; update their generated-stub references and
health-service names together when renaming a service. All standard lint rules
remain enabled, including the service suffix rule. Report warnings that cannot be
safely fixed, with
practical solution ideas. Buf's `FILE` breaking rules are configured for comparison
against a selected baseline with `buf breaking --against <baseline>`.

## Compiler warnings

Try to eliminate Java compiler warnings when they appear, fixing straightforward
causes rather than hiding warnings. If a warning is not easy to resolve, report it
with practical solution ideas and their tradeoffs. Compiler lint remains enabled
in Maven; this is a cleanup and reporting policy, not a blanket warnings-as-errors
requirement.

The two scoped `try` suppressions around Liquibase schema resources account for
upstream `close()` signatures that can throw broad checked exceptions, including
`InterruptedException`. Production migration code preserves interruption and
translates other close failures. Revisit these suppressions if Liquibase narrows
its resource-close API; avoid replacing automatic cleanup merely to remove them.

JDK `sun.misc.Unsafe` warnings can also come from Lombok annotation processing,
Spotless internals, and GraalVM/Truffle during rendering tests. These are dependency
runtime warnings rather than javac diagnostics about project sources. Prefer
compatible upstream releases that remove those calls, then verify formatting and
rendering behavior. Replacing Lombok's generated constructors with explicit
constructors is another option if removing its warning becomes a priority. Do not
disable lint or globally hide JVM warnings to make the build appear clean.

Eclipse and VS Code's Java language tooling also run JDT null-flow analysis, which
is separate from javac lint. A warning-free Maven compilation does not establish
that these IDE checks are clean. Investigate their diagnostics with Eclipse JDT
or its standalone ECJ compiler (`-warn:+null`), using the project classpath and
Lombok's ECJ integration where needed. Prefer using the checked value returned by
`QuickValidator.notNull` when subsequent code dereferences it: JDT does not infer
the non-null state of a variable from an arbitrary `validate(condition, ...)` call.
