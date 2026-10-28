//! Rocket's `examples/forms`, on Tera 2.
//!
//! The routes, the form types and the validation are the ones of the original example. What changed is the templating:
//! `rocket_dyn_templates` (Tera 1) is replaced by a few lines that load the templates into a `tera::Tera` (2.x), keep it in
//! Rocket's managed state and render with it. The templates were migrated with the plugin, see `README.md`.

#[macro_use] extern crate rocket;

#[cfg(test)] mod tests;

use std::path::Path;

use rocket::{State, time::Date};
use rocket::http::{Status, ContentType};
use rocket::response::content::RawHtml;
use rocket::form::{Form, Contextual, FromForm, FromFormField, Context};
use rocket::fs::{FileServer, TempFile, relative};
use rocket::serde::Serialize;
use tera::Tera;

/// The templates, loaded once at startup.
struct Templates(Tera);

impl Templates {
    /// Loads every `*.html.tera` in `dir`, named without the `.tera` (`index.html`), so that `.html` templates are
    /// auto-escaped. Components (`macros.html.tera`) are global once their template is added.
    fn load(dir: &Path) -> Result<Self, tera::Error> {
        let mut tera = Tera::default();
        tera.autoescape_on([".html"]);
        // Tera 2 only has what tera-contrib adds when it is registered.
        tera.register_function("now", tera_contrib::dates::now);

        let mut files = vec![];
        for entry in std::fs::read_dir(dir).expect("template directory") {
            let path = entry.expect("template entry").path();
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_string();
            if let Some(name) = name.strip_suffix(".tera") {
                files.push((name.to_string(), std::fs::read_to_string(&path).expect("readable template")));
            }
        }
        tera.add_raw_templates(files.iter().map(|(n, c)| (n.as_str(), c.as_str())))?;
        Ok(Templates(tera))
    }

    fn render<C: Serialize>(&self, name: &str, context: &C) -> Result<RawHtml<String>, Status> {
        let context = tera::Context::from_serialize(context).map_err(|e| {
            error_!("template context for '{}': {}", name, e);
            Status::InternalServerError
        })?;
        self.0.render(name, &context).map(RawHtml).map_err(|e| {
            error_!("template '{}': {}", name, e);
            Status::InternalServerError
        })
    }
}

#[derive(Debug, FromForm)]
struct Password<'v> {
    #[field(validate = len(6..))]
    #[field(validate = eq(self.second))]
    #[allow(unused)]
    first: &'v str,
    #[allow(unused)]
    #[field(validate = eq(self.first))]
    second: &'v str,
}

#[derive(Debug, FromFormField)]
enum Rights {
    Public,
    Reserved,
    Exclusive,
}

#[derive(Debug, FromFormField)]
enum Category {
    Biology,
    Chemistry,
    Physics,
    #[field(value = "CS")]
    ComputerScience,
}

#[derive(Debug, FromForm)]
#[allow(dead_code)]
struct Submission<'v> {
    #[field(validate = len(1..))]
    title: &'v str,
    date: Date,
    #[field(validate = len(1..=250))]
    r#abstract: &'v str,
    #[field(validate = ext(ContentType::PDF))]
    file: TempFile<'v>,
    #[field(validate = len(1..))]
    category: Vec<Category>,
    rights: Rights,
    ready: bool,
}

#[derive(Debug, FromForm)]
#[allow(dead_code)]
struct Account<'v> {
    #[field(validate = len(1..))]
    name: &'v str,
    password: Password<'v>,
    #[field(validate = contains('@').or_else(msg!("invalid email address")))]
    email: &'v str,
}

#[derive(Debug, FromForm)]
#[allow(dead_code)]
struct Submit<'v> {
    account: Account<'v>,
    submission: Submission<'v>,
}

#[get("/")]
fn index(templates: &State<Templates>) -> Result<RawHtml<String>, Status> {
    templates.render("index.html", &Context::default())
}

// NOTE: We use `Contextual` here because we want to collect all submitted form
// fields to re-render forms with submitted values on error. If you have no such
// need, do not use `Contextual`. Use the equivalent of `Form<Submit<'_>>`.
#[post("/", data = "<form>")]
fn submit<'r>(
    templates: &State<Templates>,
    form: Form<Contextual<'r, Submit<'r>>>,
) -> Result<(Status, RawHtml<String>), Status> {
    let template = match form.value {
        Some(ref submission) => {
            println!("submission: {:#?}", submission);
            templates.render("success.html", &form.context)?
        }
        None => templates.render("index.html", &form.context)?,
    };

    Ok((form.context.status(), template))
}

macro_rules! page_ctx {
    ($name:ident { $($field:ident : $ty:ty),* }) => {
        #[derive(Serialize)]
        #[serde(crate = "rocket::serde")]
        struct $name { $($field: $ty),* }
    };
}
page_ctx!(PageCtx { title: String, count: u32 });

#[get("/")]
fn page(templates: &State<Templates>) -> Result<RawHtml<String>, Status> {
    let mut string = String::new();
    string.push_str("hello world!!");
    templates.render("page.html", &PageCtx { title: string, count: 1 })
}

#[launch]
fn rocket() -> _ {
    let templates = Templates::load(Path::new(relative!("templates"))).expect("Tera 2 templates");
    rocket::build()
        .manage(templates)
        .mount("/", routes![index, submit])
        .mount("/page", routes![page])
        .mount("/", FileServer::from(relative!("/static")))
}
