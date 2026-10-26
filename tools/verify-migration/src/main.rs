use std::fs;

/// Rocket's examples/forms templates exactly as they are in Rocket (Tera 1) ...
const ORIGINAL: &str = "../../src/test/resources/rocket/examples/forms/templates";
/// ... and the same templates after the plugin's migration to Tera 2.
const MIGRATED: &str = "../../src/test/resources/rocket-tera2/forms/templates";

fn contexts() -> Vec<(&'static str, serde_json::Value)> {
    use serde_json::json;
    vec![
        ("default (Context::default())", json!({"errors": {}, "values": {}, "data_fields": [], "form_errors": []})),
        ("with errors and values", json!({
            "errors": {
                "account.name": [{"name": "account.name", "msg": "value is too short"}],
                "submission.title": [{"name": "submission.title", "msg": "value is required"}, {"name": "submission.title", "msg": "second problem"}]
            },
            "values": {
                "account.name": ["Bob"], "account.email": ["bob@example.com"],
                "submission.title": [""], "submission.category": ["Biology", "CS"],
                "submission.rights": ["Reserved"], "submission.ready": ["yes"], "submission.abstract": ["An abstract"]
            },
            "data_fields": ["submission.file"],
            "form_errors": []
        })),
    ]
}

fn norm(s: &str) -> String { s.split_whitespace().collect::<Vec<_>>().join(" ") }

fn names() -> Vec<(&'static str, String)> {
    ["macros", "index", "success"].iter().map(|n| (*n, format!("{n}.html.tera"))).collect()
}

fn render_v1(dir: &str, template: &str, ctx: &serde_json::Value) -> Result<String, String> {
    let mut t = tera1::Tera::default();
    t.autoescape_on(vec![]);
    let files: Vec<(String, String)> = names().into_iter()
        .map(|(n, f)| (n.to_string(), fs::read_to_string(format!("{dir}/{f}")).unwrap())).collect();
    t.add_raw_templates(files.iter().map(|(n, c)| (n.as_str(), c.as_str()))).map_err(|e| format!("load: {e:?}"))?;
    let c = tera1::Context::from_value(ctx.clone()).map_err(|e| format!("ctx: {e}"))?;
    t.render(template, &c).map_err(|e| format!("render: {e:?}"))
}

fn render_v2(dir: &str, template: &str, ctx: &serde_json::Value) -> Result<String, String> {
    let mut t = tera2::Tera::default();
    t.autoescape_on(Vec::<&str>::new());
    let files: Vec<(String, String)> = names().into_iter()
        .map(|(n, f)| (n.to_string(), fs::read_to_string(format!("{dir}/{f}")).unwrap())).collect();
    t.add_raw_templates(files.iter().map(|(n, c)| (n.as_str(), c.as_str()))).map_err(|e| format!("load: {e}"))?;
    let c = tera2::Context::from_serialize(ctx).map_err(|e| format!("ctx: {e}"))?;
    t.render(template, &c).map_err(|e| format!("render: {e}"))
}

fn main() {
    let mut failures = 0;
    for (label, ctx) in contexts() {
        for template in ["index", "success"] {
            let a = render_v1(ORIGINAL, template, &ctx);
            let b = render_v2(MIGRATED, template, &ctx);
            match (&a, &b) {
                (Ok(x), Ok(y)) if norm(x) == norm(y) => println!("OK    {template:8} [{label}] identical after whitespace normalisation ({} chars)", norm(x).len()),
                // Known engine difference: printing an array. Tera 1 prints [a, b]; Tera 2 prints JSON, ["a", "b"].
                (Ok(x), Ok(y)) if template == "success" && norm(x).replace('"', "") == norm(y).replace('"', "") =>
                    println!("OK*   {template:8} [{label}] identical except that Tera 2 prints arrays as JSON ([\"a\"] instead of [a])"),
                (Ok(x), Ok(y)) => { failures += 1; println!("DIFF  {template:8} [{label}]\n--- tera 1.20.1 original\n{}\n--- tera 2.4.0 migrated\n{}", norm(x), norm(y)); }
                (a, b) => { failures += 1; println!("ERROR {template:8} [{label}]\n  v1: {:?}\n  v2: {:?}", a.as_ref().err(), b.as_ref().err()); }
            }
        }
    }
    if failures > 0 { std::process::exit(1); }
}
