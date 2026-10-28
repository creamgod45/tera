# Rocket's `examples/forms` templates, migrated to Tera 2

`forms/templates/*.html.tera` are the templates in `../rocket/examples/forms/templates` (Rocket, MIT, see
`../rocket/LICENSE-MIT`) after running the plugin's migration (`TeraMigration.migrate`, "Migrate this template to Tera
2"). They are golden files: `TeraMigrationRocketTest` re-runs the migration and expects exactly these files.

What changed, and why:

| Tera 1 | Tera 2 |
|---|---|
| `{% import "macros" as m %}` | removed, components are global |
| `{% macro value_for(name) %}` … `{% endmacro value_for %}` | `{% component value_for(name, @values) %}` … `{% endcomponent value_for %}` |
| body reads the context variables `values` / `errors` | declared as implicit parameters `@values` / `@errors`: a component does not see the render context |
| `{{ m::input(label="Name", type="text", name="account.name") }}` | `{{ <input label="Name" type="text" name="account.name"/> }}` |
| `self::value_for(name=name)` | `<value_for name/>` (shorthand for `name={name}`) |
| `options=["Public", "Reserved"]` | `options={["Public", "Reserved"]}` |
| `{% if x is containing(value) %}` | `{% if x is containing(pat=value) %}` |

`success.html.tera` needs no change. They were checked by rendering them with the real engines, see
`tools/verify-migration`.
