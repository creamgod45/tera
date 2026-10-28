//! The Tera 2 engine and how pages are rendered with it.

use std::path::Path;

use rocket::http::Status;
use rocket::response::content::RawHtml;
use rocket::serde::Serialize;
use tera::Tera;

/// Every `*.html.tera` below `templates/`, named by its path without `.tera` (`layouts/app.html`), so `.html` auto-escapes.
pub struct Templates(Tera);

impl Templates {
    pub fn load(dir: &Path) -> Result<Self, tera::Error> {
        fn walk(dir: &Path, root: &Path, out: &mut Vec<(String, String)>) {
            for entry in std::fs::read_dir(dir).expect("template directory") {
                let path = entry.expect("template entry").path();
                if path.is_dir() {
                    walk(&path, root, out);
                } else if let Some(name) = path.strip_prefix(root).ok().and_then(|p| p.to_str()).and_then(|p| p.strip_suffix(".tera")) {
                    out.push((name.replace('\\', "/"), std::fs::read_to_string(&path).expect("readable template")));
                }
            }
        }
        let mut files = vec![];
        walk(dir, dir, &mut files);
        let mut tera = Tera::default();
        tera.autoescape_on([".html"]);
        tera.add_raw_templates(files.iter().map(|(n, c)| (n.as_str(), c.as_str())))?;
        Ok(Templates(tera))
    }

    pub fn render<C: Serialize>(&self, name: &str, context: &C) -> Result<RawHtml<String>, Status> {
        let context = tera::Context::from_serialize(context).map_err(|e| {
            rocket::error_!("context for '{}': {}", name, e);
            Status::InternalServerError
        })?;
        self.0.render(name, &context).map(RawHtml).map_err(|e| {
            rocket::error_!("template '{}': {}", name, e);
            Status::InternalServerError
        })
    }
}
