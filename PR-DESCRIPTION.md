# Tera plugin: fewer false errors, Tera 2, and Laravel-Idea-style editing

The full list of changes is in `CHANGELOG.md`.

## What this does

**Correctness.** No false errors for whitespace-trimming delimiters and named `endmacro`/`endblock`; syntax errors for what
the engines really reject (checked against Tera 1.20.1 and 2.4.0, including an unfinished `| `).

**Tera versions.** 1.x and 2.x are detected per template (Cargo.toml, Settings); built-ins, deprecations and quick fixes
follow the version; *Migrate to Tera 2* for a file or the project; Tera 2 components, typed parameters and checks.

**Rust-aware.** Template variables come from the Rust code that renders the template: found by meaning from any call that
passes a template path and a serializable value (not only `Template::render`), through generic page shells with
`#[serde(flatten)]`, `let` locals and Rocket's form `Context`; layouts and partials inherit the variables of the pages
that use them. Functions, filters and tests registered from Rust (and what a filter returns) are known. Template roots
come from `Rocket.toml`, `Tera::new(..)`, `relative!`, `set_current_dir`, or a setting.

**Editing.** Comment, brace matching, auto-closing of delimiters, folding, live templates (per Tera version), Reformat Code
that only re-indents.

**Navigation and refactoring.** Go to definition (several sources in a chooser), hover documentation, blocks along the
`extends` chain with gutter icons, usages of components, Find Usages and Rename for blocks, macros, variables and
components, renaming or moving a template updates `extends`/`include`/`import` and the Rust strings that name it, open a
template from the Rust string, template names while typing in Rust.

**Lists and maps.** `{{ list }}` is pointed out with what the engine prints, with loop / loop-else / join quick fixes (also
Alt+Enter, also after filters that keep a list, also for filters registered in Rust).

**Lists of usages.** Usage and definition lists (gutter icons, Cmd+click chooser) show the line of the template and
`file:line`; one gutter icon per line when it calls several components.

**Errors.** Template errors in the Run window are links.

**Data without Rust types.** Variables described by a JSON Schema file.

## How it is tested

`bash ./gradlew test`, `test -PwebTests=true` (HTML/CSS/JS embedding) and `test -PrustRoverHome=…` (the Rust plugin), plus
`buildPlugin verifyPlugin`. The test assets include Rocket's real templates, and `examples/forms-tera2` and
`examples/review-poc` are real Rocket + Tera 2 apps with their own `cargo test`. The error samples and the print behaviour
are the real output of Tera 1.20.1 and 2.4.0. `MANUAL-CHECK.md` lists what only a person in the IDE can judge.

## Not done

Zola awareness (variables of `page`, `section`, `config`), a block-level hover, screenshots.
