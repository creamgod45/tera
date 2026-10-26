# Tera for JetBrains IDEs

Editor support for [Tera](https://github.com/Keats/tera) templates (`.tera`).

## Editing templates

- Tera syntax highlighting, including whitespace control (`{%-`, `-%}`, `{{-`,
  `-}}`, `{#-`, `-#}`).
- Block/delimiter diagnostics, with optional names on `endmacro` and `endblock`.
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
an IDE that does not provide them. Tera diagnostics are structural checks, not
a complete implementation of the Tera runtime or a Rust context type checker.
Template output that generates arbitrary language syntax may require additional
context-aware handling; this plugin does not render templates or infer their output.

## Build and test

Use JDK 21 and the included Gradle wrapper:

```sh
bash ./gradlew test buildPlugin
bash ./gradlew test -PwebTests=true
```

The default suite uses IntelliJ IDEA Community 2025.1 and checks HTML integration
without requiring commercial web plugins. The web suite uses IntelliJ IDEA
Ultimate 2025.1 with its bundled CSS/JavaScript plugins, runs the core tests again,
and checks embedded language PSI, direct CSS/JS templates and code completion.
Gradle downloads the test IDEs; the first run can take several minutes.

The eight unmodified Rocket templates in `src/test/resources/rocket` are regression
assets. Their source revision, checksums, expected Tera diagnostic counts, and
MIT license are included. Tests check checksums, zero Tera errors, real HTML
structure, source offsets, and lexer restart behavior. Mutation tests check that
missing/unexpected closers still report errors at the right locations. Additional
synthetic cases cover syntax boundaries and web integration absent from Rocket's
original assets.

Reports: `build/reports/tests/test/index.html` and `build/test-results/test/`.
A subsequent test run replaces these reports; copy them before switching suites.
See `docs/validation.md` for the recorded validation and its limits.

## Install a local build

Build the ZIP, then in the IDE open **Settings → Plugins → gear menu → Install
Plugin from Disk**, select `build/distributions/tera-1.2.0.zip`, and restart if
prompted. This replaces the installed plugin with the same ID (`com.tera.tera`).
To return to the Marketplace version, uninstall the local build and reinstall Tera.

A fork's ZIP/release is not an official JetBrains Marketplace update. Publishing
an update to the existing Marketplace listing requires its owner's permissions.
