use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::content::RawHtml;
use rocket::serde::Serialize;
use rocket::State;

use crate::store::{self, Request, Stats, Store};
use crate::templates::Templates;
use crate::view::page;

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct DashboardData {
    stats: Stats,
    recent: Vec<Request>,
    waiting: Vec<Request>,
}

#[get("/")]
pub fn index(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let data = store.0.lock().unwrap();
    let mut recent: Vec<Request> = data.items.iter().rev().take(6).cloned().collect();
    recent.sort_by(|a, b| b.id.cmp(&a.id));
    let waiting = data.items.iter().filter(|r| r.status == "pending").cloned().collect();
    let ctx = page("Dashboard", "dashboard", flash, DashboardData { stats: store::stats(&data.items), recent, waiting });
    tpl.render("pages/dashboard.html", &ctx)
}
