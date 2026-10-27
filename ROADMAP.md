# Tera plugin roadmap

Sizes: S = under half a day, M = about a day, L = several days. "Done when" is how each item is checked.
Order of work: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 9; 10 runs alongside. Item 8 (Zola) is skipped for now.

## 1. Editor basics (S–M) — done

- 1.1 Comment and bracket matching (S)
  - 1.1.1 `Commenter` for `{# #}` (Cmd+/ and Cmd+Alt+/)
  - 1.1.2 `PairedBraceMatcher` for `{{ }}`, `{% %}`, `{# #}`
  - 1.1.3 Tests: toggle comment on a line and on a selection
- 1.2 Auto-close delimiters (M)
  - 1.2.1 Typing `{{` gives `{{  }}` with the caret inside; `{%` gives `{%  %}`; `{#` gives `{#  #}`
  - 1.2.2 Do not double up with the HTML plugin's own brace handling
  - 1.2.3 Backspace on an empty pair removes both
  - 1.2.4 Tests through the editor fixture (type char, check text and caret)
- 1.3 Code folding (M)
  - 1.3.1 Fold `block`, `for`, `if`, `macro`, `component`, `filter`, `raw`, `set … endset`
  - 1.3.2 Placeholder text shows the tag (`{% for x in xs %}…`)
  - 1.3.3 Tests: unclosed tags and nested blocks fold correctly
- 1.4 Live Templates (M)
  - 1.4.1 Common: `block`, `if`, `ifelse`, `forloop`, `extend`, `include`, `import`, `filter`, `xx`
  - 1.4.2 Tera 1 only: `macro`; Tera 2 only: `component`, `compbody`, `set`, `setglobal`, `ternary`, `comprehension`
  - 1.4.3 A "Tera" template context type that follows the project's Tera version
  - 1.4.4 Tests: expand each template; version filter works
- Done when: all four work in a real template, README lists them.

## 2. Template roots setting (S–M) — done

- 2.1 Settings page field "Template roots" (S)
  - 2.1.1 List of directories, relative to the project
  - 2.1.2 Stored in `TeraSettingsState`
- 2.2 Resolution order (S)
  - 2.2.1 Manual roots first, then detected (`Rocket.toml`, `Tera::new`, `relative!`, `set_current_dir`), then `templates`
  - 2.2.2 Show in the Rust gutter tooltip which source decided
- 2.3 Use everywhere (M)
  - 2.3.1 `extends`/`include`/`import` resolution and completion
  - 2.3.2 Rust string navigation and path completion
  - 2.3.3 Render-context template names
- 2.4 Tests and README (S)
- Done when: a project with an unusual root works after adding one setting.

## 3. Block navigation (M)

- 3.1 Block → parent definition (M)
  - 3.1.1 Resolve the `extends` chain (several levels)
  - 3.1.2 Go to Declaration on `{% block x %}` in a child lists the parent block(s)
- 3.2 Gutter icons (M)
  - 3.2.1 "Overrides" icon in a child, "Overridden in N templates" in a parent
  - 3.2.2 Click shows the list
- 3.3 `{{ super() }}` jumps to the parent block (S)
- 3.4 Tests: three-level chain, missing parent, two children (S)
- Done when: review-poc `layouts/section` blocks navigate both ways.

## 4. Find usages and rename (L)

- 4.1 One symbol model (M)
  - 4.1.1 Symbols: macro, block, component, `set`/`for` variable, template file
  - 4.1.2 Decide how to attach references to the flat PSI (`PsiReference` on leaf tokens vs. custom search)
  - 4.1.3 Spike on one symbol (block) before the rest
- 4.2 Find Usages (M)
  - 4.2.1 Macros, blocks, variables, components (extends the existing component gutter)
  - 4.2.2 Usage grouping by file
- 4.3 Rename (L)
  - 4.3.1 Macro, block, component, variable inside its scope
  - 4.3.2 Preview and conflict detection
- 4.4 Rename a template file (M)
  - 4.4.1 Update `extends`/`include`/`import` strings
  - 4.4.2 Update Rust strings that name it (`render("…")`, constants)
- 4.5 Tests per symbol kind (M)
- Done when: renaming `ui.card` or `layouts/app` updates every use without leaving a broken name.

## 5. Formatting (M)

