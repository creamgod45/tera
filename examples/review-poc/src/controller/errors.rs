use rocket::response::content::RawHtml;
use rocket::serde::Serialize;

use crate::templates::Templates;
use crate::view::page;

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct NotFoundData {
    path: String,
}

#[catch(404)]
pub fn not_found(req: &rocket::Request<'_>) -> RawHtml<String> {
    let tpl = req.rocket().state::<Templates>().expect("templates are managed");
    tpl.render("pages/not_found.html", &page("Not found", "", None, NotFoundData { path: req.uri().to_string() }))
        .unwrap_or_else(|_| RawHtml("Not found".into()))
}
