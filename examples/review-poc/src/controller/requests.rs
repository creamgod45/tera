use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::content::RawHtml;
use rocket::serde::Serialize;
use rocket::State;

use crate::store::{Request, Store, DEPARTMENTS};
use crate::templates::Templates;
use crate::view::page;

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct ListData {
    requests: Vec<Request>,
    status: String,
    department: String,
    departments: [&'static str; 5],
    counts: [u32; 4],
}

#[get("/requests?<status>&<department>")]
pub fn index(
    store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>,
    status: Option<&str>, department: Option<&str>,
) -> Result<RawHtml<String>, Status> {
    let (status, department) = (status.unwrap_or(""), department.unwrap_or(""));
    let data = store.0.lock().unwrap();
    let all = &data.items;
    let count = |s: &str| all.iter().filter(|r| r.status == s).count() as u32;
    let mut shown: Vec<Request> = all.iter()
        .filter(|r| (status.is_empty() || r.status == status) && (department.is_empty() || r.department == department))
        .cloned().collect();
    shown.sort_by(|a, b| b.id.cmp(&a.id));
    let counts = [all.len() as u32, count("pending"), count("approved"), count("rejected")];
    let ctx = page("Requests", "requests", flash, ListData {
        requests: shown, status: status.into(), department: department.into(), departments: DEPARTMENTS, counts,
    });
    tpl.render("pages/requests.html", &ctx)
}