- 5.1 Find out what happens today (S)
  - 5.1.1 Reformat review-poc templates with the HTML formatter; record what breaks
  - 5.1.2 Turn the findings into failing tests
- 5.2 Choose the approach (S)
  - 5.2.1 Option A: keep HTML formatting but protect `{% %}`/`{{ }}`
  - 5.2.2 Option B: call djLint with `--profile=tera` as an external formatter
  - 5.2.3 Decision recorded here
- 5.3 Implement the chosen option (M)
- 5.4 Tests and README (S)
- Done when: Reformat Code never changes the meaning of a template.

## 6. Clickable runtime errors (S–M)

- 6.1 Error formats (S)
  - 6.1.1 Tera 2 `--> file:line:col` blocks, Tera 1 chained errors, Rocket's `template 'x': error:` line
  - 6.1.2 Sample outputs as test fixtures
- 6.2 Console filter in Run and Terminal (M)
  - 6.2.1 Turn `file:line:col` into a link
  - 6.2.2 Resolve the file through the template roots (item 2)
- 6.3 Tests on the fixtures (S)
- Done when: clicking an error in the Rocket run window opens the template at that position.

## 7. Warning for printing a list or map (S–M)

- 7.1 Inspection rule (M)
  - 7.1.1 `{{ x }}` where x is a list or map by Rust type; message depends on version (Tera 1 `[object]`, Tera 2 JSON)
  - 7.1.2 Skip when a filter follows that produces a plain value
- 7.2 Quick fixes (S): reuse the loop, loop-with-else and join intentions
- 7.3 Severity and on/off in settings (S)
- 7.4 Tests (S)
- Done when: review-poc's `{{ categories }}` style mistakes are flagged with a one-click fix.

## 8. Zola awareness (L)

- 8.1 Detect a Zola site (S): `config.toml` plus `content/` and `templates/`
- 8.2 Context by template kind (L)
  - 8.2.1 `index.html`, `section.html`, `page.html`, `taxonomy_*`, `404.html`, `robots.txt`, `sitemap.xml`
  - 8.2.2 Variables per kind (`page`, `section`, `config`, `lang`, `current_url`, `term`, …) with types and docs
  - 8.2.3 Check each against Zola's documentation
- 8.3 Zola functions, filters and tests (M): `get_url`, `resolve_path`, `get_page`, `get_section`, `load_data`, `markdown`, …
- 8.4 Shortcodes (M)
  - 8.4.1 Index `templates/shortcodes/`
  - 8.4.2 Completion and navigation for `{{ name(...) }}` and body forms
- 8.5 Tests on a small sample site (M)
- Done when: a Zola theme template has no false "undefined variable" and completes `page.`.

## 9. Context description file for non-Rust data (M)

- 9.1 File format (S): a JSON Schema subset (`type`, `properties`, `items`, `description`)
- 9.2 Mapping rules (S): template glob → schema file, in settings or a `tera.json` next to the templates
- 9.3 Context provider (M)
  - 9.3.1 Convert the schema to the plugin's type model
  - 9.3.2 Merge with Rust-derived variables (Rust wins on conflict)
- 9.4 Tests and README (S)
- Done when: a template fed by dynamic JSON completes its fields from the schema.

## 10. Across the board

- 10.1 Manual check in RustRover (S)
  - 10.1.1 Checklist for review-poc: navigation, usages gutter, loops, completion, hover
  - 10.1.2 Record what differs from the automated tests
- 10.2 Performance (M)
  - 10.2.1 Measure on a project with hundreds of templates
  - 10.2.2 Cache the scans behind `Find usages`, roots and context lookups
- 10.3 Release (S)
  - 10.3.1 Version number and `changeNotes`
  - 10.3.2 PR description for the upstream author
  - 10.3.3 Marketplace upload (needs the account owner)
- 10.4 Documentation (S): README sections per feature, screenshots

## Already done (for reference)

False-error fixes, HTML/CSS/JS in templates, macro and component support with hover and go-to-definition, Rust render
context (any render function, generics, layouts, Rocket `Context`), custom builtins and their return types, Tera 1/2
version detection and migration, template path navigation and completion in Rust, root detection, component usages
gutter, loop/join intentions, the review-poc and forms-tera2 examples with cargo and plugin tests.
