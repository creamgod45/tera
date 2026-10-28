# Tera for JetBrains IDEs: user guide

English | [繁體中文](USER-GUIDE.zh-TW.md)

This guide shows what the plugin does for you while you write [Tera](https://github.com/Keats/tera) templates
(`.tera`) and how to turn each feature on. Technical detail and the list of changes are in `README.md` and
`CHANGELOG.md`.

## 1. Install

1. Build the plugin (`./gradlew buildPlugin`, JDK 21) or take `tera-<version>.zip` from a release.
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, choose the ZIP, restart the IDE.
3. A local build replaces the installed plugin with the same ID (`com.tera.tera`). The version number may not change
   between two local builds, so restart fully after installing.

**Which IDE.** Any JetBrains IDE with HTML support. Everything about Rust (variables from your structs, template names
in Rust strings, renaming a template in Rust code) needs RustRover, or CLion/IntelliJ IDEA with the Rust plugin.

## 2. Five minutes with a project

Open a Rocket or Tera project and a template such as `templates/pages/detail.html.tera`.

| You want to | Do this |
|---|---|
| See which variables exist | Type `{{ ` and look at the completion list; type `task.` for fields |
| Find where a variable comes from | Cmd/Ctrl+click it: it jumps to the Rust field |
| Jump from a Rust string to the template | Cmd/Ctrl+click `"pages/detail"` in `render(..)` |
| Go to the parent of a block | Cmd/Ctrl+click the block name |
| Find who uses a component | Click the gutter icon on its `{% component %}` line |
| Rename a block, macro, variable, component or file | Shift+F6 |
| Turn `{{ list }}` into a loop | Alt+Enter on it, choose *Tera: for … in …* |
| Fix indentation | Reformat Code (Cmd/Ctrl+Alt+L) |
| Jump to a template error | Click the link in the Run window |

## 3. Settings

**Settings → Languages & Frameworks → Tera**

- **Tera version**: automatic, 1.x or 2.x. Automatic reads the nearest `Cargo.toml` (`tera`, or `rocket_dyn_templates`
  which means Tera 1), then `Cargo.lock`, and falls back to 2.x. The version decides the available filters, tests,
  functions and tags.
- **Template roots**: one directory per line, relative to the project or absolute. Used first when a template name is
  resolved (`extends`, `include`, `import`, and `render("…")` in Rust); then what the plugin detects from `Rocket.toml`
  (`template_dir`), `Tera::new("…")`, `relative!`, `set_current_dir`; then `templates`.
- **In Tera 1.x projects, mark constructs that Tera 2 removed or renamed**: the migration hints (strikethrough, weak warning).
- **Mark a list or map that {{ }} prints as it is, with a quick fix to loop over it**: the warning behind section 8.

Other places: **Settings → Languages & Frameworks → Template Data Languages** (choose the HTML/CSS/JS language of a
template), **Editor → Color Scheme → Tera Template** (colors).

## 4. Writing templates

**Highlighting and structure.** Tera tags are highlighted (including whitespace control `{%-`, `-%}`), and unbalanced
or wrong end tags are reported. HTML, CSS and JavaScript inside the template use the IDE's own support: `.html.tera` is
HTML, `.css.tera` is CSS, `.js.tera` is JavaScript.

**Typing helpers.** Cmd/Ctrl+/ comments with `{# #}`. Typing `{{`, `{%` or `{#` adds the closing delimiter with the caret
inside (Backspace on an empty pair removes both). `{{ }}`, `{% %}` and `{# #}` match each other as brackets. Blocks fold
(`block`, `for`, `if`, `macro`, `component`, `filter`, `raw`, capturing `set`).

**Live templates** (type the abbreviation, press Tab). Statements are offered in template text only; inside an open
`{{ }}` or `{% %}` only expression templates appear.

| Abbreviation | Result |
|---|---|
| `xx` | `{{ expr }}` |
| `block`, `inblock` | `{% block name %}…{% endblock name %}` |
| `if`, `ifi`, `ifelse`, `elif`, `else` | conditionals |
| `forloop`, `forkv`, `forelse` | loop, loop over a map (`key, value`), loop with `else` |
| `extend`, `include`, `super` | `extends`, `include`, `{{ super() }}` |
| `filter`, `raw`, `comment`, `set`, `setglobal` | the matching tags |
| `macro`, `import` | Tera 1 projects only |
| `component`, `comp`, `compbody`, `setblock`, `ternary`, `break`, `continue` | Tera 2 projects only |
| `comprehension` | Tera 2, inside `{{ }}` / `{% %}` only |

**Reformat Code** re-indents by the nesting of HTML and Tera tags and changes nothing else: no line is joined or split,
and the bodies of `pre`, `textarea`, `script`, `style`, `{% raw %}` and comments keep their indentation.

## 5. Completion, hover, documentation

- `{{ ` completes the variables of the render context with their Rust types; `task.` completes fields; loop variables
  (`{% for task in tasks %}`, `loop.index`), `set` variables and macro parameters are known.
- `|` completes filters and `is` completes tests; in Tera 2 the ones that fit the value come first. Function, filter and
  test arguments are completed; hover shows the signature, defaults and an example.
- **Macros** (Tera 1): after `ns::` the macros are offered with their required arguments; calls are checked for unknown
  or missing arguments and unresolved imports. Write the description in a `{# … #}` comment above the macro, with
  `@param name {type} text` lines, and it shows in the hover.
- **Components** (Tera 2): names are completed after `<`, attributes inside the tag, and the body form
  `{% <ui.card> %}…{% </ui.card> %}` is understood.
- Template names are completed inside `extends`, `include` and `import` strings.

## 6. Where the variables come from (Rust)

The plugin looks for the Rust code that renders the open template: any call that passes the template path as a string and
a serializable value (`Serialize`, `tera::Context`, `serde_json::Value`, `context!`), not only Rocket's
`Template::render`. It follows generic page shells (`Page<T>` with `#[serde(flatten)]`), `let ctx = …` locals and Rocket's
form `Context`. Layouts and partials get the variables of every page that uses them.

- Unknown variables (weak warning) and unknown fields are reported only when every render call was understood. Types from
  other crates are never guessed; the context is then marked incomplete and nothing is reported.
- **Your own functions, filters and tests** registered with `register_function`, `register_filter`, `register_tester`
  (or `register_test` in Tera 2) are offered with their arguments and documentation (the Rust doc comment). To tell the
  plugin what a filter returns, add `/// @returns array<string>` (also `map<T>`, `string`, `number`, `bool`, `T[]`).
- **No Rust type?** Describe the variables in a JSON Schema file: `detail.html.schema.json` next to the template, or
  `tera-context.json` in the template directory with a `mappings` table (glob → schema). Rust-derived variables win on a
  conflict. See `README.md` for the format.

## 7. Navigation

- **Go to definition** (Cmd/Ctrl+click or Cmd/Ctrl+B): variable → Rust field; macro, alias, argument name → definition;
  `extends`/`include`/`import` path → file; custom function/filter/test → Rust code. When there are several matches a
  chooser opens; each row shows the line of the template and `file:line`.
- **Blocks**: Cmd/Ctrl+click a `{% block x %}` name (or `endblock`, or `{{ super() }}`) to go to the block it replaces
  in the parent, through the whole `extends` chain; on the topmost definition it lists the templates that override it.
  Gutter icons say *Overrides block x* and *Overridden in N templates*.
- **Components**: the gutter icon on a definition shows the number of usages and opens a list of call sites; the icon on a
  call goes to the definition (one icon per line when a line calls several components).
- **From Rust to the template**: Cmd/Ctrl+click the path string in `render(..)` or use the gutter icon beside it. The path
  is read the way the program would (`Rocket.toml`, `Tera::new`, `relative!`, `set_current_dir`, your Template roots).
  While typing a path in Rust, the project's templates are offered, written the way the project writes them.

## 8. Lists, maps and loops

Printing a list or a map directly (`{{ categories }}`) shows something users do not want (`["Travel", "Meals"]` in Tera 2,
`[object]` for an object in Tera 1). The plugin marks it and offers fixes. Put the caret on the variable and press
Alt+Enter:

- *Tera: for category in categories* → `{% for category in categories %}{{ category }}{% endfor %}`
- *for … else (when empty)* → the same with an `{% else %}` branch
- *join* → `{{ categories | join(sep=", ") }}` (lists of plain values)
- for a map: `{% for key, value in map %}`; for a list of records the first text field is printed

It also works after filters that keep or produce a list (`| sort`, `| reverse`, `| unique`, `| slice(..)`, a map's
`| values` and `| keys`) and after your own filters when their return type is known. Example: with the caret in
`{{ categories | values | }}` use the loop fix and the whole block becomes
`{% for value in categories | values %}{{ value }}{% endfor %}`. A chain that stops after `|` is an error with a quick fix
that removes the bar.

## 9. Refactoring

**Shift+F6** renames, **Alt+F7** finds usages, for:

- **blocks** (the topmost definition is the declaration; overrides and every `endblock x` follow, across files);
- **macros** in Tera 1 (definition, `ns::name(` calls, `endmacro`);
- **variables** declared by `set`, `set_global`, `for` and macro parameters;
- **components**: definition, both call forms and the closing tags in one undoable step; a name already taken is refused.
  (For a dotted component name such as `ui.card`, use the gutter icon, Cmd/Ctrl+click and Rename; Alt+F7 is not available.)
- **template files**: renaming or moving a file updates `extends`, `include`, `import` and the Rust strings that name it
  (`render("pages/detail", …)`, in RustRover).

## 10. Tera 1 and Tera 2

- In a Tera 1 project, whatever Tera 2 removed or renamed is struck through with a weak warning and has a quick fix
  (`as_str` → `str`, `macro` → `component`, positional → keyword test arguments, …).
- **Alt+Enter → Migrate this template to Tera 2** rewrites the whole file; **Code → Migrate Tera Templates to 2.x…** does
  every template of the project as one undoable change and lists what still needs a person (for example `date` and
  `slugify` moved to `tera-contrib`).
- Tera 2 has types and enforces them while rendering. The plugin reports unknown parameter types, defaults of another
  type, attribute values of the wrong type and filters/tests applied to the wrong kind of value, with a *Convert with
  `| str` first* fix where it fits.
- A migration cannot change the engine's behaviour: a printed array is JSON in Tera 2, map order, undefined variables
  being errors, and so on (see `README.md`).

## 11. Errors in the Run window

When a Rust program prints a template error, the position is a link: Tera 2's ` --> pages/detail.html:30:49` (also after
Rocket's `template 'x': error:`) and Tera 1's `Failed to parse 'x'` with ` --> 3:10`. The file is looked up through the
template roots. The Run tool window is covered; the Terminal tool window is not.

## 12. Troubleshooting

| Symptom | Likely cause and what to do |
|---|---|
| No variables in completion | The render call was not found or its type is unknown. Check that the template path is passed as a string next to a `Serialize` value; or describe the data with a schema file. |
| Templates not found for `extends` or a Rust string | Add the directory under *Template roots*. |
| Wrong filters or "removed in Tera 2" hints | Set **Tera version** explicitly. |
| A list of usages still shows only a word | You are on an older build; reinstall the ZIP and restart the IDE completely. |
| Rust features do nothing | The Rust plugin is missing or disabled (needs RustRover, or the Rust plugin in CLion/IDEA). |
| Live template not offered | Statements are not offered inside an open `{{ }}` or `{% %}`; type it outside, or use Alt+Enter. |

## 13. Limits

Diagnostics are structural checks, not a Tera runtime. Rust discovery is static: values built at run time (maps filled
in a loop, `serde_json::Value`) are unknown, and templates added with `add_raw_template` have no file. Not done yet:
Zola-specific variables, a block-level hover, Alt+F7 for dotted component names, bold match highlighting in usage lists,
template paths produced by `macro_rules!`. `MANUAL-CHECK.md` lists what a person should try in the IDE.
