# Tera for JetBrains IDEs

Editor support for [Tera](https://github.com/Keats/tera) templates (`.tera`).

## Editing templates

- Tera syntax highlighting, including whitespace control (`{%-`, `-%}`, `{{-`,
  `-}}`, `{#-`, `-#}`).
- Block/delimiter diagnostics, with optional names on `endmacro` and `endblock`.
- Macro analysis across templates: `{% import "macros" as m %}` is resolved
  Rocket-style (relative to the nearest `templates` folder, extensions optional)
  and `self::` refers to the current file. Calls such as `m::input(...)` are checked
  for unknown macros/namespaces, unknown, duplicate, positional and missing
  required arguments, duplicate macro/parameter names, and unresolvable imports.
- Completion: macro names after `ns::` (inserted with their required arguments),
  parameter names inside a call, import aliases and `self`, tag keywords after
  `{%`, and template names inside `import`/`extends`/`include` strings.
- Go to definition (Cmd/Ctrl+B or Cmd/Ctrl+click) from a macro name, namespace
  alias, call argument name, or the path in `import`/`extends`/`include`. When
  several sources match (for example `macros.html.tera` and `macros.txt.tera`,
  or an alias imported twice) the IDE shows a chooser list.
- Quick Documentation on hover for each part of a call such as
  `m::input(label="Publish Date", type="date", name="submission.date")`:
  the alias shows the import and its macros, the macro name shows its signature,
  parameters and description, and each argument name shows its type, required/optional
  kind and description. Write the description in a `{# ... #}` comment directly
  above the macro, optionally with `@param name {type} text` lines; without a
  `{type}` the type is inferred from the default value (string, integer, float,
  boolean, array, object) or shown as `any`.
- Variables from your Rust code (RustRover, CLion, or IDEA with the Rust plugin): for
  `Template::render("index", ...)` the plugin finds the call that renders the open
  template (named like Rocket does: path below `templates/`, no extension) and reads
  the context with the Rust plugin's PSI and type inference. It understands a struct
  (`&Context { .. }`, `Context::raw(..).await`, `&Context::default()`) including serde
  `rename`, `rename_all`, `skip`, `flatten`, and nested `Vec`/`Option`/`HashMap`/tuples,
  and `context! { key: value, shorthand }`. Then:
  - `{{ ` completes the context variables with their Rust types;
  - `task.` completes fields, including loop variables from `{% for task in tasks %}`,
    `{% set %}` variables, macro parameters (macros do not see the context), `loop.*`,
    `a[0].b` and `a.0`;
  - `|` completes Tera's built-in filters, `is` the built-in tests, and the built-in
    functions are offered;
  - go to definition on a variable or field jumps to the Rust field; hover shows its
    type, doc comment, source and fields;
  - unknown variables (weak warning) and unknown fields of a known struct are reported,
    only when every render call site was understood;
  - a base template or partial (`extends`/`include`) uses the context of the templates
    that use it.
  Types from crates that are not part of the project are never guessed; the context is
  then marked incomplete and no variable diagnostics are shown.
- Native template data language integration: `.html.tera` uses HTML,
  `.css.tera` uses CSS, and `.js.tera` uses JavaScript when those languages are
  installed and enabled. Files without a recognized inner extension default to HTML.
- HTML tags, attributes, and embedded `<style>`/`<script>` content use the IDE's
  language services and color scheme. This is IntelliJ template/PSI integration,
  not an external LSP server. No Node.js server needs to be configured.
- Override the data language through **Settings → Languages & Frameworks →
  Template Data Languages**. Customize Tera colors under **Editor → Color
  Scheme → Tera Template**, and HTML/CSS/JavaScript colors in their own pages.

HTML support is required. CSS/JavaScript features depend on the host IDE's
language plugins; installing this plugin does not add WebStorm's services to
an IDE that does not provide them. Macro checks only see macros defined in the file itself and in templates it
imports directly; macros reached through `extends` are not followed, and an
importing file is re-checked when it is edited (not when the imported file
changes). Tera diagnostics are structural checks, not
a complete implementation of the Tera runtime. Rust context discovery is static: values
built at run time (maps filled in a loop, `serde_json::Value`, serde `flatten` of a map)
are reported as unknown, and templates registered with `add_raw_template` have no file.
Template output that generates arbitrary language syntax may require additional
context-aware handling; this plugin does not render templates or infer their output.

## Build and test

Use JDK 21 and the included Gradle wrapper:

```sh
bash ./gradlew test buildPlugin
bash ./gradlew test -PwebTests=true
bash ./gradlew test -PrustRoverHome=/Applications/RustRover.app
```

The default suite uses IntelliJ IDEA Community 2025.1 and checks HTML integration
without requiring commercial web plugins. The web suite uses IntelliJ IDEA
Ultimate 2025.1 with its bundled CSS/JavaScript plugins, runs the core tests again,
and checks embedded language PSI, direct CSS/JS templates and code completion.
The Rust suite (`-PrustRoverHome`, a local RustRover 2025.1 or newer) runs everything
including the tests that read Rust code through the Rust plugin. Without it the Rust
integration (`src/rust`) is compiled only if `~/Applications/RustRover.app` exists;
otherwise the plugin is built without it. Gradle downloads the other test IDEs; the
first run can take several minutes.

The eight unmodified Rocket templates in `src/test/resources/rocket` are regression
assets. Their source revision, checksums, expected Tera diagnostic counts, and
MIT license are included. Tests check checksums, zero Tera errors, real HTML
structure, source offsets, and lexer restart behavior. Mutation tests check that
missing/unexpected closers still report errors at the right locations. Additional
synthetic cases cover syntax boundaries and web integration absent from Rocket's
original assets.

Reports: `build/reports/tests/test/index.html` and `build/test-results/test/`.
A subsequent test run replaces these reports; copy them before switching suites.

## Install a local build

Build the ZIP, then in the IDE open **Settings → Plugins → gear menu → Install
Plugin from Disk**, select `build/distributions/tera-1.2.0.zip`, and restart if
prompted. This replaces the installed plugin with the same ID (`com.tera.tera`).
To return to the Marketplace version, uninstall the local build and reinstall Tera.

A fork's ZIP/release is not an official JetBrains Marketplace update. Publishing
an update to the existing Marketplace listing requires its owner's permissions.
