use rocket::http::Status;
use rocket::request::FlashMessage;
use rocket::response::content::RawHtml;
use rocket::serde::Serialize;
use rocket::State;

use crate::store::{self, Stats, Store};
use crate::templates::Templates;
use crate::view::page;

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
struct StatsData {
    stats: Stats,
}

#[get("/stats")]
pub fn index(store: &State<Store>, tpl: &State<Templates>, flash: Option<FlashMessage<'_>>) -> Result<RawHtml<String>, Status> {
    let stats = store::stats(&store.0.lock().unwrap().items);
    tpl.render("pages/stats.html", &page("Statistics", "stats", flash, StatsData { stats }))
}
